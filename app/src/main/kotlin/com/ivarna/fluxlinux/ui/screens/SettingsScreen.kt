package com.ivarna.fluxlinux.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.ArrowBack
import androidx.compose.material.icons.automirrored.sharp.HelpOutline
import androidx.compose.material.icons.automirrored.sharp.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.sharp.OpenInNew
import androidx.compose.material.icons.automirrored.sharp.VolumeUp
import androidx.compose.material.icons.sharp.BugReport
import androidx.compose.material.icons.sharp.Code
import androidx.compose.material.icons.sharp.DisplaySettings
import androidx.compose.material.icons.sharp.Folder
import androidx.compose.material.icons.sharp.History
import androidx.compose.material.icons.sharp.Person
import androidx.compose.material.icons.sharp.Replay
import androidx.compose.material.icons.sharp.SdStorage
import androidx.compose.material.icons.sharp.Storage
import androidx.compose.material.icons.sharp.Terminal
import androidx.compose.material.icons.sharp.TextFields
import androidx.compose.material.icons.sharp.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ivarna.fluxlinux.BuildConfig
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.desktop.DesktopLauncher
import com.ivarna.fluxlinux.core.desktop.DesktopSessionQuery
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.system.SharedStorageAccess
import com.ivarna.fluxlinux.core.utils.RuntimeSwitchDecision
import com.ivarna.fluxlinux.core.utils.UbuntuInstallCheck
import com.ivarna.fluxlinux.core.utils.UbuntuInstallProbe
import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
import com.ivarna.fluxlinux.core.utils.UbuntuRuntimeMode
import com.ivarna.fluxlinux.core.utils.UbuntuRuntimeSwitch
import com.ivarna.fluxlinux.ui.components.FluxSwitch
import com.ivarna.fluxlinux.ui.components.UbuntuRuntimeCard
import com.ivarna.fluxlinux.ui.components.UbuntuRuntimeUi
import com.ivarna.fluxlinux.ui.components.aka.AkaRow
import com.ivarna.fluxlinux.ui.components.aka.LocalInTile
import com.ivarna.fluxlinux.ui.components.aka.akaTile
import com.ivarna.fluxlinux.ui.components.aka.AkaWordmark
import com.ivarna.fluxlinux.ui.components.aka.MarkState
import com.ivarna.fluxlinux.ui.components.aka.SectionLabel
import com.ivarna.fluxlinux.ui.components.aka.StatusMark
import com.ivarna.fluxlinux.ui.components.aka.markColor
import com.ivarna.fluxlinux.ui.theme.AkaTheme
import com.ivarna.fluxlinux.ui.theme.CodeStyle
import com.ivarna.fluxlinux.ui.theme.FluxSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SOURCE_URL = "https://github.com/akane599/fluxlinux-ubuntufied"
private const val ISSUES_URL = "https://github.com/akane599/fluxlinux-ubuntufied/issues"
private const val AUTHOR_URL = "https://github.com/akane599"
private const val UPSTREAM_URL = "https://github.com/abhay-byte/fluxlinux"

