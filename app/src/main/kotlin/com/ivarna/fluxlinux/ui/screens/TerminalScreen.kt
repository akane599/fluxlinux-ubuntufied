package com.ivarna.fluxlinux.ui.screens

import android.content.Context
import android.graphics.Typeface
import android.system.Os
import android.system.OsConstants
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.Add
import androidx.compose.material.icons.sharp.AdminPanelSettings
import androidx.compose.material.icons.sharp.ArrowDropDown
import androidx.compose.material.icons.sharp.Close
import androidx.compose.material.icons.sharp.Coffee
import androidx.compose.material.icons.sharp.Folder
import androidx.compose.material.icons.sharp.Keyboard
import androidx.compose.material.icons.sharp.Link
import androidx.compose.material.icons.sharp.Security
import androidx.compose.material.icons.sharp.Settings
import androidx.compose.material.icons.sharp.SpaceDashboard
import androidx.compose.material.icons.sharp.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ivarna.fluxlinux.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.terminal.FluxTerminalSessionManager
import com.ivarna.fluxlinux.core.terminal.FluxTerminalSessionManager.SessionOpenResult
import com.ivarna.fluxlinux.core.terminal.TerminalShellCardUi
import com.ivarna.fluxlinux.core.chroot.ChrootDetection
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.core.terminal.TerminalShellCatalog
import com.ivarna.fluxlinux.core.terminal.TerminalWakeLock
import com.ivarna.fluxlinux.core.service.AppTerminalService
import com.ivarna.fluxlinux.core.utils.TerminalPreferences
import com.ivarna.fluxlinux.core.utils.UbuntuInstallProbe
import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
import com.ivarna.fluxlinux.core.utils.UbuntuRuntimeMode
import com.ivarna.fluxlinux.ui.components.FluxSwitch
import com.ivarna.fluxlinux.ui.components.aka.AkaWordmark
import com.ivarna.fluxlinux.ui.components.aka.MarkState
import com.ivarna.fluxlinux.ui.components.aka.PlateKind
import com.ivarna.fluxlinux.ui.components.aka.PlatedButton
import com.ivarna.fluxlinux.ui.components.aka.StatusMark
import com.ivarna.fluxlinux.ui.components.aka.markColor
import com.ivarna.fluxlinux.ui.components.aka.plateInks
import com.ivarna.fluxlinux.ui.components.aka.plateOffset
import com.ivarna.fluxlinux.ui.terminal.TerminalClipboard
import com.ivarna.fluxlinux.ui.terminal.TerminalExtraKeys
import com.ivarna.fluxlinux.ui.terminal.TerminalModifierState
import com.ivarna.fluxlinux.ui.terminal.TerminalSessionChoices
import com.ivarna.fluxlinux.ui.terminal.TerminalSessionDefaults
import com.ivarna.fluxlinux.ui.terminal.hideIme
import com.ivarna.fluxlinux.ui.terminal.keepTerminalGestures
import com.ivarna.fluxlinux.ui.theme.AkaTheme
import com.ivarna.fluxlinux.ui.theme.CodeStyle
import com.ivarna.fluxlinux.ui.theme.FluxSpacing
import com.ivarna.fluxlinux.ui.theme.Riso
import com.ivarna.fluxlinux.ui.theme.TerminalBackground
import com.ivarna.fluxlinux.ui.theme.misregistered
import com.ivarna.fluxlinux.ui.theme.risoGrain
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One terminal session as the drawer's session list draws it. [mark] is its process state as a
 * mark form: [MarkState.RUNNING], [MarkState.IDLE] (exited cleanly) or [MarkState.FAILED].
 */
data class TerminalTabUi(
    val title: String,
    @DrawableRes val iconRes: Int,
    val mark: MarkState = MarkState.RUNNING,
)

/** Drawer sheet width cap, so the scrim stays visible (and tappable) on a phone. */
private val DRAWER_MAX_WIDTH = 300.dp
private val DRAWER_ROW_HEIGHT = 52.dp
private val DRAWER_ICON_SIZE = 24.dp
private val STATUS_MARK_SIZE = 10.dp
private val EMPTY_MARK_SIZE = 64.dp
private val EMPTY_STATE_MAX_WIDTH = 480.dp
private val LINK_ICON_SIZE = 18.dp
/** The wordmark is a logo: it grows with font scale only this far, so it never clips in the sheet. */
private const val WORDMARK_MAX_FONT_SCALE = 1.15f

/** Left-edge swipe zone over the terminal: Termux's edge width, inside Android's 200dp-per-edge exclusion cap. */
private val EDGE_ZONE_WIDTH = 20.dp
private val EDGE_ZONE_HEIGHT = 192.dp
/** Lower-middle of the terminal area (centre at 75% of its height), above the extra keys. */
private val EDGE_ZONE_ALIGNMENT = BiasAlignment(horizontalBias = -1f, verticalBias = 0.5f)
/** A rightward drag this long in the edge zone opens the drawer if the drawer's own drag missed it. */
private val EDGE_OPEN_DISTANCE = 48.dp

/**
 * In-app terminal with multi-session support + full ExtraKeys toolbar (injectKey / ModifierState lock).
 *
 * The app's home screen. No top bar: sessions, new shells, the extra-keys toggle and the way to
 * the Ubuntu overview, Files and Settings live in a Termux-style drawer opened by a swipe
 * towards it on the terminal ([keepTerminalGestures], [DrawerEdgeZone]) or the extra keys' ☰ key.
 *
 * Tapping a web link in the output opens it in the default browser ([TerminalClipboard.linkAt]);
 * other taps raise the keyboard. Paste: the extra keys' Paste key, Ctrl+Shift+V, or the menu
 * behind the selection toolbar's "More…" (also long-press on Paste), which also lists links and
 * copies or shares the whole output.
 *
 * Ubuntu-first: there is no distro selector. Entering the screen with no sessions opens an
 * Ubuntu PRoot user shell once; the drawer's "New Ubuntu session" opens another one. Secondary
 * shells (Ubuntu root, Ubuntu chroot, Termux host) live in its long-press / dropdown menu.
 * Session values come from [TerminalShellCatalog] via [TerminalSessionDefaults].
 */
