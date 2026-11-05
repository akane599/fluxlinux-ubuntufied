package com.ivarna.fluxlinux.ui.screens

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.OpenInNew
import androidx.compose.material.icons.sharp.Build
import androidx.compose.material.icons.sharp.Close
import androidx.compose.material.icons.sharp.ContentCopy
import androidx.compose.material.icons.sharp.DesktopWindows
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.data.DistroRepository
import com.ivarna.fluxlinux.core.desktop.DesktopDetection
import com.ivarna.fluxlinux.core.desktop.DesktopLauncher
import com.ivarna.fluxlinux.core.desktop.DesktopSession
import com.ivarna.fluxlinux.core.desktop.DesktopSessionQuery
import com.ivarna.fluxlinux.core.desktop.UbuntuDesktop
import com.ivarna.fluxlinux.core.files.UbuntuFiles
import com.ivarna.fluxlinux.core.system.GuestStorageLinks
import com.ivarna.fluxlinux.core.system.SharedStorageAccess
import com.ivarna.fluxlinux.core.install.InstallController
import com.ivarna.fluxlinux.core.install.InstallKind
import com.ivarna.fluxlinux.core.terminal.BootstrapInstaller
import com.ivarna.fluxlinux.core.terminal.FluxTerminalSessionManager
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher
import com.ivarna.fluxlinux.core.utils.RuntimeSwitchDecision
import com.ivarna.fluxlinux.core.utils.StateManager
import com.ivarna.fluxlinux.core.utils.UbuntuInstallCheck
import com.ivarna.fluxlinux.core.utils.UbuntuInstallProbe
import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
import com.ivarna.fluxlinux.core.utils.UbuntuRuntimeMode
import com.ivarna.fluxlinux.core.utils.UbuntuRuntimeSwitch
import com.ivarna.fluxlinux.ui.components.DesktopAddOnUi
import com.ivarna.fluxlinux.ui.components.SharedStorageUi
import com.ivarna.fluxlinux.ui.components.UbuntuDashboard
import com.ivarna.fluxlinux.ui.components.UbuntuDashboardActions
import com.ivarna.fluxlinux.ui.components.UbuntuDashboardState
import com.ivarna.fluxlinux.ui.components.UbuntuStatus
import com.ivarna.fluxlinux.ui.terminal.TerminalClipboard
import com.ivarna.fluxlinux.ui.theme.CodeStyle
import com.ivarna.fluxlinux.ui.theme.FluxSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pure status mapping for the Ubuntu hero card. [activeId] is the active Ubuntu install
 * ([UbuntuRuntime.activeDistroId]): a desktop of that install counts as Ubuntu's, and wins over
 * a still-running install probe (a running chroot desktop shows as running right away).
 */
internal fun ubuntuStatus(
    install: UbuntuInstallCheck,
    session: DesktopSession?,
    activeId: String,
): UbuntuStatus {
    val ubuntu = session?.takeIf { it.distroId == activeId }
    return when {
        ubuntu?.phase == DesktopSession.Phase.Running -> UbuntuStatus.RUNNING
        ubuntu?.phase == DesktopSession.Phase.Starting -> UbuntuStatus.STARTING
        install == UbuntuInstallCheck.CHECKING -> UbuntuStatus.CHECKING
        install == UbuntuInstallCheck.NO_ROOT -> UbuntuStatus.NEEDS_ROOT
        install == UbuntuInstallCheck.NOT_INSTALLED -> UbuntuStatus.NOT_INSTALLED
        else -> UbuntuStatus.STOPPED
    }
}

/** Distro id of a running desktop that is not the active Ubuntu install, or null. */
internal fun otherDesktopId(session: DesktopSession?, activeId: String): String? =
    session?.distroId?.takeIf { it != activeId }

/** Why Home would ask su again: first look, app resume, a state refresh, or "Check root again". */
internal enum class RootProbeReason { INITIAL, RESUME, REFRESH, RETRY }

/**
 * Whether Home may run the root / chroot probe for [reason], given the last root answer
 * ([lastRoot], null = never asked or cleared). Root managers prompt in their own activity, so a
 * denial resumes Home: re-probing on resume or refresh after a denial (or timeout) would loop the
 * prompt. After a grant the su invocation is cached, so re-probing only re-reads install state.
 */
internal fun homeRootProbeAllowed(reason: RootProbeReason, lastRoot: Boolean?): Boolean = when (reason) {
    RootProbeReason.INITIAL -> lastRoot == null
    RootProbeReason.RESUME, RootProbeReason.REFRESH -> lastRoot == true
    RootProbeReason.RETRY -> true
}