/**
 * Settings hub: rows in their own tiles, grouped by section labels; each row opens a
 * detail page. About sits at the end: the mark, the wordmark, links and credits.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToOnboarding: (() -> Unit)? = null,
    onNavigateToTroubleshooting: (() -> Unit)? = null,
    onNavigateToTerminalSettings: (() -> Unit)? = null,
    onNavigateToX11Settings: (() -> Unit)? = null,
    onNavigateToAudioSettings: (() -> Unit)? = null,
    onNavigateToChrootSettings: (() -> Unit)? = null,
    onNavigateToProotSettings: (() -> Unit)? = null,
    onNavigateToLegacyTermuxSettings: (() -> Unit)? = null,
) {
    val context = LocalContext.current

    SettingsPageScaffold(title = "Settings", onBack = onBack) {
        // The first group needs no label: the runtime card's own title heads it.
        Box(Modifier.padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm)) {
            UbuntuRuntimeRow()
        }

        // F-Droid (ivarna) flavor only: its manifest declares the permission, Play's does not.
        if (SharedStorageAccess.declared(context)) {
            var lifecycleKey by remember { mutableIntStateOf(0) }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, e ->
                    if (e == Lifecycle.Event.ON_RESUME) lifecycleKey++
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            val granted = remember(lifecycleKey) { SharedStorageAccess.granted() }
            SettingsPageNavRow(
                icon = Icons.Sharp.SdStorage,
                title = "Shared storage",
                subtitle = if (granted) "Allowed: /sdcard and /storage (SD, USB OTG) are mounted in guests"
                else "Not allowed: guests cannot see /sdcard or USB drives. Tap, then enable All files access",
                iconTint = if (granted) MaterialTheme.colorScheme.onSurfaceVariant else AkaTheme.inks.held,
                onClick = { SharedStorageAccess.openSettings(context) }
            )
        }
        if (com.ivarna.fluxlinux.core.system.PhantomProcessFixer.applicable()) {
            var showPhantom by remember { mutableStateOf(false) }
            val phantomState = com.ivarna.fluxlinux.core.system.PhantomProcessFixer.state(context)
            val phantomFixed = phantomState == com.ivarna.fluxlinux.core.system.PhantomProcessFixer.State.DISABLED
            SettingsPageNavRow(
                icon = Icons.Sharp.Warning,
                title = "Process killer fix",
                subtitle = "Phantom process killer: " + when (phantomState) {
                    com.ivarna.fluxlinux.core.system.PhantomProcessFixer.State.DISABLED -> "Disabled"
                    com.ivarna.fluxlinux.core.system.PhantomProcessFixer.State.ENABLED -> "Enabled. Tap to fix with Root, Shizuku or adb"
                    else -> "Unknown. Tap to fix with Root, Shizuku or adb"
                },
                iconTint = if (phantomFixed) MaterialTheme.colorScheme.onSurfaceVariant else AkaTheme.inks.held,
                onClick = { showPhantom = true }
            )
            if (showPhantom) {
                com.ivarna.fluxlinux.ui.components.PhantomProcessDialog(warning = false) { showPhantom = false }
            }
        }
        SettingsPageNavRow(
            icon = Icons.Sharp.Terminal,
            title = "Terminal",
            subtitle = "Font zoom, extra keys, and guest shell",
            onClick = { onNavigateToTerminalSettings?.invoke() }
        )
        SettingsPageNavRow(
            icon = Icons.Sharp.DisplaySettings,
            title = "X11 display",
            subtitle = "Scale, fullscreen, input for embedded X11",
            onClick = { onNavigateToX11Settings?.invoke() }
        )
        SettingsPageNavRow(
            icon = Icons.AutoMirrored.Sharp.VolumeUp,
            title = "Audio",
            subtitle = "Host PulseAudio status, start, restart, logs",
            onClick = { onNavigateToAudioSettings?.invoke() }
        )

        // Other distros' containers and the v1 Termux leftovers stay reachable here.
        SectionLabel(stringResource(R.string.settings_advanced_header))
        SettingsPageNavRow(
            icon = Icons.Sharp.Storage,
            title = "Chroot",
            subtitle = "Installed roots, size, kill orphan processes",
            onClick = { onNavigateToChrootSettings?.invoke() }
        )
        SettingsPageNavRow(
            icon = Icons.Sharp.Folder,
            title = "PRoot",
            subtitle = "Installed containers and app-storage size",
            onClick = { onNavigateToProotSettings?.invoke() }
        )
        SettingsPageNavRow(
            icon = Icons.Sharp.History,
            title = "Legacy Termux",
            subtitle = "Leftover PRoot installs from FluxLinux ≤ v1.8.0 live in the Termux app",
            onClick = { onNavigateToLegacyTermuxSettings?.invoke() }
        )

        if (onNavigateToOnboarding != null || onNavigateToTroubleshooting != null) {
            SectionLabel("Help")
            if (onNavigateToOnboarding != null) {
                SettingsPageNavRow(
                    icon = Icons.Sharp.Replay,
                    title = "Show onboarding",
                    onClick = { onNavigateToOnboarding() }
                )
            }
            if (onNavigateToTroubleshooting != null) {
                SettingsPageNavRow(
                    icon = Icons.AutoMirrored.Sharp.HelpOutline,
                    title = "Troubleshoot",
                    onClick = { onNavigateToTroubleshooting() }
                )
            }
        }

        SettingsAboutSection(open = { openUrl(context, it) })
    }
}

/** About: the mark and wordmark, the fork's links, credits, and one small upstream line. */
@Composable
internal fun SettingsAboutSection(open: (String) -> Unit) {
    // The mark and wordmark head this block; a label above them would only repeat it.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = FluxSpacing.lg, end = FluxSpacing.lg, top = FluxSpacing.xxl, bottom = FluxSpacing.md)
            .semantics(mergeDescendants = true) {},
    ) {
        Image(
            painter = painterResource(R.drawable.ic_aka_mark),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(FluxSpacing.md))
        AkaWordmark(style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(FluxSpacing.xs))
        Text(
            stringResource(R.string.settings_aka_version, BuildConfig.VERSION_NAME),
            style = CodeStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.settings_aka_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    SettingsPageLinkRow(
        icon = Icons.Sharp.Code,
        title = stringResource(R.string.settings_aka_source),
        onClick = { open(SOURCE_URL) },
    )
    SettingsPageLinkRow(
        icon = Icons.Sharp.BugReport,
        title = stringResource(R.string.settings_aka_report),
        onClick = { open(ISSUES_URL) },
    )
    SettingsPageLinkRow(
        icon = Icons.Sharp.Person,
        title = stringResource(R.string.settings_aka_author),
        supporting = stringResource(R.string.settings_aka_author_name),
        onClick = { open(AUTHOR_URL) },
    )

    SectionLabel(stringResource(R.string.settings_aka_thanks_header))
    listOf(
        R.string.settings_aka_thanks_termux to "https://github.com/termux",
        R.string.settings_aka_thanks_proot_distro to "https://github.com/termux/proot-distro",
        R.string.settings_aka_thanks_termux_x11 to "https://github.com/termux/termux-x11",
    ).forEach { (name, url) ->
        SettingsPageLinkRow(title = stringResource(name), onClick = { open(url) })
    }
    AkaRow(
        title = stringResource(R.string.settings_aka_fonts),
        supporting = stringResource(R.string.settings_aka_fonts_summary),
        icon = Icons.Sharp.TextFields,
    )

    Text(
        stringResource(R.string.settings_aka_forked),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = FluxSpacing.lg)
            .fillMaxWidth()
            .heightIn(min = FluxSpacing.touch)
            .clickable(role = Role.Button) { open(UPSTREAM_URL) }
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md),
    )
}

