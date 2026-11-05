package com.ivarna.fluxlinux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.ContentCopy
import androidx.compose.material.icons.sharp.Delete
import androidx.compose.material.icons.sharp.History
import androidx.compose.material.icons.sharp.PlayArrow
import androidx.compose.material.icons.sharp.Refresh
import androidx.compose.material.icons.sharp.Stop
import androidx.compose.material.icons.sharp.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.data.ScriptManager
import com.ivarna.fluxlinux.core.desktop.DesktopLauncher
import com.ivarna.fluxlinux.core.legacy.LegacyTermuxBridge
import com.ivarna.fluxlinux.core.legacy.LegacyTermuxStore
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.ui.components.aka.AkaRow
import com.ivarna.fluxlinux.ui.components.aka.MarkState
import com.ivarna.fluxlinux.ui.components.aka.PlatedButton
import com.ivarna.fluxlinux.ui.components.aka.SectionLabel
import com.ivarna.fluxlinux.ui.components.aka.StatusMark
import com.ivarna.fluxlinux.ui.theme.AkaTheme
import com.ivarna.fluxlinux.ui.theme.CodeStyle
import com.ivarna.fluxlinux.ui.theme.FluxSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "LegacyTermux"
private const val FIX_COMMAND =
    "mkdir -p ~/.termux && echo \"allow-external-apps = true\" >> ~/.termux/termux.properties && termux-reload-settings"

enum class LegacyPageState {
    IDLE,
    SCANNING,
    UNINSTALLING,
    TIMED_OUT
}

