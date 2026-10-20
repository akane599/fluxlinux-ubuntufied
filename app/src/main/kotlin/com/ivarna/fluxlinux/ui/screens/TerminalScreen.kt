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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.ivarna.fluxlinux.core.terminal.TerminalShellCatalog
import com.ivarna.fluxlinux.core.utils.TerminalPreferences
import com.ivarna.fluxlinux.ui.components.FluxSwitch
import com.ivarna.fluxlinux.ui.terminal.TerminalExtraKeys
import com.ivarna.fluxlinux.ui.terminal.TerminalModifierState
import com.ivarna.fluxlinux.ui.terminal.TerminalSessionDefaults
import com.ivarna.fluxlinux.ui.terminal.hideIme
import com.ivarna.fluxlinux.ui.theme.FluxAccentMagenta
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.math.abs
import kotlinx.coroutines.launch

/** One terminal session as the drawer's session list draws it. */
data class TerminalTabUi(
    val title: String,
    @DrawableRes val iconRes: Int,
)

/** Drawer sheet width cap, so the scrim stays visible (and tappable) on a phone. */
private val DRAWER_MAX_WIDTH = 300.dp
private val DRAWER_ROW_HEIGHT = 56.dp

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
 * No top bar: sessions, new shells, the extra-keys toggle and Back live in a Termux-style
 * drawer opened by a left-edge swipe or the extra keys' ☰ key.
 *
 * Ubuntu-first: there is no distro selector. Entering the screen with no sessions opens an
 * Ubuntu PRoot user shell once; the drawer's "New Ubuntu session" opens another one. Secondary
 * shells (Ubuntu root, Ubuntu chroot, Termux host) live in its long-press / dropdown menu.
 * Session values come from [TerminalShellCatalog] via [TerminalSessionDefaults].
 */