/**
 * "Ubuntu runtime": PRoot or chroot (root). Chroot is only kept after an off-main root probe
 * succeeds; a running desktop has to be stopped first. Neither install is touched.
 */
@Composable
private fun UbuntuRuntimeRow() {
    val context = LocalContext.current
    val app = context.applicationContext
    val scope = rememberCoroutineScope()
    val runtime by remember { UbuntuRuntime.modeFlow(context) }.collectAsState()
    var checking by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var stopFirstFor by remember { mutableStateOf<UbuntuRuntimeMode?>(null) }

    fun desktopActive(): Boolean =
        DesktopSessionQuery.current(context, DesktopLauncher.uiState.value) != null ||
            DesktopLauncher.isSessionActive()

    fun applySwitch(target: UbuntuRuntimeMode) {
        UbuntuRuntimeSwitch.apply(app, target)
        message = null
        messageIsError = false
        // Point at Home when the chosen install doesn't exist yet (chroot: su, off main).
        scope.launch {
            val check = withContext(Dispatchers.IO) { UbuntuInstallProbe.probe(app, target) }
            if (UbuntuRuntime.mode(app) != target) return@launch
            if (check == UbuntuInstallCheck.NOT_INSTALLED) {
                message = app.getString(
                    if (target == UbuntuRuntimeMode.CHROOT) R.string.settings_runtime_chroot_missing
                    else R.string.settings_runtime_proot_missing
                )
            }
        }
    }

    /** Root probe (chroot only) and switch; the desktop is already known to be stopped. */
    fun proceed(target: UbuntuRuntimeMode) {
        if (target == UbuntuRuntimeMode.PROOT) {
            applySwitch(target)
            return
        }
        checking = true
        message = null
        RootShell.probeRootAvailable { ok ->
            checking = false
            when (UbuntuRuntimeSwitch.decide(UbuntuRuntime.mode(app), target, desktopActive = false, rootAvailable = ok)) {
                RuntimeSwitchDecision.SWITCH -> applySwitch(target)
                RuntimeSwitchDecision.NO_ROOT -> {
                    message = app.getString(R.string.settings_runtime_no_root)
                    messageIsError = true
                }
                else -> Unit
            }
        }
    }

    fun request(target: UbuntuRuntimeMode) {
        if (target == runtime || checking) return
        if (desktopActive()) stopFirstFor = target else proceed(target)
    }

    UbuntuRuntimeCard(
        state = UbuntuRuntimeUi(
            selected = runtime,
            checking = checking,
            message = message,
            messageIsError = messageIsError,
        ),
        onSelect = ::request
    )

    stopFirstFor?.let { target ->
        val targetName = stringResource(
            if (target == UbuntuRuntimeMode.CHROOT) R.string.home_other_ubuntu_chroot
            else R.string.home_other_ubuntu_proot
        )
        AlertDialog(
            onDismissRequest = { stopFirstFor = null },
            title = { Text(stringResource(R.string.settings_runtime_stop_title)) },
            text = { Text(stringResource(R.string.settings_runtime_stop_body, targetName)) },
            confirmButton = {
                TextButton(onClick = {
                    stopFirstFor = null
                    val running = DesktopSessionQuery.current(context, DesktopLauncher.uiState.value)
                    val id = running?.distroId ?: DesktopLauncher.uiState.value.distroId ?: runtime.distroId
                    // onDone may arrive on the stop script's reader thread.
                    val main = android.os.Handler(android.os.Looper.getMainLooper())
                    DesktopLauncher.stop(app, id) { main.post { proceed(target) } }
                }) { Text(stringResource(R.string.settings_runtime_stop_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { stopFirstFor = null }) {
                    Text(stringResource(R.string.settings_runtime_stop_cancel))
                }
            }
        )
    }
}

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Log.e("Settings", "Error opening URL", e)
    }
}