/**
 * Whether Home offers the quick runtime switch: the other mode's install is known to exist
 * and no desktop is running or starting.
 */
internal fun canSwitchRuntime(otherInstalled: Boolean, status: UbuntuStatus, desktopActive: Boolean): Boolean =
    otherInstalled && !desktopActive &&
        status != UbuntuStatus.RUNNING && status != UbuntuStatus.STARTING

/**
 * The Desktop section's state for the active install [activeId]: a running or failed desktop
 * add-on run of that install wins; otherwise [desktopInstalled] decides, and an unfinished
 * desktop install ([desktopIncomplete], DesktopDetection.INCOMPLETE) shows as failed.
 * Terminal-only runs and runs for the other install never show here.
 */
internal fun desktopAddOnUi(
    install: InstallController.State,
    activeId: String,
    desktopInstalled: Boolean,
    desktopIncomplete: Boolean = false,
): DesktopAddOnUi {
    val addOn = install.takeIf { it.kind == InstallKind.DESKTOP_ADD_ON && it.distroId == activeId }
    return when {
        addOn?.isRunning == true -> DesktopAddOnUi.Installing(addOn.percent, addOn.phaseLabel, addOn.detail)
        desktopInstalled -> DesktopAddOnUi.Installed
        addOn?.status == InstallController.Status.Failed -> DesktopAddOnUi.Failed(addOn.errorMessage)
        desktopIncomplete -> DesktopAddOnUi.Failed(null)
        else -> DesktopAddOnUi.NotInstalled
    }
}

/**
 * Home: the Ubuntu overview, opened from the terminal drawer. Status, the optional desktop
 * (install, start/open/stop), files, shared storage and recovery. Device state is read here;
 * the leaves in [UbuntuDashboard] are stateless (see the screenshot previews).
 */
