package com.ivarna.fluxlinux.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.CheckCircle
import androidx.compose.material.icons.sharp.Delete
import androidx.compose.material.icons.sharp.ExpandLess
import androidx.compose.material.icons.sharp.ExpandMore
import androidx.compose.material.icons.sharp.Refresh
import androidx.compose.material.icons.sharp.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.data.DistroComponent
import com.ivarna.fluxlinux.core.gpu.DriverRelease
import com.ivarna.fluxlinux.core.gpu.GpuDriverInstaller
import com.ivarna.fluxlinux.core.gpu.PanvkInstaller
import com.ivarna.fluxlinux.core.terminal.GpuAccelDetector
import com.ivarna.fluxlinux.core.utils.InstallationQueueManager
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.ui.components.aka.AkaRow
import com.ivarna.fluxlinux.ui.components.aka.MarkState
import com.ivarna.fluxlinux.ui.components.aka.SectionLabel
import com.ivarna.fluxlinux.ui.theme.AkaMotion
import com.ivarna.fluxlinux.ui.theme.CodeStyle
import com.ivarna.fluxlinux.ui.theme.FluxSpacing

/**
 * Manage one installed distro: a header with its state, its components as rows (expand for
 * the package list), and the destructive actions in the error ink at the end.
 */
@Composable
fun DistroSettingsScreen(
    distro: Distro,
    onBack: () -> Unit,
    onInstallComponent: (DistroComponent, Map<String, String>) -> Unit,
    onUninstallComponent: (DistroComponent) -> Unit = {},
    onUninstallDistro: () -> Unit,
    onReinstallDistro: () -> Unit,
) {
    val context = LocalContext.current
    val installState by InstallationQueueManager.installState.collectAsState()
    var showUninstallDialog by remember { mutableStateOf(false) }
    var pendingUninstallComponent by remember { mutableStateOf<DistroComponent?>(null) }

    // Config Dialog States
    var showThemeDialog by remember { mutableStateOf(false) }
    var showGpuDialog by remember { mutableStateOf(false) }
    var activeComponent by remember { mutableStateOf<DistroComponent?>(null) }

    // Selections
    var selectedTheme by remember { mutableStateOf("dark") }
    var selectedGpu by remember { mutableStateOf("auto") }

    // Auto-clear stale "Busy..." state when screen opens with no active install
    LaunchedEffect(Unit) {
        if (!InstallationQueueManager.hasPending() && InstallationQueueManager.currentTask == null) {
            InstallationQueueManager.clear()
        }
    }

    SettingsPageScaffold(title = "Manage ${distro.name}", onBack = onBack, scroll = false) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = FluxSpacing.xxl),
        ) {
            item { DistroHeader(distro) }

            item { SectionLabel("Features & components") }

            items(distro.components) { component ->
                // Include both component.id and distro.id in the remember key so the
                // cached isInstalled value resets when the user navigates to a different
                // distro. Without distro.id, switching from termux to debian would re-use
                // termux's cached value for the same component (e.g. kde_plasma) and
                // make the debian screen falsely show the component as installed.
                val refreshKey by StateManager.refreshTrigger.collectAsState()
                val isInstalled = remember(component.id, distro.id, refreshKey) {
                    StateManager.isComponentInstalled(context, distro.id, component.id)
                }
                val details = componentDetailsMap[component.id]

                ComponentManagementGlassCard(
                    component = component,
                    isInstalled = isInstalled,
                    isGlobalInstalling = installState.isInstalling,
                    details = details,
                    onAction = {
                        when {
                            component.id == "customization" -> {
                                // XFCE4 customization — show theme + GPU dialog
                                activeComponent = component
                                showThemeDialog = true
                            }
                            component.id == "hw_accel" -> {
                                // Hardware acceleration — show GPU dialog
                                activeComponent = component
                                showGpuDialog = true
                            }
                            component.id == "kde_customization" -> {
                                // KDE customization — show theme dialog (kde script handles FLUX_THEME)
                                activeComponent = component
                                showThemeDialog = true
                            }
                            else -> {
                                // Generic install (kde_plasma, app_dev, web_dev, etc.)
                                onInstallComponent(component, emptyMap())
                            }
                        }
                    },
                    onUninstall = {
                        // Stage the uninstall — confirmation dialog follows
                        pendingUninstallComponent = component
                    }
                )
            }

            // Danger zone: printed in the error ink, each behind a confirmation.
            item {
                SectionLabel("Danger zone", color = MaterialTheme.colorScheme.error)
                AkaRow(
                    title = "Re-run installation script",
                    icon = Icons.Sharp.Refresh,
                    onClick = onReinstallDistro,
                )
                AkaRow(
                    title = "Uninstall distribution",
                    icon = Icons.Sharp.Delete,
                    iconTint = MaterialTheme.colorScheme.error,
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { showUninstallDialog = true },
                )
            }
        }
    }

    // --- DIALOGS ---

    // Component Uninstall Dialog (per-component, not whole-distro)
    pendingUninstallComponent?.let { comp ->
        AlertDialog(
            onDismissRequest = { pendingUninstallComponent = null },
            icon = { Icon(Icons.Sharp.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Uninstall ${comp.name}?") },
            text = {
                Text(
                    "This will remove ${comp.name} (${comp.sizeEstimate}) and all its data. " +
                        "You can re-install it later from this screen."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val toUninstall = pendingUninstallComponent
                    pendingUninstallComponent = null
                    if (toUninstall != null) onUninstallComponent(toUninstall)
                }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstallComponent = null }) { Text("Cancel") }
            }
        )
    }

    // Uninstall Dialog
    if (showUninstallDialog) {
        AlertDialog(
            onDismissRequest = { showUninstallDialog = false },
            icon = { Icon(Icons.Sharp.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Uninstall ${distro.name}?") },
            text = { Text("This will remove all data and files for ${distro.name}. This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    // Embedded uninstall only (plan §4.5): chroot → SSOT
                    // uninstall script via Root Shell; proot → proot-distro remove.
                    // No Termux intent, no clipboard.
                    onUninstallDistro()
                    showUninstallDialog = false
                }) { Text("Uninstall", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Theme Configuration Dialog
    val themeComponent = activeComponent
    if (showThemeDialog && themeComponent != null) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("Customize desktop") },
            text = {
                Column(Modifier.selectableGroup()) {
                    SettingsThemeOption(name = "Dark mode (default)", desc = "Sleek and professional.", id = "dark", selected = selectedTheme == "dark", onSelect = { selectedTheme = "dark" })
                    SettingsThemeOption(name = "Light mode", desc = "Clean and bright.", id = "light", selected = selectedTheme == "light", onSelect = { selectedTheme = "light" })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showThemeDialog = false
                    onInstallComponent(themeComponent, mapOf("FLUX_THEME" to selectedTheme))
                }) { Text("Apply theme") }
            },
            dismissButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("Cancel") }
            }
        )
    }

    // GPU Configuration Dialog
    val gpuComponent = activeComponent
    if (showGpuDialog && gpuComponent != null) {
        val adreno = remember { GpuAccelDetector.detect().mode == GpuAccelDetector.MODE_TURNIP }
        val waiting by DriverRelease.waiting.collectAsState()
        val driverPending = GpuDriverInstaller.pendingDistro(context) != null
        val elig = remember { PanvkInstaller.current(GpuAccelDetector.detect().signals) }
        val panvk = elig is PanvkInstaller.Elig.Ok && PanvkInstaller.glibc(distro.id)
        AlertDialog(
            onDismissRequest = { showGpuDialog = false },
            title = { Text("Hardware acceleration") },
            text = {
                Column(
                    Modifier
                        .selectableGroup()
                        .verticalScroll(rememberScrollState())
                ) {
                    SettingsThemeOption(name = "Auto detect (recommended)", desc = "Detects Snapdragon (Turnip) or uses VirGL.", id = "auto", selected = selectedGpu == "auto", onSelect = { selectedGpu = "auto" })
                    SettingsThemeOption(name = "VirGL (universal)", desc = "Compatible with most devices.", id = "virgl", selected = selectedGpu == "virgl", onSelect = { selectedGpu = "virgl" })
                    SettingsThemeOption(name = "Turnip/Zink (Snapdragon)", desc = "High performance for Adreno.", id = "turnip", selected = selectedGpu == "turnip", onSelect = { selectedGpu = "turnip" })
                    SettingsThemeOption(name = "Force re-detect", desc = "Ask interactively during install.", id = "ask", selected = selectedGpu == "ask", onSelect = { selectedGpu = "ask" })

                    val driverLine: String? = if (adreno || panvk) {
                        val name = if (adreno) "Turnip" else "PanVK"
                        val inst = if (adreno) GpuDriverInstaller.installedVersion(context, distro.id)
                            else PanvkInstaller.installedVersion(context, distro.id)
                        val latest = if (adreno) GpuDriverInstaller.latestVersion(context)
                            else PanvkInstaller.latestVersion(context)
                        val upd = if (adreno) GpuDriverInstaller.updateAvailable(context, distro.id)
                            else PanvkInstaller.updateAvailable(context, distro.id)
                        "GPU driver ($name): installed ${inst ?: "none"} · latest ${latest ?: "unknown"}" +
                            when {
                                waiting -> "\nWaiting for internet…"
                                driverPending -> "\nNo internet — driver not installed. Software rendering until you retry."
                                upd && inst != null -> "\nUpdate available."
                                else -> ""
                            }
                    } else if (elig is PanvkInstaller.Elig.Skip) {
                        "GPU driver (PanVK): ${elig.msg}"
                    } else if (elig is PanvkInstaller.Elig.Ok) {
                        "GPU driver (PanVK): needs a glibc distro (${distro.id} is musl) — software rendering stays"
                    } else {
                        null
                    }
                    if (driverLine != null) {
                        Spacer(Modifier.height(FluxSpacing.md))
                        Text(
                            driverLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showGpuDialog = false
                    val det = GpuAccelDetector.detect()
                    GpuAccelDetector.persist(context, det)
                    val mode = GpuAccelDetector.resolveFluxGpu(selectedGpu)
                    onInstallComponent(
                        gpuComponent,
                        mapOf(
                            "FLUX_GPU" to mode,
                            "FLUX_GPU_VENDOR" to det.vendorHint
                        )
                    )
                }) { Text(if ((adreno || panvk) && driverPending) "Retry" else "Apply configuration") }
            },
            dismissButton = {
                TextButton(onClick = { showGpuDialog = false }) { Text("Cancel") }
            }
        )
    }
}

