package com.ivarna.fluxlinux.ui.screens

import android.content.Context
import android.graphics.Typeface
import android.system.Os
import android.system.OsConstants
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import com.ivarna.fluxlinux.ui.terminal.TerminalExtraKeys
import com.ivarna.fluxlinux.ui.terminal.TerminalModifierState
import com.ivarna.fluxlinux.ui.terminal.TerminalSessionDefaults
import com.ivarna.fluxlinux.ui.theme.FluxAccentMagenta
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** One terminal tab as the tab row draws it. */
data class TerminalTabUi(
    val title: String,
    @DrawableRes val iconRes: Int,
)

/**
 * In-app terminal with multi-session tabs + full ExtraKeys toolbar (injectKey / ModifierState lock).
 *
 * Ubuntu-first: there is no distro selector. Entering the screen with no sessions opens an
 * Ubuntu PRoot user shell once; "+" opens another one. Secondary shells (Ubuntu root, Ubuntu
 * chroot, Termux host) live in the "+" long-press / dropdown menu. Session values come from
 * [TerminalShellCatalog] via [TerminalSessionDefaults].
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
    var showShellMenu by remember { mutableStateOf(false) }
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                FilledTonalIconButton(
                    onClick = {
                        terminalViewRef?.let { com.ivarna.fluxlinux.ui.terminal.hideIme(it) }
                        onBack.invoke()
                    },
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Text(
                text = "Terminal",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 8.dp)
            )
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    val next = !showExtraKeys
                    showExtraKeys = next
                    TerminalPreferences.setExtraKeysEnabled(context, next)
                    terminalViewRef?.let { forceTerminalResize(it) }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (showExtraKeys) Icons.Default.Keyboard else Icons.Default.KeyboardHide,
                    contentDescription = if (showExtraKeys) "Hide extra keys" else "Show extra keys",
                    tint = if (showExtraKeys) FluxAccentMagenta
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Box {
                NewTabButtons(
                    onNewTab = { openUbuntu(fromEmptyState = false) },
                    onShowMenu = { showShellMenu = true }
                )
                if (showShellMenu) {
                    val choices = remember(rootAvailable) { resolveChoices() }
                    ShellMenu(
                        expanded = true,
                        onDismiss = { showShellMenu = false },
                        ubuntuRoot = choices.ubuntuRoot,
                        ubuntuChroot = choices.ubuntuChroot,
                        host = choices.host,
                        onNewTab = {
                            showShellMenu = false
                            openUbuntu(fromEmptyState = false)
                        },
                        onOpen = { card ->
                            showShellMenu = false
                            openShell(card)
                        }
                    )
                }
            }
        }

        if (sessions.isNotEmpty()) {
            TerminalTabRow(
                tabs = sessions.mapIndexed { i, managed ->
                    TerminalTabUi(
                        title = managed.title.ifBlank { titles.getOrElse(i) { "session" } },
                        iconRes = managed.iconRes ?: R.drawable.ic_terminal
                    )
                },
                selectedIndex = activeIndex,
                onSelect = { FluxTerminalSessionManager.switchSession(it) },
                onClose = { FluxTerminalSessionManager.closeSession(context, it) },
                onCloseOthers = { keep ->
                    // Descending so the indices still to close stay valid.
                    for (j in sessions.indices.reversed()) {
                        if (j != keep) FluxTerminalSessionManager.closeSession(context, j)
                    }
                }
            )
        }

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

                                // T3: tap → focus + IME (SHOW_FORCED fallback for OEMs).
                                override fun onSingleTapUp(e: android.view.MotionEvent) {
                                    requestFocus()
                                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                                    if (!imm.showSoftInput(this@apply, InputMethodManager.SHOW_IMPLICIT)) {
                                        imm.showSoftInput(this@apply, InputMethodManager.SHOW_FORCED)
                                    }
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
            }
            DisposableEffect(Unit) {
                onDispose {
                    terminalViewRef?.let { com.ivarna.fluxlinux.ui.terminal.hideIme(it) }
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
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            TerminalEmptyState(
                // Before the first-frame auto-open fires, show "opening" rather than "closed".
                opening = opening || !autoOpenTried,
                errorMessage = openError,
                onOpen = { openUbuntu(fromEmptyState = true) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** "+" (tap: new Ubuntu tab, long-press: shell menu) plus a small dropdown arrow for the same menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewTabButtons(
    onNewTab: () -> Unit,
    onShowMenu: () -> Unit,
) {
    val newTabLabel = stringResource(R.string.terminal_new_tab)
    val moreLabel = stringResource(R.string.terminal_more_shells)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .size(40.dp)
                .clip(CircleShape)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = newTabLabel,
                    onLongClickLabel = moreLabel,
                    onLongClick = onShowMenu,
                    onClick = onNewTab
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = newTabLabel,
                tint = FluxAccentMagenta,
                modifier = Modifier.size(24.dp)
            )
        }
        IconButton(onClick = onShowMenu) {
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = moreLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
        ShellMenuItem(stringResource(R.string.terminal_new_tab), Icons.Default.Add, onNewTab)
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
 * Scrollable row of pill tabs. Every tab has its own 48dp close target; long-press opens
 * "Close" / "Close other tabs". Stateless so screenshot previews can drive it with fake tabs.
 */
@Composable
fun TerminalTabRow(
    tabs: List<TerminalTabUi>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onCloseOthers: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { i, tab ->
            TerminalTab(
                tab = tab,
                selected = i == selectedIndex,
                canCloseOthers = tabs.size > 1,
                onSelect = { onSelect(i) },
                onClose = { onClose(i) },
                onCloseOthers = { onCloseOthers(i) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalTab(
    tab: TerminalTabUi,
    selected: Boolean,
    canCloseOthers: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onCloseOthers: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val title = TerminalSessionDefaults.shortTabTitle(tab.title)
    val closeLabel = stringResource(R.string.terminal_tab_close_named, title)
    val switchLabel = stringResource(R.string.terminal_tab_switch, title)
    val menuLabel = stringResource(R.string.terminal_tab_menu)
    Box {
        Row(
            modifier = Modifier
                .height(48.dp)
                .clip(CircleShape)
                .background(if (selected) FluxAccentMagenta.copy(alpha = 0.22f) else colors.surfaceVariant)
                .border(
                    BorderStroke(
                        if (selected) 1.5.dp else 1.dp,
                        if (selected) FluxAccentMagenta else colors.outlineVariant.copy(alpha = 0.5f)
                    ),
                    CircleShape
                )
                .combinedClickable(
                    role = Role.Tab,
                    onClickLabel = switchLabel,
                    onLongClickLabel = menuLabel,
                    onLongClick = { menuOpen = true },
                    onClick = onSelect
                )
                .semantics { this.selected = selected }
                .padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = tab.iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .widthIn(max = 128.dp)
            )
            // Own 48dp hit area inside the pill; the tab's select target never overlaps it.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = closeLabel },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) FluxAccentMagenta.copy(alpha = 0.28f)
                            else colors.surface.copy(alpha = 0.6f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = if (selected) colors.onSurface else colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
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