@Composable
fun TerminalScreen(
    onBack: (() -> Unit)? = null,
    embeddedInBottomNav: Boolean = false
) {
    val context = LocalContext.current
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
    // Empty-state auto-open runs once per entry; a failure leaves a retry button, never a loop.
    var autoOpenTried by rememberSaveable { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }
    var openError by rememberSaveable { mutableStateOf<String?>(null) }
    var rootAvailable by remember { mutableStateOf(false) }
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

    /** Fresh filesystem snapshot each call, so a just-installed Ubuntu is picked up on retry. */
    fun resolveChoices() = TerminalSessionDefaults.resolve(
        TerminalShellCatalog.sections(
            context,
            TerminalShellCatalog.availability(context, rootAvailable)
        ).flatMap { it.cards }
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

    /** Default Ubuntu PRoot user tab ("+", empty-state auto-open and retry). */
    fun openUbuntu(fromEmptyState: Boolean) {
        val card = resolveChoices().defaultUser
        if (card == null || !card.enabled) {
            val reason = context.getString(R.string.terminal_error_ubuntu_missing)
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

    LaunchedEffect(Unit) {
        RootShell.probeRootAvailable { ok -> rootAvailable = ok }
        if (!autoOpenTried) {
            autoOpenTried = true
            if (FluxTerminalSessionManager.sessionCount == 0) openUbuntu(fromEmptyState = true)
        }
    }
    // A stale failure must not reappear after tabs were opened (e.g. via "+") and closed again.
    val hasSessions = sessions.isNotEmpty()
    LaunchedEffect(hasSessions) {
        if (hasSessions) openError = null
    }
    // Root granted + Ubuntu chroot installed: warm helper + BusyBox off main so the first
    // chroot open skips su staging/probes. Never prompts non-root users.
    LaunchedEffect(rootAvailable) {
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            TerminalDrawerSheet(
                sessions = sessions.mapIndexed { i, managed ->
                    TerminalTabUi(
                        title = managed.title.ifBlank { titles.getOrElse(i) { "session" } },
                        iconRes = managed.iconRes ?: R.drawable.ic_terminal
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
                onBack = onBack?.let { back ->
                    {
                        terminalViewRef?.let { hideIme(it) }
                        back()
                    }
                },
                shellMenu = { onDismiss ->
                    val choices = remember(rootAvailable) { resolveChoices() }
                    ShellMenu(
                        expanded = true,
                        onDismiss = onDismiss,
                        ubuntuRoot = choices.ubuntuRoot,
                        ubuntuChroot = choices.ubuntuChroot,
                        host = choices.host,
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
                .background(MaterialTheme.colorScheme.background)
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

                                    override fun onSingleTapUp(e: android.view.MotionEvent) {
                                        showTerminalIme(this@apply)
                                    }

                                    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
                                    override fun shouldEnforceCharBasedInput(): Boolean = false
                                    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
                                    override fun isTerminalViewSelected(): Boolean = true
                                    override fun copyModeChanged(active: Boolean) {}
                                    // Let the view handle keys — never swallow (nativecode parity).
                                    override fun onKeyDown(keyCode: Int, event: KeyEvent, session: com.termux.terminal.TerminalSession): Boolean = false
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
                        modifier = Modifier.fillMaxSize()
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
                        modifier = Modifier.fillMaxSize()
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
 * Thin strip on the terminal's left edge that makes the drawer's edge swipe work over the
 * [TerminalView]. The `AndroidView` consumes every touch, so the drawer's own drag (on its
 * container, an ancestor) never starts there. This strip is hit before the terminal and consumes
 * nothing, so its touches reach that ancestor drag, which follows the finger. If a rightward swipe
 * still leaves the drawer unmoved, it opens the drawer itself; a plain tap is forwarded via [onTap]
 * so the strip doesn't leave a dead column. [systemGestureExclusion] keeps the swipe from becoming
 * the system Back gesture inside this bounded band.
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
 * Termux-style session drawer: the session list on top, actions at the bottom within thumb reach.
 * Stateless (the screen owns the session manager calls) so previews can drive it with fake
 * sessions. [shellMenu] is the "More shells" dropdown, composed only while open.
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
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    shellMenu: @Composable (onDismiss: () -> Unit) -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    ModalDrawerSheet(modifier = modifier.widthIn(max = DRAWER_MAX_WIDTH)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.terminal_drawer_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .semantics { heading() }
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (sessions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.terminal_drawer_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
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
                modifier = Modifier.padding(vertical = 8.dp),
                color = colors.outlineVariant
            )
            NewSessionRow(onNewSession = onNewSession, shellMenu = shellMenu)
            val extraKeysLabel = stringResource(R.string.terminal_extra_keys)
            DrawerActionRow(
                icon = Icons.Default.Keyboard,
                label = extraKeysLabel,
                modifier = Modifier.toggleable(
                    value = extraKeysVisible,
                    role = Role.Switch,
                    onValueChange = { onToggleExtraKeys() }
                )
            ) {
                FluxSwitch(
                    checked = extraKeysVisible,
                    onCheckedChange = null,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
            if (onBack != null) {
                DrawerActionRow(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    label = stringResource(R.string.terminal_back_home),
                    modifier = Modifier.clickable(role = Role.Button, onClick = onBack)
                )
            }
        }
    }
}

/** One drawer action: 56dp pill row with a leading icon; [modifier] carries the click/toggle. */
@Composable
private fun DrawerActionRow(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DRAWER_ROW_HEIGHT)
            .clip(CircleShape)
            .then(modifier)
            .padding(start = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp, end = 8.dp)
        )
        trailing()
    }
}

/** Tap: new Ubuntu session; long-press or ▾: the shell menu (root, chroot, host). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewSessionRow(
    onNewSession: () -> Unit,
    shellMenu: @Composable (onDismiss: () -> Unit) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val newLabel = stringResource(R.string.terminal_new_session)
    val moreLabel = stringResource(R.string.terminal_more_shells)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DRAWER_ROW_HEIGHT)
                .clip(CircleShape)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = newLabel,
                    onLongClickLabel = moreLabel,
                    onLongClick = { menuOpen = true },
                    onClick = onNewSession
                )
                .padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = FluxAccentMagenta,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = newLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp)
            )
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = moreLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (menuOpen) shellMenu { menuOpen = false }
    }
}

@Composable
private fun ShellMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    ubuntuRoot: TerminalShellCardUi?,
    ubuntuChroot: TerminalShellCardUi?,
    host: TerminalShellCardUi?,
    onNewTab: () -> Unit,
    onOpen: (TerminalShellCardUi) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        ShellMenuItem(stringResource(R.string.terminal_new_session), Icons.Default.Add, onNewTab)
        if (ubuntuRoot != null || ubuntuChroot != null || host != null) HorizontalDivider()
        ubuntuRoot?.let {
            ShellMenuItem(stringResource(R.string.terminal_shell_ubuntu_root), Icons.Default.AdminPanelSettings) {
                onOpen(it)
            }
        }
        ubuntuChroot?.let {
            ShellMenuItem(stringResource(R.string.terminal_shell_ubuntu_chroot), Icons.Default.Security) {
                onOpen(it)
            }
        }
        host?.let {
            ShellMenuItem(stringResource(R.string.terminal_shell_host), Icons.Default.Terminal) {
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
 * "Close" / "Close other tabs". The selected session gets the accent tint.
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
    val colors = MaterialTheme.colorScheme
    val title = TerminalSessionDefaults.shortTabTitle(session.title)
    val closeLabel = stringResource(R.string.terminal_tab_close_named, title)
    val switchLabel = stringResource(R.string.terminal_tab_switch, title)
    val menuLabel = stringResource(R.string.terminal_tab_menu)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DRAWER_ROW_HEIGHT)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(FluxAccentMagenta.copy(alpha = 0.22f))
                    else Modifier
                )
                .combinedClickable(
                    role = Role.Tab,
                    onClickLabel = switchLabel,
                    onLongClickLabel = menuLabel,
                    onLongClick = { menuOpen = true },
                    onClick = onSelect
                )
                .semantics { this.selected = selected }
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = session.iconRes),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp, end = 8.dp)
            )
            // Own 48dp hit area; the row's select target never overlaps it.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = closeLabel },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = if (selected) colors.onSurface else colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.terminal_tab_close)) },
                leadingIcon = { Icon(Icons.Default.Close, contentDescription = null) },
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
 * Compact empty state: no selector, just the Ubuntu shell being opened, or why it failed
 * with a single retry. Stateless for screenshot previews.
 */
@Composable
fun TerminalEmptyState(
    opening: Boolean,
    errorMessage: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.distro_ubuntu),
            contentDescription = null,
            modifier = Modifier.size(56.dp)
        )
        Text(
            text = stringResource(R.string.terminal_empty_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onBackground,
            modifier = Modifier.padding(top = 16.dp)
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (opening) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = FluxAccentMagenta
                )
                Spacer(Modifier.size(8.dp))
            }
            Text(
                text = when {
                    opening -> stringResource(R.string.terminal_empty_opening)
                    errorMessage != null -> errorMessage
                    else -> stringResource(R.string.terminal_empty_closed)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (!opening && errorMessage != null) colors.error else colors.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        Button(
            onClick = onOpen,
            enabled = !opening,
            modifier = Modifier
                .padding(top = 24.dp)
                .heightIn(min = 48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.terminal_open_ubuntu))
        }
    }
}