@Composable
fun TerminalScreen(
    onOpenDashboard: (() -> Unit)? = null,
    onOpenFiles: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val activeIndex by FluxTerminalSessionManager.activeIndex.collectAsState()
    val sessionRevision by FluxTerminalSessionManager.revision.collectAsState()
    val titles = remember(sessionRevision) { FluxTerminalSessionManager.titles() }
    val sessions = remember(sessionRevision) { FluxTerminalSessionManager.sessions() }

    // Modifier state shared between TerminalViewClient (read side) and ExtraKeys (UI).
    val modState = remember { TerminalModifierState() }
    var fontSize by remember {
        mutableIntStateOf(TerminalPreferences.getFontSize(context))
    }
    var showExtraKeys by remember {
        mutableStateOf(TerminalPreferences.isExtraKeysEnabled(context))
    }
    var accelerateKeys by remember {
        mutableStateOf(TerminalPreferences.isKeyRepeatAccelerated(context))
    }
    var terminalViewRef by remember { mutableStateOf<TerminalView?>(null) }
    // Links listed by the clipboard menu's "Open a link…"; null = dialog closed.
    var linkChoices by remember { mutableStateOf<List<String>?>(null) }
    // Empty-state auto-open runs once per entry; a failure leaves a retry button, never a loop.
    var autoOpenTried by rememberSaveable { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var openError by rememberSaveable { mutableStateOf<String?>(null) }
    var rootAvailable by remember { mutableStateOf(false) }
    // Bumped when the off-main root + chroot detection finishes, so remembered choices
    // (the shell menu) recompute: ChrootDetection only answers from its cache on main.
    var detectionRevision by remember { mutableIntStateOf(0) }
    val runtime by remember { UbuntuRuntime.modeFlow(context) }.collectAsState()
    val wakeLockHeld by TerminalWakeLock.held.collectAsState()
    // Avoid SIGWINCH spam on every Compose recomposition — only when the emulator's
    // cols/rows or the attached pid actually change (R2: pinch zoom changes cols/rows
    // without a view-size change, so pixel width/height is NOT a valid guard key).
    val lastWinchKey = remember { intArrayOf(-1, -1, -1) } // cols, rows, pid

    // Reload prefs when returning from Settings (Terminal / X11 detail pages).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                fontSize = TerminalPreferences.getFontSize(context)
                showExtraKeys = TerminalPreferences.isExtraKeysEnabled(context)
                accelerateKeys = TerminalPreferences.isKeyRepeatAccelerated(context)
                terminalViewRef?.setTextSize(fontSize)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Fresh filesystem snapshot each call, so a just-installed Ubuntu is picked up on retry.
     * Chroot entries come from the ChrootDetection cache the off-main probe below warms.
     */
    fun resolveChoices() = TerminalSessionDefaults.resolve(
        TerminalShellCatalog.sections(
            context,
            TerminalShellCatalog.availability(context, rootAvailable)
        ).flatMap { it.cards },
        runtime,
    )

    /** Opens [card]; [onDone] gets null on success or the failure reason (already toasted). */
    fun openShell(card: TerminalShellCardUi, onDone: (String?) -> Unit = {}) {
        val def = card.def
        fun report(result: SessionOpenResult) {
            val reason = when (result) {
                SessionOpenResult.OPENED -> null
                SessionOpenResult.MAX_TABS -> context.getString(
                    R.string.terminal_error_tab_limit,
                    FluxTerminalSessionManager.MAX_TABS
                )
                SessionOpenResult.HOST_PREPARE_FAILED ->
                    context.getString(R.string.terminal_error_host_bootstrap)
                SessionOpenResult.HOST_NOT_READY ->
                    context.getString(R.string.terminal_error_host_not_ready)
                SessionOpenResult.OPEN_FAILED -> context.getString(
                    when (def.method) {
                        "host" -> R.string.terminal_error_host_failed
                        "chroot" -> R.string.terminal_error_chroot_failed
                        else -> R.string.terminal_error_session_failed
                    }
                )
            }
            reason?.let(::toast)
            onDone(reason)
        }
        if (def.method == "host") {
            // R4: gate on host ready — prepare when missing, toast "Host not ready".
            FluxTerminalSessionManager.openHostShellAfterReady(context, def.label, ::report)
            return
        }
        FluxTerminalSessionManager.openSessionAfterHost(
            context,
            type = def.type,
            title = def.label,
            method = def.method,
            distroId = def.distroId,
            onResult = ::report
        )
    }

    /** Default user tab of the active Ubuntu install ("New session", empty-state auto-open and retry). */
    fun openUbuntu(fromEmptyState: Boolean) {
        val card = resolveChoices().defaultUser
        if (card == null || !card.enabled) {
            val reason = context.getString(
                when {
                    runtime != UbuntuRuntimeMode.CHROOT -> R.string.terminal_error_ubuntu_missing
                    !rootAvailable -> R.string.terminal_error_chroot_no_root
                    else -> R.string.terminal_error_ubuntu_chroot_missing
                }
            )
            toast(reason)
            if (fromEmptyState) openError = reason
            return
        }
        if (fromEmptyState) {
            opening = true
            openError = null
        }
        openShell(card) { reason ->
            if (fromEmptyState) {
                opening = false
                openError = reason
            }
        }
    }

    fun autoOpenOnce() {
        if (autoOpenTried) return
        autoOpenTried = true
        if (FluxTerminalSessionManager.sessionCount == 0) openUbuntu(fromEmptyState = true)
    }

    LaunchedEffect(Unit) {
        // PRoot opens right away; chroot needs root and a warm chroot detection first (on
        // main ChrootDetection is cache-only, so a cold start would report it missing).
        val waitForRoot = runtime == UbuntuRuntimeMode.CHROOT
        if (!waitForRoot) autoOpenOnce()
        // The terminal is the first screen: like Home, only ask su once a chroot was ever set
        // up, so PRoot users on a rooted phone get no root prompt on every launch.
        if (!waitForRoot && !UbuntuInstallProbe.chrootEverInstalled(context)) return@LaunchedEffect
        val app = context.applicationContext
        val root = withContext(Dispatchers.IO) {
            val ok = runCatching { RootShell.isRootAvailable() }.getOrDefault(false)
            // Off main this probes through su and fills the cache resolveChoices() reads.
            if (ok) runCatching { ChrootDetection.warmUp(ChrootPaths.UBUNTU_CHROOT_PATH) }
            ok
        }
        rootAvailable = root
        detectionRevision++
        if (waitForRoot) autoOpenOnce()
    }
    // A stale failure must not reappear after tabs were opened (e.g. via "+") and closed again.
    val hasSessions = sessions.isNotEmpty()
    LaunchedEffect(hasSessions) {
        if (hasSessions) openError = null
    }
    // Root granted + Ubuntu chroot installed: warm helper + BusyBox off main so the first
    // chroot open skips su staging/probes. Never prompts non-root users.
    LaunchedEffect(rootAvailable, detectionRevision) {
        if (rootAvailable && resolveChoices().ubuntuChroot != null) {
            val app = context.applicationContext
            Thread { RootShell.ensureChrootHelper(app); RootShell.resolveBusyBox() }.start()
        }
    }

    /** Nativecode `forceTerminalResize` parity — post to view; SIGWINCH on cols/rows change. */
    fun forceTerminalResize(tv: TerminalView) {
        tv.post {
            try {
                if (tv.width <= 0 || tv.height <= 0) return@post
                tv.updateSize()
                tv.onScreenUpdated()
                val session = tv.currentSession ?: return@post
                val emulator = session.emulator ?: return@post
                if (!session.isRunning) return@post
                val cols = emulator.mColumns
                val rows = emulator.mRows
                val pid = session.pid
                if (pid > 0 &&
                    (cols != lastWinchKey[0] || rows != lastWinchKey[1] || pid != lastWinchKey[2])
                ) {
                    lastWinchKey[0] = cols
                    lastWinchKey[1] = rows
                    lastWinchKey[2] = pid
                    runCatching { Os.kill(pid, OsConstants.SIGWINCH) }
                }
            } catch (_: Exception) {
            }
        }
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun openDrawer() {
        scope.launch { drawerState.open() }
    }
    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }
    val drawerOpening = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open
    // The IME would cover half the drawer: hide it as soon as the drawer heads open (swipe or ☰).
    LaunchedEffect(drawerOpening) {
        if (drawerOpening) terminalViewRef?.let { hideIme(it) }
    }
    // Back closes an open drawer; when closed, MainActivity's handler runs as before.
    BackHandler(enabled = drawerOpening) { closeDrawer() }

    /** Drawer destination: drop the keyboard and the drawer before leaving the terminal. */
    fun leaveTerminal(go: () -> Unit): () -> Unit = {
        terminalViewRef?.let { hideIme(it) }
        scope.launch { drawerState.snapTo(DrawerValue.Closed) }
        go()
    }

    fun showLinks(view: TerminalView?) {
        val links = TerminalClipboard.links(view)
        if (links.isEmpty()) toast(context.getString(R.string.terminal_links_empty))
        else linkChoices = links
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            TerminalDrawerSheet(
                sessions = sessions.mapIndexed { i, managed ->
                    // Read when the screen recomposes (the drawer heading open is one), so the
                    // marks are fresh whenever the drawer is shown.
                    val process = managed.session
                    TerminalTabUi(
                        title = managed.title.ifBlank { titles.getOrElse(i) { "session" } },
                        iconRes = managed.iconRes ?: R.drawable.ic_terminal,
                        mark = when {
                            process == null -> MarkState.IDLE
                            process.isRunning -> MarkState.RUNNING
                            process.exitStatus == 0 -> MarkState.IDLE
                            else -> MarkState.FAILED
                        }
                    )
                },
                selectedIndex = activeIndex,
                extraKeysVisible = showExtraKeys,
                onSelect = {
                    FluxTerminalSessionManager.switchSession(it)
                    closeDrawer()
                },
                onClose = { FluxTerminalSessionManager.closeSession(context, it) },
                onCloseOthers = { keep ->
                    // Descending so the indices still to close stay valid.
                    for (j in sessions.indices.reversed()) {
                        if (j != keep) FluxTerminalSessionManager.closeSession(context, j)
                    }
                },
                onNewSession = {
                    openUbuntu(fromEmptyState = false)
                    closeDrawer()
                },
                onToggleExtraKeys = {
                    val next = !showExtraKeys
                    showExtraKeys = next
                    TerminalPreferences.setExtraKeysEnabled(context, next)
                    terminalViewRef?.let { forceTerminalResize(it) }
                },
                onOpenDashboard = onOpenDashboard?.let(::leaveTerminal),
                onOpenFiles = onOpenFiles?.let(::leaveTerminal),
                onOpenSettings = onOpenSettings?.let(::leaveTerminal),
                wakeLockHeld = wakeLockHeld,
                // The lock lives only while sessions do (the service drops it with the last one).
                onToggleWakeLock = if (sessions.isNotEmpty()) {
                    { AppTerminalService.toggleWakeLock(context) }
                } else {
                    null
                },
                newSessionLabel = stringResource(
                    if (runtime == UbuntuRuntimeMode.CHROOT) R.string.terminal_new_session_chroot
                    else R.string.terminal_new_session
                ),
                shellMenu = { onDismiss ->
                    val choices = remember(rootAvailable, detectionRevision, runtime) { resolveChoices() }
                    ShellMenu(
                        expanded = true,
                        onDismiss = onDismiss,
                        choices = choices,
                        onNewTab = {
                            onDismiss()
                            openUbuntu(fromEmptyState = false)
                            closeDrawer()
                        },
                        onOpen = { card ->
                            onDismiss()
                            openShell(card)
                            closeDrawer()
                        }
                    )
                }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // The terminal's stock under the status bar, the same colour TerminalView's renderer
                // paints (:terminal-emulator's TerminalColorScheme default background); the empty
                // state sits on the scheme's own stock (light in light mode).
                .background(if (activeIndex >= 0) TerminalBackground else MaterialTheme.colorScheme.background)
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(
                    WindowInsets.ime.union(WindowInsets.navigationBars)
                        .only(WindowInsetsSides.Bottom)
                )
        ) {
            if (activeIndex >= 0) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    AndroidView(
                        factory = { ctx ->
                            TerminalView(ctx, null).apply {
                                setTextSize(fontSize)
                                try {
                                    val tf = Typeface.createFromAsset(ctx.assets, "fonts/font.ttf")
                                    setTypeface(tf)
                                } catch (_: Exception) {
                                }
                                setTerminalViewClient(object : TerminalViewClient {
                                    override fun onScale(scale: Float): Float {
                                        if (scale < 0.9f || scale > 1.1f) {
                                            val next = (fontSize * scale).toInt().coerceIn(
                                                TerminalPreferences.FONT_MIN,
                                                TerminalPreferences.FONT_MAX
                                            )
                                            fontSize = next
                                            TerminalPreferences.setFontSize(ctx, next)
                                            setTextSize(next)
                                            return 1.0f
                                        }
                                        return scale
                                    }

                                    // A link under the tap opens in the browser; anything else
                                    // raises the keyboard. Also in mouse-tracking apps (Claude
                                    // Code / Codex full screen, tmux mouse on): TerminalView has
                                    // already sent them the tap as a click, which they ignore or
                                    // use to focus, and a tap on a link (a login URL) means open
                                    // it. Only vim with mouse=a also moves its cursor there.
                                    override fun onSingleTapUp(e: android.view.MotionEvent) {
                                        val url = TerminalClipboard.linkAt(this@apply, e)
                                        if (url != null) TerminalClipboard.openLink(ctx, url)
                                        else showTerminalIme(this@apply)
                                    }

                                    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                                    override fun shouldEnforceCharBasedInput(): Boolean = false
                                    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                                    override fun isTerminalViewSelected(): Boolean = true
                                    override fun copyModeChanged(active: Boolean) {}
                                    // Ctrl+Shift+V / a keyboard Paste key paste; every other key goes
                                    // to the view (nativecode parity).
                                    override fun onKeyDown(keyCode: Int, event: KeyEvent, session: com.termux.terminal.TerminalSession): Boolean {
                                        if (!TerminalClipboard.isPasteShortcut(keyCode, event.isCtrlPressed, event.isShiftPressed)) {
                                            return false
                                        }
                                        TerminalClipboard.pasteWithFeedback(this@apply, ctx)
                                        return true
                                    }
                                    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false
                                    override fun onLongPress(e: android.view.MotionEvent): Boolean = false
                                    // Modifiers read from shared lockable state (ModifierState parity).
                                    override fun readControlKey(): Boolean = modState.readCtrl(true)
                                    override fun readAltKey(): Boolean = modState.readAlt(true)
                                    override fun readShiftKey(): Boolean = modState.readShift(true)
                                    override fun readFnKey(): Boolean = modState.readFn(true)
                                    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: com.termux.terminal.TerminalSession): Boolean = false
                                    override fun onEmulatorSet() {}
                                    override fun logError(tag: String, message: String) {}
                                    override fun logWarn(tag: String, message: String) {}
                                    override fun logInfo(tag: String, message: String) {}
                                    override fun logDebug(tag: String, message: String) {}
                                    override fun logVerbose(tag: String, message: String) {}
                                    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
                                    override fun logStackTrace(tag: String, e: Exception) {}
                                })
                                TerminalClipboard.installContextMenu(this) { showLinks(this) }
                                terminalViewRef = this
                            }
                        },
                        update = { view ->
                            terminalViewRef = view
                            // Keep text size in sync with global prefs (settings page / pinch).
                            view.setTextSize(fontSize)
                            // T3: focus parity — re-attach + focus on every recompose/switch so a
                            // freshly opened or switched session never shows a blank/dead view.
                            view.isFocusable = true
                            view.isFocusableInTouchMode = true
                            // T3/proot-opt-01: only re-attach + re-focus when the session
                            // actually changed (new tab / switched tab). Typing, font-size,
                            // extra-keys and other recompositions must NOT re-attach the
                            // same TerminalSession or steal focus every frame.
                            val activeTerminalSession = FluxTerminalSessionManager.activeSession?.session
                            if (activeTerminalSession == null || view.currentSession != activeTerminalSession) {
                                FluxTerminalSessionManager.attachView(view)
                                view.requestFocus()
                            }
                            // T5: resize + SIGWINCH only on real size/pid change.
                            forceTerminalResize(view)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .keepTerminalGestures(rtl) { terminalViewRef }
                    )
                    DrawerEdgeZone(
                        drawerState = drawerState,
                        onOpen = ::openDrawer,
                        onTap = { terminalViewRef?.let { showTerminalIme(it) } },
                        modifier = Modifier.align(EDGE_ZONE_ALIGNMENT)
                    )
                }
                DisposableEffect(Unit) {
                    onDispose {
                        terminalViewRef?.let { hideIme(it) }
                        FluxTerminalSessionManager.detachView()
                        terminalViewRef = null
                    }
                }

                // ExtraKeys toolbar: nativecode inject path (TerminalView.onKeyDown / inputCodePoint).
                if (showExtraKeys) {
                    TerminalExtraKeys(
                        modState = modState,
                        terminalView = { terminalViewRef },
                        accelerateRepeat = accelerateKeys,
                        onOpenDrawer = ::openDrawer,
                        onPaste = { TerminalClipboard.pasteWithFeedback(terminalViewRef, context) },
                        onPasteLongPress = { terminalViewRef?.showContextMenu() },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    TerminalEmptyState(
                        // Before the first-frame auto-open fires, show "opening" rather than "closed".
                        opening = opening || !autoOpenTried,
                        errorMessage = openError,
                        onOpen = { openUbuntu(fromEmptyState = true) },
                        modifier = Modifier.fillMaxSize(),
                        onOpenDashboard = onOpenDashboard?.let { leaveTerminal(it) },
                        onOpenSettings = onOpenSettings?.let { leaveTerminal(it) },
                    )
                    DrawerEdgeZone(
                        drawerState = drawerState,
                        onOpen = ::openDrawer,
                        onTap = {},
                        modifier = Modifier.align(EDGE_ZONE_ALIGNMENT)
                    )
                }
            }
        }
    }

    linkChoices?.let { links ->
        TerminalLinksDialog(
            links = links,
            onOpen = { url ->
                linkChoices = null
                TerminalClipboard.openLink(context, url)
            },
            onCopy = { url ->
                linkChoices = null
                TerminalClipboard.copy(context, url, R.string.terminal_link_copied)
            },
            onDismiss = { linkChoices = null }
        )
    }
}