@Composable
fun HomeScreen(
    scriptRefreshTrigger: Int = 0,
    onNavigateToInstall: (Distro) -> Unit,
    onNavigateToSettings: (Distro) -> Unit,
    onNavigateToTroubleshooting: () -> Unit
) {
    val context = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    // State-backed: a runtime switch (Settings, the hero chip) recomposes Home on the other install.
    val runtime by remember { UbuntuRuntime.modeFlow(context) }.collectAsState()
    val activeId = runtime.distroId
    val ubuntuDistro = remember(activeId) {
        DistroRepository.supportedDistros.firstOrNull { it.id == activeId }
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
    // Root / chroot install answers from the off-main probe; null = still probing or not probed.
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }
    var chrootInstalled by remember { mutableStateOf<Boolean?>(null) }
    // Re-probes root / the chroot install. Gated by homeRootProbeAllowed: never on every
    // desktop phase change, and after a denial only on "Check root again".
    var probeKey by remember { mutableIntStateOf(0) }
    var probeReason by remember { mutableStateOf(RootProbeReason.INITIAL) }
    fun requestProbe(reason: RootProbeReason) {
        if (!homeRootProbeAllowed(reason, rootAvailable)) return
        probeReason = reason
        probeKey++
    }
    LaunchedEffect(scriptRefreshTrigger) { refreshKey++ }
    LaunchedEffect(desktopUi.phase, desktopUi.displayReady) { refreshKey++ }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Re-check storage permission / links after returning from Settings.
                refreshKey++
                requestProbe(RootProbeReason.RESUME)
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
    // PRoot: a plain file check. Chroot: root + rootfs markers through su, off the main thread
    // (ChrootDetection only answers from its cache there); null = still probing.
    val prootInstalled = remember(stateRefresh, refreshKey) { UbuntuInstallProbe.prootInstalled(context) }
    // In PRoot mode the chroot is only probed once it was installed through the app, so people
    // who never chose chroot get no su prompt from Home.
    val probeChroot = remember(runtime, stateRefresh) {
        runtime == UbuntuRuntimeMode.CHROOT || UbuntuInstallProbe.chrootEverInstalled(context)
    }
    LaunchedEffect(stateRefresh) { requestProbe(RootProbeReason.REFRESH) }
    LaunchedEffect(probeChroot, probeKey) {
        if (!probeChroot) {
            // A removed (or never chosen) chroot must not keep offering the quick switch.
            rootAvailable = null
            chrootInstalled = null
            return@LaunchedEffect
        }
        val app = context.applicationContext
        val forceAsk = probeReason == RootProbeReason.RETRY
        val (root, chroot) = withContext(Dispatchers.IO) {
            if (forceAsk) RootShell.clearSuCache()
            val r = runCatching { RootShell.isRootAvailable() }.getOrDefault(false)
            r to (r && runCatching {
                TerminalLauncher.isDistroInstalledOnFs(app, UbuntuRuntime.CHROOT_ID)
            }.getOrDefault(false))
        }
        rootAvailable = root
        chrootInstalled = if (root) chroot else null
    }
    val install = UbuntuInstallProbe.check(runtime, prootInstalled, rootAvailable, chrootInstalled)
    val installed = install == UbuntuInstallCheck.INSTALLED
    val otherInstalled = when (runtime) {
        UbuntuRuntimeMode.PROOT -> rootAvailable == true && chrootInstalled == true
        UbuntuRuntimeMode.CHROOT -> prootInstalled
    }
    val installer = remember { InstallController.get(context.applicationContext) }
    val installState by installer.state.collectAsState()
    // The optional desktop, verified off the main thread (UbuntuDesktop.detect): a startxfce4
    // file check (PRoot) or, once root was granted, an su probe of the chroot; both heal the
    // stored flag. Starts from the stored state so an installed desktop doesn't flash "not
    // installed". Re-checked when an install run ends (a failed or cancelled one leaves the
    // desktop incomplete).
    var desktopDetection by remember(activeId) {
        mutableStateOf(UbuntuDesktop.storedDetection(context, activeId))
    }
    LaunchedEffect(installed, activeId, runtime, rootAvailable, stateRefresh, installState.status) {
        if (!installed) return@LaunchedEffect
        val app = context.applicationContext
        val mayAskRoot = rootAvailable == true
        desktopDetection = withContext(Dispatchers.IO) {
            runCatching { UbuntuDesktop.detect(app, activeId, mayAskRoot) }.getOrElse { desktopDetection }
        }
    }
    var showDesktopInstallConfirm by remember { mutableStateOf(false) }
    var showDesktopInstallLog by remember { mutableStateOf(false) }
    var creatingLinks by remember { mutableStateOf(false) }
    // The chroot check goes through su, so links are always read off the main thread.
    var linksPresent by remember { mutableStateOf(false) }
    LaunchedEffect(installed, activeId, refreshKey, creatingLinks) {
        if (!installed) {
            linksPresent = false
            return@LaunchedEffect
        }
        val app = context.applicationContext
        linksPresent = withContext(Dispatchers.IO) {
            runCatching { GuestStorageLinks.linksPresent(app, activeId) }.getOrDefault(false)
        }
    }
    val storage = remember(refreshKey, creatingLinks, linksPresent) {
        SharedStorageUi(
            supported = SharedStorageAccess.isSupported(context),
            granted = SharedStorageAccess.granted(context),
            linksPresent = linksPresent,
            linkNames = GuestStorageLinks.LINKS.map { it.name },
            creatingLinks = creatingLinks
        )
    }
    // Host setup or an upgrade from an older build can leave Ubuntu without its ~/storage
    // links, and only session starts create them. ensure() is idempotent, never follows
    // links and keeps existing entries, so Home repeats it quietly off the main thread.
    fun ensureLinksQuietly() {
        if (!installed) return
        val app = context.applicationContext
        val id = activeId
        Thread {
            runCatching { GuestStorageLinks.ensure(app, id) }
            runCatching { UbuntuFiles.notifyRootsChanged(app) }
            mainHandler.post { refreshKey++ }
        }.start()
    }
    LaunchedEffect(installed, activeId) { if (installed) ensureLinksQuietly() }

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
            DesktopLauncher.start(context, activeId) { ok ->
                refreshKey++
                if (!ok) showDesktopLogs = true
            }
        } catch (e: Exception) {
            toast("Launch failed: ${e.message}")
        }
    }

    fun stopDesktop(onDone: (() -> Unit)? = null) {
        val id = session?.distroId ?: desktopUi.distroId ?: activeId
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
        val id = activeId
        Thread {
            val result = runCatching { GuestStorageLinks.ensure(app, id) }
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

    fun repairAllowed(): Boolean = InstallController.hostRepairAllowed(
        InstallController.get(context.applicationContext).state.value,
        extracting = BootstrapInstaller.busy.value,
    )

    fun repairHost() {
        // Process-wide guards (the local flag resets on recreation): no repair while
        // an install (it outlives its screen) or another extraction uses usr/.
        if (repairing) return
        if (!repairAllowed()) {
            toast(context.getString(R.string.home_repair_blocked_install))
            return
        }
        repairing = true
        toast(context.getString(R.string.home_repair_running))
        val app = context.applicationContext
        FluxTerminalSessionManager.closeAll(app)
        val extract = {
            // Same single-thread executor as terminal/desktop host preparation, and the
            // extract lock inside ensureExtracted, so repair never races them (M1).
            TerminalLauncher.executor.execute {
                // Re-check right before deleting usr/: an install may have started while
                // the desktop was stopping. (An install started after this waits on the
                // extract lock in its host-preparation step.)
                if (!repairAllowed()) {
                    mainHandler.post {
                        repairing = false
                        Toast.makeText(app, app.getString(R.string.home_repair_blocked_install), Toast.LENGTH_LONG).show()
                    }
                    return@execute
                }
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
            }
        }
        if (session != null || DesktopLauncher.isSessionActive()) stopDesktop { extract() } else extract()
    }

    val desktopActive = session != null || DesktopLauncher.isSessionActive()

    /** Hero chip / "Use PRoot": flip to the other runtime. Never while a desktop runs. */
    fun switchRuntime() {
        val target = if (runtime == UbuntuRuntimeMode.CHROOT) UbuntuRuntimeMode.PROOT else UbuntuRuntimeMode.CHROOT
        when (
            UbuntuRuntimeSwitch.decide(
                current = runtime,
                target = target,
                desktopActive = desktopActive,
                rootAvailable = rootAvailable == true,
            )
        ) {
            RuntimeSwitchDecision.UNCHANGED -> Unit
            RuntimeSwitchDecision.STOP_DESKTOP_FIRST ->
                toast(context.getString(R.string.home_runtime_switch_blocked))
            RuntimeSwitchDecision.NO_ROOT -> toast(context.getString(R.string.home_runtime_no_root))
            RuntimeSwitchDecision.SWITCH -> {
                UbuntuRuntimeSwitch.apply(context, target)
                toast(
                    context.getString(
                        if (target == UbuntuRuntimeMode.CHROOT) R.string.home_runtime_switched_chroot
                        else R.string.home_runtime_switched_proot
                    )
                )
            }
        }
    }

    /** Starts the desktop add-on on the active install (refused while another install runs). */
    fun installDesktop(theme: String) {
        val result = installer.start(activeId, theme, InstallKind.DESKTOP_ADD_ON)
        if (result is InstallController.StartResult.Refused) toast(result.message)
    }

    val status = ubuntuStatus(install, session, activeId)
    val desktopInstalled = desktopDetection.isInstalled || session?.distroId == activeId
    val desktop = desktopAddOnUi(
        installState, activeId, desktopInstalled,
        desktopIncomplete = desktopDetection == DesktopDetection.INCOMPLETE,
    )
    val otherId = otherDesktopId(session, activeId)
    val otherName = when (otherId) {
        null -> null
        UbuntuRuntime.PROOT_ID -> stringResource(R.string.home_other_ubuntu_proot)
        UbuntuRuntime.CHROOT_ID -> stringResource(R.string.home_other_ubuntu_chroot)
        else -> session?.distroName
    }
    val state = UbuntuDashboardState(
        status = status,
        otherDesktopRunning = otherName,
        storage = storage,
        runtime = runtime,
        canSwitchRuntime = canSwitchRuntime(otherInstalled, status, desktopActive),
        desktop = desktop,
    )
    val actions = UbuntuDashboardActions(
        onStartDesktop = { startDesktop() },
        onOpenDesktop = { DesktopLauncher.reopenDisplay(context) },
        onStopDesktop = { stopDesktop() },
        onInstallDesktop = { showDesktopInstallConfirm = true },
        onCancelDesktopInstall = { installer.cancel() },
        onDesktopInstallLog = { showDesktopInstallLog = true },
        onSetUpUbuntu = { ubuntuDistro?.let(onNavigateToInstall) },
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
        onTroubleshooting = onNavigateToTroubleshooting,
        onSwitchRuntime = { switchRuntime() },
        onRetryRoot = {
            // Clears the su cache and asks the root manager again.
            rootAvailable = null
            chrootInstalled = null
            requestProbe(RootProbeReason.RETRY)
        },
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
            icon = { Icon(Icons.Sharp.Build, contentDescription = null) },
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

    if (showDesktopInstallConfirm) {
        DesktopInstallDialog(
            onInstall = { theme ->
                showDesktopInstallConfirm = false
                installDesktop(theme)
            },
            onDismiss = { showDesktopInstallConfirm = false }
        )
    }

    if (showDesktopInstallLog) {
        LogDialog(
            title = stringResource(R.string.home_desktop_install_log_title),
            subtitle = installState.phaseLabel.ifBlank { null },
            logText = installState.logText,
            onCopy = { copyLog(context, installState.logText) },
            onDismiss = { showDesktopInstallLog = false }
        )
    }

    // ─── Desktop start/stop log (termux-lib VIEW LOGS) ──────────────────────
    if (showDesktopLogs) {
        val logText = desktopUi.logText.ifBlank { DesktopLauncher.readLog(context) }
        LogDialog(
            title = stringResource(R.string.home_log_title_desktop),
            subtitle = stringResource(
                when (desktopUi.phase) {
                    DesktopLauncher.Phase.Starting -> R.string.home_log_phase_starting
                    DesktopLauncher.Phase.Running -> R.string.home_log_phase_running
                    DesktopLauncher.Phase.Idle -> R.string.home_log_phase_idle
                }
            ),
            logText = logText,
            onCopy = { copyLog(context, logText) },
            onDismiss = { showDesktopLogs = false },
            openDisplay = if (desktopUi.phase != DesktopLauncher.Phase.Idle) {
                { DesktopLauncher.reopenDisplay(context) }
            } else {
                null
            }
        )
    }
}

/**
 * Copies the newest part of a log (the desktop log can be 512 KiB, more than one Binder call
 * carries); says "Log copied" below Android 13 and always says when the copy failed.
 */
private fun copyLog(context: Context, text: String) {
    TerminalClipboard.copy(context, TerminalClipboard.tailForShare(text), R.string.home_log_copied)
}

/** Confirms the large desktop download and picks its theme (the same two as setup). */
@Composable
private fun DesktopInstallDialog(onInstall: (theme: String) -> Unit, onDismiss: () -> Unit) {
    var theme by rememberSaveable { mutableStateOf("dark") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Sharp.DesktopWindows, contentDescription = null) },
        title = { Text(stringResource(R.string.home_desktop_confirm_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.home_desktop_confirm_body),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.home_desktop_confirm_theme),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = FluxSpacing.lg, bottom = FluxSpacing.xs)
                )
                Column(Modifier.selectableGroup()) {
                    listOf(
                        "dark" to R.string.home_desktop_theme_dark,
                        "light" to R.string.home_desktop_theme_light,
                    ).forEach { (id, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = FluxSpacing.touch)
                                .clip(MaterialTheme.shapes.small)
                                .selectable(
                                    selected = theme == id,
                                    role = Role.RadioButton,
                                    onClick = { theme = id }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = theme == id, onClick = null)
                            Spacer(Modifier.width(FluxSpacing.md))
                            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onInstall(theme) }) {
                Text(stringResource(R.string.home_desktop_confirm_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.home_desktop_confirm_cancel)) }
        }
    )
}