// ─── Settings family building blocks ──────────────────────────────────────────
// Shared by the Settings detail pages in this package (Terminal, X11, Audio, storage,
// distro, legacy Termux, troubleshooting). Rows in tiles, grouped by SectionLabel;
// tonal blocks only for real groups (a readout, a log).

/**
 * A Settings page: Material top app bar with back on the ground (a kachi tint once the
 * content scrolls under it) and, when [scroll] is true, a scrolling column for [content].
 * With [scroll] false the column fills the page and the caller scrolls (e.g. a LazyColumn).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    scroll: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = scheme.background,
        snackbarHost = snackbarHost,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Sharp.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = actions,
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = scheme.background,
                    scrolledContainerColor = scheme.surfaceContainer,
                    navigationIconContentColor = scheme.onBackground,
                    titleContentColor = scheme.onBackground,
                    actionIconContentColor = scheme.onSurfaceVariant,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
        ) {
            content()
            if (scroll) Spacer(Modifier.height(FluxSpacing.xxl))
        }
    }
}

/** A row that opens another page: leading Sharp icon, title, supporting text, chevron. */
@Composable
internal fun SettingsPageNavRow(
    icon: ImageVector?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
) {
    AkaRow(
        title = title,
        supporting = subtitle,
        icon = icon,
        iconTint = iconTint,
        enabled = enabled,
        onClick = onClick,
        modifier = modifier,
        trailing = {
            Icon(
                Icons.AutoMirrored.Sharp.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/** A row that opens a web page: the trailing mark says it leaves the app. */
@Composable
internal fun SettingsPageLinkRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supporting: String? = null,
) {
    AkaRow(
        title = title,
        supporting = supporting,
        icon = icon,
        onClick = onClick,
        modifier = modifier,
        trailing = {
            Icon(
                Icons.AutoMirrored.Sharp.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}

/**
 * A setting with a switch. The whole row toggles; [description] overrides what
 * accessibility services read for it (defaults to the row's own text).
 */
@Composable
internal fun SettingsPageSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    description: String? = null,
    trailingExtra: (@Composable RowScope.() -> Unit)? = null,
) {
    AkaRow(
        title = title,
        supporting = supporting,
        icon = icon,
        enabled = enabled,
        modifier = modifier
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        trailing = {
            trailingExtra?.invoke(this)
            FluxSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
        },
    )
}

/** An explanatory paragraph under the top bar or a section label. */
@Composable
internal fun SettingsPageNote(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm),
    )
}

/**
 * A note that needs attention (data that outlives the app, a missing tool): printed in the
 * held ink with a warning glyph, never colour alone.
 */
@Composable
internal fun SettingsPageAttention(
    text: String,
    modifier: Modifier = Modifier,
    failed: Boolean = false,
) {
    val ink = if (failed) AkaTheme.inks.failed else AkaTheme.inks.held
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Sharp.Warning,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(FluxSpacing.md))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * A tile for a real group (a readout with its own controls, a log). Content gets the block's
 * inner padding; rows inside lie flat ([LocalInTile]).
 */
@Composable
internal fun SettingsPageBlock(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(FluxSpacing.lg),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm)
            .akaTile(MaterialTheme.shapes.large)
            .padding(contentPadding),
    ) {
        CompositionLocalProvider(LocalInTile provides true) { content() }
    }
}

/** A state told by mark form and ink, then words: replaces the old all-caps badges. */
@Composable
internal fun SettingsPageStatus(
    text: String,
    state: MarkState,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        StatusMark(state = state)
        Spacer(Modifier.width(FluxSpacing.sm))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = if (state == MarkState.IDLE) MaterialTheme.colorScheme.onSurfaceVariant else markColor(state),
        )
    }
}