/** T3: tap → focus + IME (SHOW_FORCED fallback for OEMs). */
private fun showTerminalIme(view: TerminalView) {
    view.requestFocus()
    val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    if (!imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)) {
        imm.showSoftInput(view, InputMethodManager.SHOW_FORCED)
    }
}

/**
 * Thin strip on the terminal's left edge where an edge swipe opens the drawer instead of going
 * Back: [systemGestureExclusion] keeps the system Back gesture out of this bounded band. Elsewhere
 * on the terminal the drawer's own drag (on its container, an ancestor) takes swipes towards it
 * ([keepTerminalGestures]). This strip is hit before the terminal and consumes nothing, so its
 * touches reach that drag too, which follows the finger. If a rightward swipe still leaves the
 * drawer unmoved, it opens the drawer itself; a plain tap is forwarded via [onTap] so the strip
 * doesn't leave a dead column.
 */
@Composable
private fun DrawerEdgeZone(
    drawerState: DrawerState,
    onOpen: () -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnTap by rememberUpdatedState(onTap)
    Box(
        modifier = modifier
            .width(EDGE_ZONE_WIDTH)
            .height(EDGE_ZONE_HEIGHT)
            .systemGestureExclusion()
            .pointerInput(drawerState) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startOffset = drawerState.currentOffset
                    var last = down
                    var moved = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        last = change
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            moved = true
                        }
                        if (!change.pressed) break
                    }
                    val drawerUntouched = startOffset.isNaN() || abs(drawerState.currentOffset - startOffset) < 1f
                    when {
                        !moved && last.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis ->
                            currentOnTap()
                        moved && drawerUntouched && drawerState.isClosed &&
                            last.position.x - down.position.x > EDGE_OPEN_DISTANCE.toPx() -> currentOnOpen()
                    }
                }
            }
    )
}

