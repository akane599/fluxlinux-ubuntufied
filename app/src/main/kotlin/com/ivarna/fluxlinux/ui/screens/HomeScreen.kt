package com.ivarna.fluxlinux.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.desktop.DesktopLauncher
import com.ivarna.fluxlinux.core.desktop.DesktopSession
import com.ivarna.fluxlinux.core.desktop.DesktopSessionQuery
import com.ivarna.fluxlinux.core.files.UbuntuFiles
import com.ivarna.fluxlinux.core.system.GuestStorageLinks
import com.ivarna.fluxlinux.core.system.SharedStorageAccess
import com.ivarna.fluxlinux.core.terminal.BootstrapInstaller
import com.ivarna.fluxlinux.core.terminal.FluxTerminalSessionManager
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.core.utils.UbuntuLaunchPreferences
import com.ivarna.fluxlinux.ui.components.SharedStorageUi
import com.ivarna.fluxlinux.ui.components.UbuntuDashboard
import com.ivarna.fluxlinux.ui.components.UbuntuDashboardActions
import com.ivarna.fluxlinux.ui.components.UbuntuDashboardState
import com.ivarna.fluxlinux.ui.components.UbuntuStatus

/** The one distro this build's Home is about. Other installed containers are left alone. */
const val UBUNTU_DISTRO_ID = "ubuntu"

/** Pure status mapping for the Ubuntu hero card. */
internal fun ubuntuStatus(installed: Boolean, autoStartPending: Boolean, session: DesktopSession?): UbuntuStatus {
    val ubuntu = session?.takeIf { it.distroId == UBUNTU_DISTRO_ID }
    return when {
        !installed -> UbuntuStatus.NOT_INSTALLED
        autoStartPending -> UbuntuStatus.STARTING
        ubuntu?.phase == DesktopSession.Phase.Running -> UbuntuStatus.RUNNING
        ubuntu?.phase == DesktopSession.Phase.Starting -> UbuntuStatus.STARTING
        else -> UbuntuStatus.STOPPED
    }
}

/**
 * Home: the Ubuntu dashboard. Status and start/open/stop, terminal and file shortcuts,
 * shared storage, and recovery. Device state is read here; the leaves in
 * [UbuntuDashboard] are stateless (see the screenshot previews).
 */