/** Label on the start, a value (text or a status) on the end: a line of a readout. */
@Composable
internal fun SettingsPageMetaRow(
    label: String,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FluxSpacing.touch)
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(FluxSpacing.md))
        trailing()
    }
}

/** A measured figure (a size, a count): set in mono at headline size, its unit beside it. */
@Composable
internal fun SettingsPageFigure(
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    dimmed: Boolean = false,
) {
    val figure = MaterialTheme.typography.headlineMedium
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            value,
            style = CodeStyle.copy(fontSize = figure.fontSize, lineHeight = figure.lineHeight),
            color = if (dimmed) color.copy(alpha = 0.72f) else color,
        )
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(FluxSpacing.xs))
            Text(
                unit,
                style = CodeStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = FluxSpacing.xxs),
            )
        }
    }
}

/** A secondary action: a kon container with the ai line (error ink when [danger]), 48dp tall. */
@Composable
internal fun SettingsPageSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    danger: Boolean = false,
) {
    val ink = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val inks = AkaTheme.inks
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = inks.tile, contentColor = ink),
        border = BorderStroke(
            1.dp,
            when {
                !enabled -> inks.tileEdge
                danger -> MaterialTheme.colorScheme.error
                else -> inks.controlLine
            },
        ),
        modifier = modifier.heightIn(min = FluxSpacing.touch),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(FluxSpacing.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2)
    }
}

/** A destructive action, filled in the error ink. Always behind a confirming dialog. */
@Composable
internal fun SettingsPageDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        modifier = modifier.heightIn(min = FluxSpacing.touch),
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(FluxSpacing.sm))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2)
    }
}

/** Usage printed flat: a filled run of [color] on the container ink, no gradient. */
@Composable
internal fun SettingsPageUsageBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary,
) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(FluxSpacing.sm)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.extraSmall),
    ) {
        if (f > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(f)
                    .height(FluxSpacing.sm)
                    .background(color, MaterialTheme.shapes.extraSmall),
            )
        }
    }
}