/**
 * Termux-style session drawer: the session list on top, actions at the bottom within thumb reach,
 * then the app's other places (Ubuntu overview, Files, Settings). Stateless (the screen owns the
 * session manager calls) so previews can drive it with fake sessions. [shellMenu] is the
 * "More shells" dropdown, composed only while open. A null destination hides its row.
 *
 * Printed as one Federal Blue sheet with the extra-keys field: a grain-printed header band with
 * the wordmark, session rows marked by process state, the current session as the selection
 * overprint, "New session" as the sheet's one primary plate.
 */
@Composable
fun TerminalDrawerSheet(
    sessions: List<TerminalTabUi>,
    selectedIndex: Int,
    extraKeysVisible: Boolean,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onCloseOthers: (Int) -> Unit,
    onNewSession: () -> Unit,
    onToggleExtraKeys: () -> Unit,
    onOpenDashboard: (() -> Unit)?,
    onOpenFiles: (() -> Unit)?,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
    wakeLockHeld: Boolean = false,
    onToggleWakeLock: (() -> Unit)? = null,
    newSessionLabel: String = stringResource(R.string.terminal_new_session),
    shellMenu: @Composable (onDismiss: () -> Unit) -> Unit = {},
) {
    val inks = AkaTheme.inks
    // Cut-paper sheet: square where it meets the screen edge, trimmed at the free edge.
    val sheetShape = MaterialTheme.shapes.extraLarge.copy(
        topStart = CornerSize(0),
        bottomStart = CornerSize(0),
    )
    ModalDrawerSheet(
        modifier = modifier.widthIn(max = DRAWER_MAX_WIDTH),
        drawerShape = sheetShape,
        drawerContainerColor = inks.shellField,
        drawerContentColor = inks.onShellField,
    ) {
        DrawerHeader()
        Column(modifier = Modifier.padding(horizontal = FluxSpacing.md, vertical = FluxSpacing.sm)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    // Room for the selected row's plates, which print outside its bounds.
                    .padding(vertical = FluxSpacing.xxs),
                verticalArrangement = Arrangement.spacedBy(FluxSpacing.xs)
            ) {
                if (sessions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.terminal_drawer_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = inks.onShellFieldMuted,
                        modifier = Modifier.padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm)
                    )
                }
                sessions.forEachIndexed { i, session ->
                    DrawerSessionRow(
                        session = session,
                        selected = i == selectedIndex,
                        canCloseOthers = sessions.size > 1,
                        onSelect = { onSelect(i) },
                        onClose = { onClose(i) },
                        onCloseOthers = { onCloseOthers(i) }
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = FluxSpacing.sm),
                color = inks.shellHairline
            )
            NewSessionRow(label = newSessionLabel, onNewSession = onNewSession, shellMenu = shellMenu)
            DrawerActionRow(
                icon = Icons.Sharp.Keyboard,
                label = stringResource(R.string.terminal_extra_keys),
                modifier = Modifier.toggleable(
                    value = extraKeysVisible,
                    role = Role.Switch,
                    onValueChange = { onToggleExtraKeys() }
                )
            ) {
                FluxSwitch(
                    checked = extraKeysVisible,
                    onCheckedChange = null,
                    modifier = Modifier.padding(end = FluxSpacing.md),
                    onShellField = true
                )
            }
            onToggleWakeLock?.let { toggle ->
                DrawerActionRow(
                    icon = Icons.Sharp.Coffee,
                    label = stringResource(R.string.terminal_drawer_wakelock),
                    supporting = stringResource(R.string.terminal_drawer_wakelock_sub),
                    // Held: the yellow half-disc takes the icon's place (the switch says it in words).
                    leadingMark = if (wakeLockHeld) MarkState.HELD else null,
                    modifier = Modifier.toggleable(
                        value = wakeLockHeld,
                        role = Role.Switch,
                        onValueChange = { toggle() }
                    )
                ) {
                    FluxSwitch(
                        checked = wakeLockHeld,
                        onCheckedChange = null,
                        modifier = Modifier.padding(end = FluxSpacing.md),
                        onShellField = true
                    )
                }
            }
            if (onOpenDashboard != null || onOpenFiles != null || onOpenSettings != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = FluxSpacing.sm),
                    color = inks.shellHairline
                )
            }
            onOpenDashboard?.let {
                DrawerActionRow(
                    icon = Icons.Sharp.SpaceDashboard,
                    label = stringResource(R.string.terminal_drawer_ubuntu),
                    modifier = Modifier.clickable(role = Role.Button, onClick = it)
                )
            }
            onOpenFiles?.let {
                DrawerActionRow(
                    icon = Icons.Sharp.Folder,
                    label = stringResource(R.string.terminal_drawer_files),
                    modifier = Modifier.clickable(role = Role.Button, onClick = it)
                )
            }
            onOpenSettings?.let {
                DrawerActionRow(
                    icon = Icons.Sharp.Settings,
                    label = stringResource(R.string.terminal_drawer_settings),
                    modifier = Modifier.clickable(role = Role.Button, onClick = it)
                )
            }
        }
    }
}