enum class LegacyTimeoutType {
    NONE,
    LIST,
    UNINSTALL
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun LegacyTermuxSettingsScreen(
    onBack: () -> Unit,
    permissionState: PermissionState
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scriptManager = remember { ScriptManager(context) }

    var pageState by remember { mutableStateOf(LegacyPageState.IDLE) }
    var activeJobId by remember { mutableLongStateOf(0L) }
    var lastTimeoutType by remember { mutableStateOf(LegacyTimeoutType.NONE) }
    var uninstallTargetId by remember { mutableStateOf<String?>(null) }
    var confirmUninstallRow by remember { mutableStateOf<LegacyTermuxStore.Row?>(null) }
    var showPermissionHelpDialog by remember { mutableStateOf(false) }

    // Lifecycle observer to re-read package state on resume
    var lifecycleKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                lifecycleKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val termuxInstalled = remember(lifecycleKey) { LegacyTermuxBridge.isTermuxInstalled(context) }
    val termuxVersion = remember(lifecycleKey) { LegacyTermuxBridge.termuxVersionName(context) }
    val termuxVersionOk = remember(lifecycleKey) { LegacyTermuxBridge.isTermuxVersionOk(context) }
    val termuxX11Installed = remember(lifecycleKey) { LegacyTermuxBridge.isTermuxX11Installed(context) }
    val runCommandGranted = remember(lifecycleKey, permissionState.status) {
        LegacyTermuxBridge.hasRunCommandPermission(context) || permissionState.status.isGranted
    }

    val refreshTrigger by StateManager.refreshTrigger.collectAsState()
    val scan = remember(refreshTrigger, lifecycleKey) { LegacyTermuxStore.load(context) }
    val isPingOk = remember(refreshTrigger, lifecycleKey) { LegacyTermuxStore.isPingOk(context) }

    val embeddedDesktopActive = remember(refreshTrigger, lifecycleKey) {
        isEmbeddedDesktopActive(context)
    }

    val isBusy = pageState == LegacyPageState.SCANNING || pageState == LegacyPageState.UNINSTALLING
    val canInteract = termuxInstalled && termuxVersionOk && runCommandGranted && !isBusy

    fun triggerScan() {
        if (!termuxInstalled || !termuxVersionOk || !runCommandGranted || isBusy) return

        try {
            val script = scriptManager.getScriptContent("legacy-termux/list_proot.sh")
            val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val spec = LegacyTermuxBridge.buildListSpec(b64)
            val intent = LegacyTermuxBridge.toIntent(spec)
            val launched = LegacyTermuxBridge.startSafely(context, intent)
            if (launched) {
                val jobId = System.currentTimeMillis()
                activeJobId = jobId
                pageState = LegacyPageState.SCANNING
                lastTimeoutType = LegacyTimeoutType.NONE
                coroutineScope.launch {
                    delay(15_000L)
                    if (pageState == LegacyPageState.SCANNING && activeJobId == jobId) {
                        pageState = LegacyPageState.TIMED_OUT
                        lastTimeoutType = LegacyTimeoutType.LIST
                    }
                }
            } else {
                Toast.makeText(context, "Failed to start Termux scan", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Scan trigger failed", e)
            Toast.makeText(context, "Scan error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Auto-scan on enter / resume whenever gates are satisfied
    LaunchedEffect(lifecycleKey, termuxInstalled, termuxVersionOk, runCommandGranted) {
        if (termuxInstalled && termuxVersionOk && runCommandGranted && pageState == LegacyPageState.IDLE) {
            triggerScan()
        }
    }

    // Track handled scan updates so initial composition does not clear SCANNING
    var lastHandledScanMs by remember { mutableLongStateOf(scan.scannedAtMs) }
    var lastHandledError by remember { mutableStateOf(scan.error) }
    var lastHandledRows by remember { mutableStateOf(scan.rows) }
    var lastHandledActionMs by remember { mutableLongStateOf(scan.lastActionMs) }

    LaunchedEffect(scan.scannedAtMs, scan.error, scan.rows, scan.lastActionMs) {
        val scanChanged = scan.scannedAtMs != lastHandledScanMs || scan.error != lastHandledError
        val rowsChanged = scan.rows != lastHandledRows
        val actionChanged = scan.lastActionMs != lastHandledActionMs

        if (scanChanged || rowsChanged || actionChanged) {
            lastHandledScanMs = scan.scannedAtMs
            lastHandledError = scan.error
            lastHandledRows = scan.rows
            lastHandledActionMs = scan.lastActionMs

            // Any applied callback response (list/uninstall success or error) returns page to IDLE
            if (pageState == LegacyPageState.SCANNING || pageState == LegacyPageState.TIMED_OUT || pageState == LegacyPageState.UNINSTALLING) {
                pageState = LegacyPageState.IDLE
                uninstallTargetId = null
                lastTimeoutType = LegacyTimeoutType.NONE
                activeJobId = 0L
            }
        }
    }

    fun triggerOpenTerminal(row: LegacyTermuxStore.Row) {
        if (!termuxInstalled || !termuxVersionOk || !runCommandGranted || isBusy) return
        if (row.id == "termux") return

        try {
            val script = scriptManager.getScriptContent("legacy-termux/login_proot.sh")
            val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val spec = LegacyTermuxBridge.buildLoginSpec(b64, row.id) ?: return
            val intent = LegacyTermuxBridge.toIntent(spec)
            val launched = LegacyTermuxBridge.startSafely(context, intent)
            if (launched) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                }
            } else {
                Toast.makeText(context, "Failed to launch Termux session", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login failed", e)
            Toast.makeText(context, "Login error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerStartDisplay(row: LegacyTermuxStore.Row) {
        if (!termuxInstalled || !termuxVersionOk || !runCommandGranted || isBusy) return
        if (row.id == "termux") return

        if (embeddedDesktopActive) {
            Toast.makeText(context, "Stop the desktop first.", Toast.LENGTH_LONG).show()
            return
        }
        if (!termuxX11Installed) {
            Toast.makeText(context, "Termux:X11 is not installed", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val script = scriptManager.getScriptContent("legacy-termux/start_display.sh")
            val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val spec = LegacyTermuxBridge.buildStartDisplaySpec(b64, row.id) ?: return
            val intent = LegacyTermuxBridge.toIntent(spec)
            val launched = LegacyTermuxBridge.startSafely(context, intent)
            if (!launched) {
                Toast.makeText(context, "Failed to start leftover display", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Start display failed", e)
            Toast.makeText(context, "Start display error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerStopDisplay(row: LegacyTermuxStore.Row) {
        if (!termuxInstalled || !termuxVersionOk || !runCommandGranted || isBusy) return
        if (row.id == "termux") return

        try {
            val script = scriptManager.getScriptContent("legacy-termux/stop_display.sh")
            val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val spec = LegacyTermuxBridge.buildStopDisplaySpec(b64, row.id) ?: return
            val intent = LegacyTermuxBridge.toIntent(spec)
            val launched = LegacyTermuxBridge.startSafely(context, intent)
            if (!launched) {
                Toast.makeText(context, "Failed to stop leftover display", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stop display failed", e)
            Toast.makeText(context, "Stop display error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerUninstall(row: LegacyTermuxStore.Row) {
        if (!termuxInstalled || !termuxVersionOk || !runCommandGranted || isBusy) return
        if (row.id == "termux") return

        val targetId = row.id
        try {
            val script = scriptManager.getScriptContent("legacy-termux/uninstall_proot.sh")
            val b64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val spec = LegacyTermuxBridge.buildUninstallSpec(b64, targetId) ?: return
            val intent = LegacyTermuxBridge.toIntent(spec)
            val launched = LegacyTermuxBridge.startSafely(context, intent)
            if (launched) {
                val jobId = System.currentTimeMillis()
                activeJobId = jobId
                pageState = LegacyPageState.UNINSTALLING
                uninstallTargetId = targetId
                lastTimeoutType = LegacyTimeoutType.NONE
                coroutineScope.launch {
                    delay(20_000L)
                    if (pageState == LegacyPageState.UNINSTALLING && activeJobId == jobId) {
                        pageState = LegacyPageState.TIMED_OUT
                        lastTimeoutType = LegacyTimeoutType.UNINSTALL
                        Toast.makeText(context, "Termux did not confirm removal; tap Scan.", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Toast.makeText(context, "Failed to request Termux uninstall", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Uninstall trigger failed", e)
            Toast.makeText(context, "Uninstall error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyAndOpenTermux() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Termux Fix", FIX_COMMAND))
        val launchIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
        if (launchIntent != null) {
            context.startActivity(launchIntent)
            Toast.makeText(context, "Command copied! Paste in Termux", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(context, "Termux not found!", Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerPing() {
        if (!termuxInstalled || !termuxVersionOk || !runCommandGranted || isBusy) return

        try {
            val spec = LegacyTermuxBridge.buildPingSpec()
            val intent = LegacyTermuxBridge.toIntent(spec)
            val launched = LegacyTermuxBridge.startSafely(context, intent)
            if (launched) {
                Toast.makeText(context, "Ping sent to Termux", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Ping failed to start", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ping failed", e)
        }
    }

    SettingsPageScaffold(
        title = "Legacy Termux",
        onBack = onBack,
        actions = {
            IconButton(
                onClick = { triggerScan() },
                enabled = !isBusy && termuxInstalled && termuxVersionOk && runCommandGranted
            ) {
                if (pageState == LegacyPageState.SCANNING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Sharp.Refresh, contentDescription = "Scan")
                }
            }
        }
    ) {
        // Where these leftovers live
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm)
        ) {
            Text(
                "Leftovers live in com.termux, not app storage",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(FluxSpacing.xxs))
            Text(
                "These are containers inside the Termux app, not Settings → PRoot.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SectionLabel("Prerequisites")

        // Row 1: Termux app + version
        PrereqRow(
            title = "Termux app",
            statusText = when {
                !termuxInstalled -> "Not installed"
                !termuxVersionOk -> "v${termuxVersion ?: "unknown"} (< v${LegacyTermuxBridge.MIN_TERMUX_VERSION})"
                else -> "v${termuxVersion} (ready)"
            },
            isOk = termuxInstalled && termuxVersionOk,
            ctaText = when {
                !termuxInstalled -> "Download"
                !termuxVersionOk -> "Update"
                else -> null
            },
            onCta = {
                openUrl(context, "https://f-droid.org/packages/com.termux/")
            }
        )
        if (termuxInstalled && !termuxVersionOk) {
            SettingsPageAttention(
                "Play Store version will not work. Install v0.118.3 or newer from F-Droid or GitHub releases.",
                failed = true
            )
        }

        // Row 2: Termux:X11
        PrereqRow(
            title = "Termux:X11",
            statusText = if (termuxX11Installed) "Installed (ready)" else "Not installed",
            isOk = termuxX11Installed,
            ctaText = if (!termuxX11Installed) "Get X11" else null,
            onCta = {
                openUrl(context, "https://github.com/termux/termux-x11/releases")
            }
        )

        // Row 3: RUN_COMMAND
        PrereqRow(
            title = "RUN_COMMAND",
            codeTitle = true,
            statusText = if (runCommandGranted) "Granted" else if (!termuxInstalled) "Termux not installed" else "Denied",
            isOk = runCommandGranted,
            ctaText = if (!runCommandGranted) "Grant" else null,
            onCta = {
                if (!termuxInstalled) {
                    Toast.makeText(context, "Install Termux first to grant RUN_COMMAND permission", Toast.LENGTH_LONG).show()
                    openUrl(context, "https://f-droid.org/packages/com.termux/")
                } else {
                    try {
                        permissionState.launchPermissionRequest()
                    } catch (e: Exception) {
                        Log.e(TAG, "launchPermissionRequest failed", e)
                    }
                    showPermissionHelpDialog = true
                }
            }
        )

        // Row 4: allow-external-apps
        val isConfigured = isPingOk || (scan.scannedAtMs > 0 && scan.error == null)
        PrereqRow(
            title = "allow-external-apps",
            codeTitle = true,
            statusText = if (isConfigured) "Configured" else "Unknown, needs fix",
            isOk = isConfigured,
            ctaText = null,
            onCta = null
        )

        // Fix block if not configured or if timed out
        if (!isConfigured || pageState == LegacyPageState.TIMED_OUT) {
            SettingsPageBlock {
                Text(
                    "Fix: allow-external-apps = true",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(FluxSpacing.sm))
                Text(
                    FIX_COMMAND,
                    style = CodeStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceContainerLowest,
                            MaterialTheme.shapes.small
                        )
                        .padding(FluxSpacing.md)
                )
                Spacer(Modifier.height(FluxSpacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlatedButton(
                        text = "Copy & open Termux",
                        onClick = { copyAndOpenTermux() },
                        icon = Icons.Sharp.ContentCopy,
                        contentPadding = PaddingValues(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md),
                        modifier = Modifier.weight(1f)
                    )
                    SettingsPageSecondaryButton(
                        text = "Ping",
                        onClick = { triggerPing() },
                        enabled = canInteract
                    )
                }
            }
        }

        // Timed out note
        if (pageState == LegacyPageState.TIMED_OUT) {
            SettingsPageAttention(
                if (lastTimeoutType == LegacyTimeoutType.UNINSTALL) {
                    "Termux did not confirm removal of '${uninstallTargetId ?: "container"}'; tap Scan to check if it was removed."
                } else {
                    "Termux did not respond. Enable allow-external-apps = true and grant RUN_COMMAND."
                },
                failed = true
            )
        }

        // Status / error message
        if (scan.error != null) {
            SettingsPageAttention(scan.error, failed = true)
        }

        // Discovered leftovers header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom
        ) {
            SectionLabel("Leftover containers", modifier = Modifier.weight(1f))
            if (scan.scannedAtMs > 0) {
                val ageMinutes = (System.currentTimeMillis() - scan.scannedAtMs) / 60000
                Text(
                    if (ageMinutes <= 0) "Just scanned" else "Scanned ${ageMinutes}m ago",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = FluxSpacing.lg, bottom = FluxSpacing.sm)
                )
            }
        }

        // Rows list or empty states
        if (!termuxInstalled) {
            AkaRow(
                title = "Termux not installed",
                supporting = "The Termux app is not installed. Leftover FluxLinux ≤ v1.8.0 containers lived there.",
                icon = Icons.Sharp.History
            )
        } else if (scan.rows.isEmpty() && scan.scannedAtMs > 0 && scan.error == null) {
            MarkedRow(
                mark = MarkState.RUNNING,
                markColor = AkaTheme.inks.running,
                title = "No leftovers found",
                supporting = "No leftover PRoot containers found in Termux."
            )
        } else {
            val isScanFreshForUninstall = (System.currentTimeMillis() - scan.scannedAtMs) < 30 * 60 * 1000L

            scan.rows.forEachIndexed { index, row ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(horizontal = FluxSpacing.lg)
                    )
                }
                LeftoverRow(
                    row = row,
                    isBusy = isBusy,
                    isUninstallActive = pageState == LegacyPageState.UNINSTALLING && uninstallTargetId == row.id,
                    termuxX11Installed = termuxX11Installed,
                    embeddedDesktopActive = embeddedDesktopActive,
                    canInteract = canInteract,
                    canUninstall = isScanFreshForUninstall && canInteract,
                    onLoginTerminal = { triggerOpenTerminal(row) },
                    onStartDisplay = { triggerStartDisplay(row) },
                    onStopDisplay = { triggerStopDisplay(row) },
                    onUninstall = {
                        if (!isBusy) {
                            confirmUninstallRow = row
                        }
                    }
                )
            }
        }

        // Bottom scan action
        if (termuxInstalled && termuxVersionOk && runCommandGranted) {
            Spacer(Modifier.height(FluxSpacing.md))
            PlatedButton(
                text = if (pageState == LegacyPageState.SCANNING) "Scanning Termux…" else "Scan leftovers",
                onClick = { triggerScan() },
                enabled = !isBusy,
                icon = if (pageState == LegacyPageState.SCANNING) null else Icons.Sharp.Refresh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FluxSpacing.lg)
            )
        }
    }

    // Uninstall confirmation dialog
    val targetToConfirm = confirmUninstallRow
    if (targetToConfirm != null) {
        val pathText = if (!targetToConfirm.hostPath.isNullOrBlank()) {
            "\n\nPath: ${targetToConfirm.hostPath}"
        } else ""

        AlertDialog(
            onDismissRequest = {
                if (!isBusy) confirmUninstallRow = null
            },
            title = {
                Text(if (targetToConfirm.id == "debian") "Delete legacy Debian container?" else "Delete Termux container?")
            },
            text = {
                val promptText = if (targetToConfirm.id == "debian") {
                    "Delete the legacy FluxLinux ≤ v1.8 Termux container 'debian'?"
                } else {
                    "Delete the Termux proot-distro container '${targetToConfirm.id}' (not installed by FluxLinux ≤ v1.8)?"
                }
                Text(
                    "$promptText$pathText\n\nThis removes the container from Termux and does not touch AkaTerminal's app storage (Settings → PRoot).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toRemove = targetToConfirm
                        confirmUninstallRow = null
                        if (!isBusy) {
                            triggerUninstall(toRemove)
                        }
                    },
                    enabled = !isBusy,
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Uninstall")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmUninstallRow = null },
                    enabled = !isBusy
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    // Permission help dialog (Android 11+ / MIUI custom dangerous permission guidance)
    if (showPermissionHelpDialog && !runCommandGranted) {
        AlertDialog(
            onDismissRequest = { showPermissionHelpDialog = false },
            title = { Text("Grant RUN_COMMAND") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(FluxSpacing.md)) {
                    Text(
                        "On modern Android, custom permissions like RUN_COMMAND must be enabled manually in App Info → Permissions → Additional Permissions / All Permissions.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Or grant via ADB:\npm grant ${context.packageName} com.termux.permission.RUN_COMMAND",
                        style = CodeStyle,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showPermissionHelpDialog = false
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to open app settings", e)
                        }
                    }
                ) {
                    Text("Open app info")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionHelpDialog = false }) {
                    Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

/** A prerequisite: its name, its state as mark + words, and the fix when there is one. */
@Composable
private fun PrereqRow(
    title: String,
    statusText: String,
    isOk: Boolean,
    ctaText: String?,
    onCta: (() -> Unit)?,
    codeTitle: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = if (codeTitle) CodeStyle else MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(FluxSpacing.xxs))
            SettingsPageStatus(
                text = statusText,
                state = if (isOk) MarkState.RUNNING else MarkState.FAILED
            )
        }
        if (ctaText != null && onCta != null) {
            Spacer(Modifier.width(FluxSpacing.md))
            SettingsPageSecondaryButton(text = ctaText, onClick = onCta)
        }
    }
}

/** A list row led by a state mark instead of an icon (e.g. an all-clear result). */
@Composable
private fun MarkedRow(
    mark: MarkState,
    markColor: Color,
    title: String,
    supporting: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            StatusMark(state = mark, color = markColor, size = 12.dp)
        }
        Spacer(Modifier.width(FluxSpacing.lg))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LeftoverRow(
    row: LegacyTermuxStore.Row,
    isBusy: Boolean,
    isUninstallActive: Boolean,
    termuxX11Installed: Boolean,
    embeddedDesktopActive: Boolean,
    canInteract: Boolean,
    canUninstall: Boolean,
    onLoginTerminal: () -> Unit,
    onStartDisplay: () -> Unit,
    onStopDisplay: () -> Unit,
    onUninstall: () -> Unit
) {
    @Suppress("UNUSED_PARAMETER")
    val _isBusy = isBusy
    val isDebianLeftover = row.id == "debian"
    val isNativeTermux = row.id == "termux"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = FluxSpacing.sm)
    ) {
        // Header: name, size, kind
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Sharp.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(FluxSpacing.lg))
            Column(Modifier.weight(1f)) {
                Text(
                    row.id,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    formatBytes(row.bytes),
                    style = CodeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(FluxSpacing.md))
            Text(
                text = when {
                    isDebianLeftover -> "v1.8 leftover"
                    isNativeTermux -> "Native"
                    else -> "Other Termux container"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (isDebianLeftover) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Path line
        if (!row.hostPath.isNullOrBlank()) {
            Text(
                row.hostPath,
                style = CodeStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.xs)
            )
        }

        // Start disclaimer / conflict note
        if (embeddedDesktopActive) {
            SettingsPageAttention(
                "Stop the desktop first. Leftover Termux:X11 and embedded XFCE should not run together.",
                failed = true
            )
        } else if (isNativeTermux) {
            SettingsPageAttention(
                "Termux native container management is not supported.",
                failed = true
            )
        } else if (isDebianLeftover) {
            SettingsPageNote("Start: XFCE in Termux:X11 (v1.8 protocol). KDE leftovers are not supported.")
        } else {
            SettingsPageNote(
                "Termux container (not installed by FluxLinux ≤ v1.8). Start: XFCE in Termux:X11 if installed inside container."
            )
        }

        // Actions
        Column(
            modifier = Modifier.padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
            ) {
                SettingsPageSecondaryButton(
                    text = "Open terminal",
                    onClick = onLoginTerminal,
                    enabled = canInteract && !isNativeTermux,
                    icon = Icons.Sharp.Terminal,
                    modifier = Modifier.weight(1f)
                )
                SettingsPageSecondaryButton(
                    text = "Start display",
                    onClick = onStartDisplay,
                    enabled = canInteract && termuxX11Installed && !embeddedDesktopActive && !isNativeTermux,
                    icon = Icons.Sharp.PlayArrow,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
            ) {
                SettingsPageSecondaryButton(
                    text = "Stop display",
                    onClick = onStopDisplay,
                    enabled = canInteract && !isNativeTermux,
                    icon = Icons.Sharp.Stop,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onUninstall,
                    enabled = canUninstall && !isNativeTermux,
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (canUninstall && !isNativeTermux) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.outlineVariant
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = FluxSpacing.touch)
                ) {
                    if (isUninstallActive) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.error,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Sharp.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(FluxSpacing.sm))
                    Text("Uninstall", style = MaterialTheme.typography.labelLarge, maxLines = 2)
                }
            }
        }
    }
}

private fun isEmbeddedDesktopActive(ctx: Context): Boolean {
    return DesktopLauncher.isSessionActive() ||
        DistroRepository.supportedDistros.any {
            StateManager.isGuiRunning(ctx, it.id)
        }
}

private fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes <= 0L) return "Size unknown"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 1.0) return String.format(Locale.US, "%.1f GB", gb)
    val mb = bytes / (1024.0 * 1024.0)
    if (mb >= 1.0) return String.format(Locale.US, "%.1f MB", mb)
    val kb = bytes / 1024.0
    return String.format(Locale.US, "%.1f KB", kb)
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Log.e(TAG, "Error opening URL: $url", e)
    }
}