@Composable
fun HomeScreen(
    scriptRefreshTrigger: Int = 0,
    autoStartPending: Boolean = false,
    onCancelAutoStart: () -> Unit = {},
    onOpenTerminal: (distroId: String, root: Boolean) -> Unit = { _, _ -> },
    onNavigateToInstall: (Distro) -> Unit,
    onNavigateToSettings: (Distro) -> Unit,
    onNavigateToTroubleshooting: () -> Unit
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val ubuntuDistro = remember {
        DistroRepository.supportedDistros.firstOrNull { it.id == UBUNTU_DISTRO_ID }
    }

    // Live desktop start/stop logs (termux-lib VIEW LOGS parity)
    val desktopUi by DesktopLauncher.uiState.collectAsState()
    var showDesktopLogs by remember { mutableStateOf(false) }
    // Only auto-open on a *new* tick (start/fail). Do NOT open when Home is
    // re-entered while tick is already > 0 from a prior start.
    var lastSeenAutoShowTick by remember { mutableIntStateOf(-1) }
    LaunchedEffect(desktopUi.autoShowLogsTick) {
        val tick = desktopUi.autoShowLogsTick
        if (lastSeenAutoShowTick < 0) {
            lastSeenAutoShowTick = tick
            return@LaunchedEffect
        }
        if (tick > lastSeenAutoShowTick) {
            lastSeenAutoShowTick = tick
            showDesktopLogs = true
        }
    }

    var refreshKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(scriptRefreshTrigger) { refreshKey++ }
    LaunchedEffect(desktopUi.phase, desktopUi.displayReady) { refreshKey++ }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Re-check storage permission / links after returning from Settings.
                refreshKey++
                runCatching { UbuntuFiles.notifyRootsChanged(context) }
                // Drop a "desktop running" state whose X server died with the app.
                Thread {
                    DesktopSessionQuery.reconcileStale(context.applicationContext)
                    mainHandler.post { refreshKey++ }
                }.start()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val stateRefresh by StateManager.refreshTrigger.collectAsState()
    val session = remember(desktopUi, stateRefresh, refreshKey) {
        DesktopSessionQuery.current(context, desktopUi)
    }
    val installed = remember(stateRefresh, refreshKey) {
        TerminalLauncher.isProotInstalled(context, UBUNTU_DISTRO_ID)
    }
    val lastStartFailed = remember(stateRefresh, refreshKey, autoStartPending) {
        UbuntuLaunchPreferences.lastAutoStartFailed(context)
    }
    var creatingLinks by remember { mutableStateOf(false) }
    val storage = remember(refreshKey, creatingLinks, installed) {
        SharedStorageUi(
            supported = SharedStorageAccess.isSupported(context),
            granted = SharedStorageAccess.granted(context),
            linksPresent = installed && GuestStorageLinks.linksPresent(context, UBUNTU_DISTRO_ID),
            linkNames = GuestStorageLinks.LINKS.map { it.name },
            creatingLinks = creatingLinks
        )
    }
    // Host setup or an upgrade from an older build can leave Ubuntu without its ~/storage
    // links, and only session starts create them. ensure() is idempotent, never follows
    // links and keeps existing entries, so Home repeats it quietly off the main thread.
    fun ensureLinksQuietly() {
        if (!TerminalLauncher.isProotInstalled(context, UBUNTU_DISTRO_ID)) return
        val app = context.applicationContext
        Thread {
            runCatching { GuestStorageLinks.ensure(app, UBUNTU_DISTRO_ID) }
            runCatching { UbuntuFiles.notifyRootsChanged(app) }
            mainHandler.post { refreshKey++ }
        }.start()
    }
    LaunchedEffect(installed) { if (installed) ensureLinksQuietly() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshKey++
        ensureLinksQuietly()
    }

    var showRepairConfirm by remember { mutableStateOf(false) }
    var repairing by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    fun startDesktop() {
        try {
            DesktopLauncher.start(context, UBUNTU_DISTRO_ID) { ok ->
                if (ok) UbuntuLaunchPreferences.clearAutoStartAttempt(context)
                refreshKey++
                if (!ok) showDesktopLogs = true
            }
        } catch (e: Exception) {
            toast("Launch failed: ${e.message}")
        }
    }

    fun stopDesktop(onDone: (() -> Unit)? = null) {
        UbuntuLaunchPreferences.clearAutoStartAttempt(context)
        val id = session?.distroId ?: desktopUi.distroId ?: UBUNTU_DISTRO_ID
        try {
            DesktopLauncher.stop(context, id) {
                refreshKey++
                onDone?.invoke()
            }
        } catch (e: Exception) {
            toast("Stop failed: ${e.message}")
            onDone?.invoke()
        }
    }

    fun openFiles(rootId: String) {
        val opened = runCatching { UbuntuFiles.open(context, rootId) }.getOrDefault(false)
        if (!opened) toast(context.getString(R.string.home_files_unavailable))
    }

    fun createLinks() {
        if (creatingLinks) return
        creatingLinks = true
        val app = context.applicationContext
        Thread {
            val result = runCatching { GuestStorageLinks.ensure(app, UBUNTU_DISTRO_ID) }
            runCatching { UbuntuFiles.notifyRootsChanged(app) }
            mainHandler.post {
                creatingLinks = false
                refreshKey++
                val report = result.getOrNull()
                val msg = when {
                    result.isFailure -> app.getString(
                        R.string.home_storage_links_failed,
                        result.exceptionOrNull()?.message ?: "?"
                    )
                    report == null -> app.getString(R.string.home_storage_links_no_rootfs)
                    report.skipped.isEmpty() -> app.getString(
                        R.string.home_storage_links_report, report.created, report.existing
                    )
                    else -> app.getString(
                        R.string.home_storage_links_report, report.created, report.existing
                    ) + "\n" + app.getString(
                        R.string.home_storage_links_skipped, report.skipped.joinToString(", ")
                    )
                }
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    fun repairHost() {
        if (repairing) return
        repairing = true
        toast(context.getString(R.string.home_repair_running))
        val app = context.applicationContext
        FluxTerminalSessionManager.closeAll(app)
        val extract = {
            Thread {
                // Containers under usr/var/lib/proot-distro are moved aside and restored.
                val ok = runCatching { BootstrapInstaller.ensureExtracted(app, force = true) }
                    .getOrDefault(false)
                mainHandler.post {
                    repairing = false
                    refreshKey++
                    Toast.makeText(
                        app,
                        app.getString(if (ok) R.string.home_repair_done else R.string.home_repair_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.start()
        }
        if (session != null || DesktopLauncher.isSessionActive()) stopDesktop { extract() } else extract()
    }

    val state = UbuntuDashboardState(
        status = ubuntuStatus(installed, autoStartPending, session),
        autoStartPending = autoStartPending,
        lastStartFailed = lastStartFailed,
        otherDesktopRunning = session?.takeIf { it.distroId != UBUNTU_DISTRO_ID }?.distroName,
        storage = storage
    )
    val actions = UbuntuDashboardActions(
        onStartDesktop = { startDesktop() },
        onOpenDesktop = { DesktopLauncher.reopenDisplay(context) },
        onStopDesktop = { stopDesktop() },
        onCancelAutoStart = onCancelAutoStart,
        onSetUpUbuntu = { ubuntuDistro?.let(onNavigateToInstall) },
        onTerminal = { onOpenTerminal(UBUNTU_DISTRO_ID, false) },
        onRootTerminal = { onOpenTerminal(UBUNTU_DISTRO_ID, true) },
        onFiles = { openFiles(UbuntuFiles.ROOT_HOME) },
        onFilesystem = { openFiles(UbuntuFiles.ROOT_SYSTEM) },
        onAllowStorage = {
            val perms = SharedStorageAccess.legacyPermissions()
            if (perms.isNotEmpty()) permissionLauncher.launch(perms)
            else SharedStorageAccess.openSettings(context)
        },
        onCreateLinks = { createLinks() },
        onViewLog = { showDesktopLogs = true },
        onStopEverything = {
            FluxTerminalSessionManager.closeAll(context)
            stopDesktop()
            toast(context.getString(R.string.home_recovery_stop_all_done))
        },
        onRepairHost = { showRepairConfirm = true },
        onRerunSetup = { ubuntuDistro?.let(onNavigateToInstall) },
        onUbuntuSettings = { ubuntuDistro?.let(onNavigateToSettings) },
        onTroubleshooting = onNavigateToTroubleshooting
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        UbuntuDashboard(state = state, actions = actions)
    }

    if (showRepairConfirm) {
        AlertDialog(
            onDismissRequest = { showRepairConfirm = false },
            title = { Text(stringResource(R.string.home_repair_dialog_title)) },
            text = { Text(stringResource(R.string.home_repair_dialog_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showRepairConfirm = false
                    repairHost()
                }) { Text(stringResource(R.string.home_repair_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showRepairConfirm = false }) {
                    Text(stringResource(R.string.home_repair_dialog_cancel))
                }
            }
        )
    }

    // ─── Graphical Desktop live logs (termux-lib VIEW LOGS) ─────────────────
    if (showDesktopLogs) {
        DesktopLogsDialog(
            logText = if (desktopUi.logText.isNotBlank()) {
                desktopUi.logText
            } else {
                DesktopLauncher.readLog(context)
            },
            phase = desktopUi.phase,
            onDismiss = { showDesktopLogs = false },
            onOpenX11 = {
                DesktopLauncher.reopenDisplay(context)
            },
            onCopy = {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = if (desktopUi.logText.isNotBlank()) {
                    desktopUi.logText
                } else {
                    DesktopLauncher.readLog(context)
                }
                cm.setPrimaryClip(ClipData.newPlainText("desktop_log", text))
                Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

// ─── Desktop start/stop log dialog ─────────────────────────────────────────────
@Composable
private fun DesktopLogsDialog(
    logText: String,
    phase: DesktopLauncher.Phase,
    onDismiss: () -> Unit,
    onOpenX11: () -> Unit,
    onCopy: () -> Unit
) {
    val scroll = rememberScrollState()
    LaunchedEffect(logText) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    // Dark primary is near-black — prefer cream secondary + light surfaces for contrast.
    val titleColor = MaterialTheme.colorScheme.onBackground
    val accent = MaterialTheme.colorScheme.secondary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val logFg = Color(0xFFE8EAED)
    val logBg = Color(0xFF101214)
    val phaseLabel = when (phase) {
        DesktopLauncher.Phase.Starting -> "Starting…"
        DesktopLauncher.Phase.Running -> "Running"
        DesktopLauncher.Phase.Idle -> "Idle"
    }
    val phaseIcon = when (phase) {
        DesktopLauncher.Phase.Starting -> Icons.Filled.Sync
        DesktopLauncher.Phase.Running -> Icons.Filled.PlayArrow
        DesktopLauncher.Phase.Idle -> Icons.Filled.Schedule
    }
    val phaseTint = when (phase) {
        DesktopLauncher.Phase.Starting -> accent
        DesktopLauncher.Phase.Running -> Color(0xFF69F0AE)
        DesktopLauncher.Phase.Idle -> muted
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.75f),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DesktopWindows,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                "Graphical Desktop Log",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = titleColor
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = phaseIcon,
                                    contentDescription = null,
                                    tint = phaseTint,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    phaseLabel,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = phaseTint
                                )
                            }
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = titleColor
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(logBg)
                        .border(
                            BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = logText.ifBlank { "Waiting for desktop output…" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = logFg,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onCopy,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = accent
                        ),
                        border = BorderStroke(1.5.dp, accent.copy(alpha = 0.75f))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Copy", fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = onOpenX11,
                        enabled = phase != DesktopLauncher.Phase.Idle,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00E5FF),
                            contentColor = Color.Black,
                            disabledContainerColor = Color(0xFF00E5FF).copy(alpha = 0.28f),
                            disabledContentColor = Color.Black.copy(alpha = 0.45f)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Open X11", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