/**
 * The sheet's header band: the AkaTerminal wordmark printed with grain (static; its two-pass
 * print belongs to onboarding), then the "Sessions" heading, closed off by a hairline.
 */
@Composable
private fun DrawerHeader() {
    val inks = AkaTheme.inks
    val density = LocalDensity.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .risoGrain(inks.grainAlpha)
            .padding(
                start = FluxSpacing.xl,
                end = FluxSpacing.lg,
                top = FluxSpacing.lg,
                bottom = FluxSpacing.md,
            )
    ) {
        CompositionLocalProvider(
            LocalDensity provides Density(density.density, minOf(density.fontScale, WORDMARK_MAX_FONT_SCALE)),
        ) {
            // "Aka" prints in the red plate ink, which holds on the blue field in both schemes
            // (the light scheme's deepened primary sinks into it).
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = inks.selectionPlateA)) {
                AkaWordmark(
                    style = MaterialTheme.typography.headlineMedium,
                    restColor = inks.onShellField,
                )
            }
        }
        Text(
            text = stringResource(R.string.terminal_drawer_title),
            style = MaterialTheme.typography.titleSmall,
            color = inks.onShellFieldMuted,
            modifier = Modifier
                .padding(top = FluxSpacing.xs)
                .semantics { heading() }
        )
    }
    HorizontalDivider(color = inks.shellHairline)
}