/** The distro's printed header: its logo on a tonal tile, its name, its install state. */
@Composable
private fun DistroHeader(distro: Distro) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            val iconRes = distro.iconRes
            if (iconRes != null) {
                Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(36.dp),
                )
            } else {
                Icon(
                    Icons.Sharp.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Spacer(Modifier.width(FluxSpacing.lg))
        Column(Modifier.weight(1f)) {
            Text(
                distro.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(FluxSpacing.xs))
            SettingsPageStatus(text = "Installed & ready", state = MarkState.IDLE)
        }
    }
}

/** One choice in the theme / GPU dialogs: the whole row selects (Material radio). */
@Composable
fun SettingsThemeOption(name: String, desc: String, id: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = FluxSpacing.touch)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = FluxSpacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
        )
        Spacer(modifier = Modifier.width(FluxSpacing.md))
        Column {
            Text(name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Component ids whose scripts have no uninstall branch: they ignore the
 * `uninstall` argument and re-run the install (the customization scripts also
 * rewrite ~/.zshrc), after which the app would record them as uninstalled.
 * xfce4_desktop is the base desktop install (setup_*_family.sh). Every
 * customization script (XFCE, KDE, Termux, Debian, Alpine) is install-only.
 * kde_plasma keeps its button: setup_kde_*.sh handle `uninstall`.
 */
private val INSTALL_ONLY_COMPONENT_IDS = setOf("xfce4_desktop", "customization", "kde_customization")

/** True when the Distro Settings card may offer Uninstall for [component]. */
internal fun componentSupportsUninstall(component: DistroComponent): Boolean =
    !component.isMandatory &&
        !component.comingSoon &&
        component.id !in INSTALL_ONLY_COMPONENT_IDS

/**
 * A component as a full-width row on the stock (the name is historical): icon, name,
 * description, size and state, then its actions; tapping the row expands the package list.
 */
@Composable
fun ComponentManagementGlassCard(
    component: DistroComponent,
    isInstalled: Boolean,
    isGlobalInstalling: Boolean = false,
    details: ComponentDetail?,
    onAction: () -> Unit,
    onUninstall: (() -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val dim = component.comingSoon

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !component.comingSoon) { expanded = !expanded }
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (details != null) {
                Icon(
                    imageVector = details.icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = if (dim) scheme.onSurfaceVariant.copy(alpha = 0.38f) else scheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(FluxSpacing.lg))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = component.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (dim) scheme.onSurface.copy(alpha = 0.5f) else scheme.onSurface
                )
                Text(
                    text = component.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (dim) scheme.onSurfaceVariant.copy(alpha = 0.5f) else scheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(FluxSpacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (component.comingSoon) {
                        Text("Coming soon", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                    } else {
                        Text(
                            text = "Size: ${component.sizeEstimate}",
                            style = CodeStyle,
                            color = scheme.onSurfaceVariant
                        )
                        if (isInstalled) {
                            Spacer(modifier = Modifier.width(FluxSpacing.md))
                            SettingsPageStatus(text = "Installed", state = MarkState.IDLE)
                        }
                    }
                }

                // Actions: Re-run/Install, optional Uninstall, and the expand toggle.
                if (!component.comingSoon) {
                    Spacer(modifier = Modifier.height(FluxSpacing.sm))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm),
                    ) {
                        val actionLabel = when {
                            isGlobalInstalling -> "Busy…"
                            isInstalled -> "Re-run"
                            else -> "Install"
                        }
                        if (isInstalled || isGlobalInstalling) {
                            SettingsPageSecondaryButton(
                                text = actionLabel,
                                onClick = onAction,
                                enabled = !isGlobalInstalling,
                            )
                        } else {
                            Button(
                                onClick = onAction,
                                enabled = !isGlobalInstalling,
                                shape = MaterialTheme.shapes.small,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = scheme.primary,
                                    contentColor = scheme.onPrimary,
                                ),
                                modifier = Modifier.heightIn(min = FluxSpacing.touch),
                            ) {
                                Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        // Uninstall — only for installed components whose script has a
                        // real uninstall branch (see componentSupportsUninstall).
                        if (isInstalled && componentSupportsUninstall(component) && onUninstall != null) {
                            TextButton(
                                onClick = onUninstall,
                                enabled = !isGlobalInstalling,
                                shape = MaterialTheme.shapes.small,
                                colors = ButtonDefaults.textButtonColors(contentColor = scheme.error),
                                modifier = Modifier.heightIn(min = FluxSpacing.touch),
                            ) {
                                Icon(
                                    imageVector = Icons.Sharp.Delete,
                                    contentDescription = "Uninstall ${component.name}",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(FluxSpacing.xs))
                                Text("Uninstall", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        if (details != null) {
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { expanded = !expanded }) {
                                Icon(
                                    imageVector = if (expanded) Icons.Sharp.ExpandLess else Icons.Sharp.ExpandMore,
                                    contentDescription = if (expanded) "Collapse" else "Expand",
                                    tint = scheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Included packages, set as a readout.
                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(AkaMotion.standard()) + fadeIn(AkaMotion.standard()),
                    exit = shrinkVertically(AkaMotion.standard(AkaMotion.QUICK)) + fadeOut(AkaMotion.standard(AkaMotion.QUICK))
                ) {
                    if (details != null) {
                        Column(
                            modifier = Modifier
                                .padding(top = FluxSpacing.sm)
                                .fillMaxWidth()
                                .background(scheme.surfaceContainerLow, MaterialTheme.shapes.medium)
                                .padding(FluxSpacing.md)
                        ) {
                            Text(
                                text = "Included packages",
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(FluxSpacing.xs))
                            details.packages.forEach { (pkg, size) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = FluxSpacing.xs),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = pkg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurface,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Spacer(Modifier.width(FluxSpacing.md))
                                    Text(
                                        text = size,
                                        style = CodeStyle,
                                        color = scheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