/**
 * Scrolling log viewer (desktop start/stop log, desktop install log): follows new output,
 * copies the whole text, and offers "Open display" while a desktop runs.
 */
@Composable
private fun LogDialog(
    title: String,
    subtitle: String?,
    logText: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    openDisplay: (() -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    LaunchedEffect(logText) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    val colors = MaterialTheme.colorScheme
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.75f),
            shape = MaterialTheme.shapes.extraLarge,
            color = colors.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(FluxSpacing.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = FluxSpacing.sm)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                        if (subtitle != null) {
                            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Sharp.Close,
                            contentDescription = stringResource(R.string.home_log_close),
                            tint = colors.onSurfaceVariant
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .padding(top = FluxSpacing.md)
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(colors.surfaceContainerLowest)
                        .padding(FluxSpacing.md)
                ) {
                    // Real program output: the code face is the content, not decoration.
                    Text(
                        text = logText.ifBlank { stringResource(R.string.home_log_waiting) },
                        style = CodeStyle,
                        color = if (logText.isBlank()) colors.onSurfaceVariant else colors.onSurface,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = FluxSpacing.md),
                    horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
                ) {
                    LogDialogButton(
                        label = stringResource(R.string.home_log_copy),
                        icon = Icons.Sharp.ContentCopy,
                        onClick = onCopy,
                        modifier = Modifier.weight(1f)
                    )
                    if (openDisplay != null) {
                        LogDialogButton(
                            label = stringResource(R.string.home_log_open_display),
                            icon = Icons.AutoMirrored.Sharp.OpenInNew,
                            onClick = openDisplay,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** The log dialog's actions: outlined, in the text ink (no plate in a viewer). */
@Composable
private fun LogDialogButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = modifier.heightIn(min = FluxSpacing.touch)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(LogButtonIcon))
        Spacer(Modifier.width(FluxSpacing.sm))
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val LogButtonIcon = 18.dp