/**
 * State inks on the Federal field. The field is the same blue in both schemes, so marks take
 * the full-strength spot inks; the light scheme's deepened state inks would sink into it.
 */
@Composable
private fun fieldMarkColor(state: MarkState): Color {
    val inks = AkaTheme.inks
    return when (state) {
        MarkState.RUNNING -> Riso.GreenTint
        MarkState.HELD -> Riso.Yellow
        // Fluorescent pink on the dark field; its pale tint on the lighter light-scheme field.
        MarkState.FAILED -> if (inks.isDark) inks.failed else MaterialTheme.colorScheme.errorContainer
        MarkState.IDLE, MarkState.WORKING -> inks.onShellFieldMuted
    }
}

/**
 * One drawer action on the field: 56dp row with a leading Sharp icon (or [leadingMark] in its
 * place); [modifier] carries the click/toggle.
 */
@Composable
private fun DrawerActionRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leadingMark: MarkState? = null,
    trailing: @Composable () -> Unit = {},
) {
    val inks = AkaTheme.inks
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DRAWER_ROW_HEIGHT)
            .clip(MaterialTheme.shapes.small)
            .then(modifier)
            .padding(start = FluxSpacing.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(DRAWER_ICON_SIZE), contentAlignment = Alignment.Center) {
            if (leadingMark != null) {
                StatusMark(
                    state = leadingMark,
                    size = STATUS_MARK_SIZE + FluxSpacing.xs,
                    color = fieldMarkColor(leadingMark),
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = inks.onShellFieldMuted,
                    modifier = Modifier.size(DRAWER_ICON_SIZE)
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = FluxSpacing.md, end = FluxSpacing.sm)
                .padding(vertical = if (supporting != null) FluxSpacing.sm else 0.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = inks.onShellField
            )
            supporting?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = inks.onShellFieldMuted
                )
            }
        }
        trailing()
    }
}

/**
 * Tap: new Ubuntu session; long-press or ▾: the shell menu (root, chroot, host). The sheet's
 * primary plate: red ink alone would read under 4.5:1 on the blue field, so it prints as the
 * vermilion overprint, plates out of register until pressed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewSessionRow(
    label: String,
    onNewSession: () -> Unit,
    shellMenu: @Composable (onDismiss: () -> Unit) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val moreLabel = stringResource(R.string.terminal_more_shells)
    val shape = MaterialTheme.shapes.small
    val plates = plateInks(PlateKind.PRIMARY)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val offset = plateOffset(registered = pressed)
    Box(modifier = Modifier.padding(vertical = FluxSpacing.xs)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DRAWER_ROW_HEIGHT)
                .misregistered(shape, plates.fill, plates.plateA, plates.plateB) { offset }
                .clip(shape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    role = Role.Button,
                    onClickLabel = label,
                    onLongClickLabel = moreLabel,
                    onLongClick = { menuOpen = true },
                    onClick = onNewSession
                )
                .padding(start = FluxSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompositionLocalProvider(LocalContentColor provides plates.onFill) {
                Icon(
                    imageVector = Icons.Sharp.Add,
                    contentDescription = null,
                    modifier = Modifier.size(DRAWER_ICON_SIZE)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = FluxSpacing.md, end = FluxSpacing.sm)
                )
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Sharp.ArrowDropDown,
                        contentDescription = moreLabel
                    )
                }
            }
        }
        if (menuOpen) shellMenu { menuOpen = false }
    }
}

/**
 * Menu label for an Ubuntu card. Both runtimes are named explicitly once a chroot shell is
 * in the menu, so "root shell" never leaves the user guessing which install it opens.
 */
private fun ubuntuShellLabelRes(card: TerminalShellCardUi, bothRuntimes: Boolean): Int {
    val root = card.def.type == "shell-root"
    return when {
        card.def.method == "chroot" ->
            if (root) R.string.terminal_shell_ubuntu_chroot_root else R.string.terminal_shell_ubuntu_chroot
        !bothRuntimes -> if (root) R.string.terminal_shell_ubuntu_root else R.string.terminal_shell_ubuntu_proot
        else -> if (root) R.string.terminal_shell_ubuntu_proot_root else R.string.terminal_shell_ubuntu_proot
    }
}

@Composable
private fun ShellMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    choices: TerminalSessionChoices,
    onNewTab: () -> Unit,
    onOpen: (TerminalShellCardUi) -> Unit,
) {
    val host = choices.host
    val ubuntuCards = listOfNotNull(choices.ubuntuRoot, choices.otherUser, choices.otherRoot)
    val bothRuntimes = choices.mode == UbuntuRuntimeMode.CHROOT || ubuntuCards.any { it.def.method == "chroot" }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        ShellMenuItem(
            stringResource(
                if (choices.mode == UbuntuRuntimeMode.CHROOT) R.string.terminal_new_session_chroot
                else R.string.terminal_new_session
            ),
            Icons.Sharp.Add,
            onNewTab
        )
        if (ubuntuCards.isNotEmpty() || host != null) HorizontalDivider()
        ubuntuCards.forEach { card ->
            val icon = when {
                card.def.type == "shell-root" -> Icons.Sharp.AdminPanelSettings
                card.def.method == "chroot" -> Icons.Sharp.Security
                else -> Icons.Sharp.Terminal
            }
            ShellMenuItem(stringResource(ubuntuShellLabelRes(card, bothRuntimes)), icon) { onOpen(card) }
        }
        host?.let {
            ShellMenuItem(stringResource(R.string.terminal_shell_host), Icons.Sharp.Terminal) {
                onOpen(it)
            }
        }
    }
}

@Composable
private fun ShellMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null) },
        onClick = onClick
    )
}

/**
 * One session in the drawer: tap switches, the own 48dp ✕ target closes, long-press opens
 * "Close" / "Close other tabs". A [StatusMark] tells its process state by form; the selected
 * session prints as the selection overprint, plates out of register until pressed.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerSessionRow(
    session: TerminalTabUi,
    selected: Boolean,
    canCloseOthers: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onCloseOthers: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val inks = AkaTheme.inks
    val title = TerminalSessionDefaults.shortTabTitle(session.title)
    val closeLabel = stringResource(R.string.terminal_tab_close_named, title)
    val switchLabel = stringResource(R.string.terminal_tab_switch, title)
    val menuLabel = stringResource(R.string.terminal_tab_menu)
    val markLabel = stringResource(
        when (session.mark) {
            MarkState.FAILED -> R.string.terminal_aka_session_failed
            MarkState.IDLE -> R.string.terminal_aka_session_exited
            else -> R.string.terminal_aka_session_running
        }
    )
    val namedState = stringResource(R.string.terminal_aka_session_named, title, markLabel)
    val shape = MaterialTheme.shapes.small
    val plates = plateInks(PlateKind.SELECTION)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val offset = plateOffset(registered = pressed)
    val content = if (selected) plates.onFill else inks.onShellFieldMuted
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DRAWER_ROW_HEIGHT)
                .then(
                    if (selected) Modifier.misregistered(shape, plates.fill, plates.plateA, plates.plateB) { offset }
                    else Modifier
                )
                .clip(shape)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    role = Role.Tab,
                    onClickLabel = switchLabel,
                    onLongClickLabel = menuLabel,
                    onLongClick = { menuOpen = true },
                    onClick = onSelect
                )
                .semantics { this.selected = selected }
                .padding(start = FluxSpacing.lg, end = FluxSpacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // In the icon column of the action rows below, so marks and icons line up.
            Box(modifier = Modifier.size(DRAWER_ICON_SIZE), contentAlignment = Alignment.Center) {
                StatusMark(state = session.mark, size = STATUS_MARK_SIZE, color = fieldMarkColor(session.mark))
            }
            Text(
                text = title,
                style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = FluxSpacing.md, end = FluxSpacing.sm)
                    // The name first, then its state.
                    .semantics { contentDescription = namedState }
            )
            // Own 48dp hit area; the row's select target never overlaps it.
            Box(
                modifier = Modifier
                    .size(FluxSpacing.touch)
                    .clip(shape)
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = closeLabel },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Sharp.Close,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.terminal_tab_close)) },
                leadingIcon = { Icon(Icons.Sharp.Close, contentDescription = null) },
                onClick = {
                    menuOpen = false
                    onClose()
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.terminal_tab_close_others)) },
                enabled = canCloseOthers,
                onClick = {
                    menuOpen = false
                    onCloseOthers()
                }
            )
        }
    }
}

/**
 * Links found in the session's output, newest first: tap opens one in the browser, long-press
 * copies it. Stateless for screenshot previews. Links are set in the terminal's mono face, in
 * the Federal tint (the red primary reads under 4.5:1 on the dialog's container).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalLinksDialog(
    links: List<String>,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val copyLabel = stringResource(R.string.terminal_link_copy)
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_links_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.terminal_links_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = FluxSpacing.sm)
                )
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    links.forEach { url ->
                        val openLabel = stringResource(R.string.terminal_link_open, url)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = FluxSpacing.touch)
                                .clip(MaterialTheme.shapes.small)
                                .combinedClickable(
                                    role = Role.Button,
                                    onClickLabel = openLabel,
                                    onLongClickLabel = copyLabel,
                                    onLongClick = { onCopy(url) },
                                    onClick = { onOpen(url) }
                                )
                                // 14dp top and bottom: a one-line link is exactly the 48dp target.
                                .padding(horizontal = FluxSpacing.sm, vertical = FluxSpacing.md + FluxSpacing.xxs),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Sharp.Link,
                                contentDescription = null,
                                tint = colors.secondary,
                                modifier = Modifier.size(LINK_ICON_SIZE)
                            )
                            Text(
                                text = url,
                                style = CodeStyle,
                                color = colors.secondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = FluxSpacing.sm)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.terminal_links_close)) }
        }
    )
}

/**
 * Compact empty state: no selector, just the Ubuntu shell being opened, or why it failed
 * with a single retry. Stateless for screenshot previews.
 *
 * Set flush left on the stock like a zine page: the mark, the title in the display voice, a
 * status line led by its mark (working, failed or idle by form), then the one primary plate.
 */
@Composable
fun TerminalEmptyState(
    opening: Boolean,
    errorMessage: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    // With no session there is no extra-keys bar (no ☰) and the edge swipe is hidden, so the
    // other destinations, e.g. "Repair host files" on the Ubuntu page, need a visible way in.
    onOpenDashboard: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val failed = !opening && errorMessage != null
    val mark = when {
        opening -> MarkState.WORKING
        failed -> MarkState.FAILED
        else -> MarkState.IDLE
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = FluxSpacing.xl, vertical = FluxSpacing.xxl),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(modifier = Modifier.widthIn(max = EMPTY_STATE_MAX_WIDTH)) {
            Image(
                painter = painterResource(id = R.drawable.ic_aka_mark),
                contentDescription = null,
                modifier = Modifier.size(EMPTY_MARK_SIZE)
            )
            Text(
                text = stringResource(R.string.terminal_empty_title),
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onBackground,
                modifier = Modifier.padding(top = FluxSpacing.xl)
            )
            Row(
                modifier = Modifier.padding(top = FluxSpacing.sm),
                verticalAlignment = Alignment.Top
            ) {
                StatusMark(
                    state = mark,
                    size = STATUS_MARK_SIZE,
                    color = markColor(mark),
                    // Centred on the first line of bodyMedium (20sp line, 10dp mark).
                    modifier = Modifier.padding(top = FluxSpacing.xs + FluxSpacing.xxs / 2)
                )
                Spacer(Modifier.width(FluxSpacing.sm))
                Text(
                    text = when {
                        opening -> stringResource(R.string.terminal_empty_opening)
                        errorMessage != null -> errorMessage
                        else -> stringResource(R.string.terminal_empty_closed)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (failed) colors.error else colors.onSurfaceVariant
                )
            }
            PlatedButton(
                text = stringResource(R.string.terminal_open_ubuntu),
                onClick = onOpen,
                icon = Icons.Sharp.Terminal,
                enabled = !opening,
                modifier = Modifier.padding(top = FluxSpacing.xl)
            )
            if (onOpenDashboard != null || onOpenSettings != null) {
                Row(
                    modifier = Modifier.padding(top = FluxSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
                ) {
                    onOpenDashboard?.let {
                        TextButton(onClick = it, modifier = Modifier.heightIn(min = FluxSpacing.touch)) {
                            Text(stringResource(R.string.terminal_drawer_ubuntu))
                        }
                    }
                    onOpenSettings?.let {
                        TextButton(onClick = it, modifier = Modifier.heightIn(min = FluxSpacing.touch)) {
                            Text(stringResource(R.string.terminal_drawer_settings))
                        }
                    }
                }
            }
        }
    }
}
