package com.ivarna.fluxlinux.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.HelpOutline
import androidx.compose.material.icons.automirrored.sharp.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.sharp.OpenInNew
import androidx.compose.material.icons.sharp.Build
import androidx.compose.material.icons.sharp.DesktopWindows
import androidx.compose.material.icons.sharp.Download
import androidx.compose.material.icons.sharp.Folder
import androidx.compose.material.icons.sharp.FolderOpen
import androidx.compose.material.icons.sharp.PlayArrow
import androidx.compose.material.icons.sharp.Refresh
import androidx.compose.material.icons.sharp.SdStorage
import androidx.compose.material.icons.sharp.Settings
import androidx.compose.material.icons.sharp.Stop
import androidx.compose.material.icons.sharp.StopCircle
import androidx.compose.material.icons.sharp.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.utils.UbuntuRuntimeMode
import com.ivarna.fluxlinux.ui.components.aka.MarkState
import com.ivarna.fluxlinux.ui.components.aka.AkaButton
import com.ivarna.fluxlinux.ui.components.aka.AkaButtonKind
import com.ivarna.fluxlinux.ui.components.aka.AkaRow
import com.ivarna.fluxlinux.ui.components.aka.akaTile
import com.ivarna.fluxlinux.ui.theme.horizon
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import com.ivarna.fluxlinux.ui.components.aka.SectionLabel
import com.ivarna.fluxlinux.ui.components.aka.StatusMark
import com.ivarna.fluxlinux.ui.components.aka.markColor
import com.ivarna.fluxlinux.ui.theme.AkaMotion
import com.ivarna.fluxlinux.ui.theme.AkaTheme
import com.ivarna.fluxlinux.ui.theme.CodeStyle
import com.ivarna.fluxlinux.ui.theme.FluxSpacing

// Sizes local to the dashboard; spacing comes from FluxSpacing, radii from MaterialTheme.shapes.
private val LogoSize = 40.dp
private val RowIcon = 22.dp
private val ButtonIcon = 18.dp
private val ChipIcon = 14.dp
private val HeaderMark = 12.dp
private val RowMark = 12.dp
private val RowMinHeight = 56.dp

/**
 * The active Ubuntu install as the dashboard header shows it: whether Ubuntu itself is usable.
 * What the optional desktop is doing is [DesktopAddOnUi]'s business, never this.
 */
enum class UbuntuStatus {
    /** Chroot mode: root / install detection still running off the main thread. */
    CHECKING,

    /** Chroot mode without root access: the install can't be checked or started. */
    NEEDS_ROOT,
    NOT_INSTALLED,

    /** Installed and usable: the terminal works, whatever the desktop is doing. */
    READY,
}

/** The installed desktop's session on the active install. */
enum class DesktopRun { STOPPED, STARTING, RUNNING }

/** The optional XFCE desktop as the dashboard's Desktop section shows it. */
@Immutable
sealed interface DesktopAddOnUi {
    /** Detection hasn't answered (e.g. the chroot's su probe) and nothing stored says installed. */
    data object Checking : DesktopAddOnUi

    data object NotInstalled : DesktopAddOnUi

    /** The add-on install is running ([InstallController] kind DESKTOP_ADD_ON); [percent] is 0..100. */
    data class Installing(val percent: Int, val phaseLabel: String, val detail: String) : DesktopAddOnUi

    data class Failed(val message: String?) : DesktopAddOnUi

    /**
     * Installed, with its session's [run] state. [otherRunning] names another install whose
     * desktop runs; it is set only while this one is [DesktopRun.STOPPED].
     */
    data class Installed(val run: DesktopRun, val otherRunning: String? = null) : DesktopAddOnUi
}

@Immutable
data class SharedStorageUi(
    /** False on builds that cannot request all-files access (Play flavor on Android 11+). */
    val supported: Boolean,
    val granted: Boolean,
    val linksPresent: Boolean,
    val linkNames: List<String>,
    val creatingLinks: Boolean = false
)

@Immutable
data class UbuntuDashboardState(
    val status: UbuntuStatus,
    /** The Desktop section; shown only while [status] is [UbuntuStatus.READY]. */
    val desktop: DesktopAddOnUi,
    val storage: SharedStorageUi,
    /** Which Ubuntu install the dashboard drives (PRoot `ubuntu` or chroot `ubuntu_chroot`). */
    val runtime: UbuntuRuntimeMode = UbuntuRuntimeMode.PROOT,
    /** The other runtime's install exists and no desktop runs or starts: offer the quick switch. */
    val canSwitchRuntime: Boolean = false,
)

/** Every user action on the dashboard. Stateless leaves call these; HomeScreen wires them. */
@Immutable
class UbuntuDashboardActions(
    val onStartDesktop: () -> Unit = {},
    val onOpenDesktop: () -> Unit = {},
    val onStopDesktop: () -> Unit = {},
    /** Asks to install the optional desktop (HomeScreen confirms first). */
    val onInstallDesktop: () -> Unit = {},
    val onCancelDesktopInstall: () -> Unit = {},
    val onDesktopInstallLog: () -> Unit = {},
    val onSetUpUbuntu: () -> Unit = {},
    val onFiles: () -> Unit = {},
    val onFilesystem: () -> Unit = {},
    val onAllowStorage: () -> Unit = {},
    val onCreateLinks: () -> Unit = {},
    /** The desktop's start/stop log. */
    val onViewLog: () -> Unit = {},
    val onStopEverything: () -> Unit = {},
    val onRepairHost: () -> Unit = {},
    val onRerunSetup: () -> Unit = {},
    val onUbuntuSettings: () -> Unit = {},
    val onTroubleshooting: () -> Unit = {},
    /** Switch to the other Ubuntu runtime (PRoot <-> chroot). */
    val onSwitchRuntime: () -> Unit = {},
    /** Chroot mode without root: probe root again. */
    val onRetryRoot: () -> Unit = {},
)

/**
 * The Ubuntu overview, reached from the terminal drawer. Stateless: callers own state and
 * scrolling. The terminal itself is not repeated here: Back (or the top bar arrow) returns to it.
 *
 * One card carries the install's state (its horizon risen once Ubuntu is ready); below it the
 * Desktop and Shared storage cards and the Files and Recovery rows, each in its own tile.
 */
@Composable
fun UbuntuDashboard(
    state: UbuntuDashboardState,
    actions: UbuntuDashboardActions,
    modifier: Modifier = Modifier
) {
    val ready = state.status == UbuntuStatus.READY
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = FluxSpacing.sm),
    ) {
        UbuntuHeader(
            status = state.status,
            runtime = state.runtime,
            canSwitchRuntime = state.canSwitchRuntime,
            actions = actions,
            modifier = Modifier.padding(horizontal = FluxSpacing.lg),
        )

        if (ready) {
            // No group label: the panel's own "XFCE desktop" title heads it.
            Spacer(Modifier.height(FluxSpacing.lg))
            DesktopPanel(desktop = state.desktop, actions = actions)

            GroupLabel(stringResource(R.string.home_section_files))
            NavRow(
                Icons.Sharp.Folder,
                stringResource(R.string.home_files_home),
                stringResource(R.string.home_files_home_sub),
                actions.onFiles
            )
            NavRow(
                Icons.Sharp.FolderOpen,
                stringResource(R.string.home_quick_filesystem),
                stringResource(R.string.home_files_system_sub),
                actions.onFilesystem
            )

            SharedStorageCard(
                storage = state.storage,
                onAllow = actions.onAllowStorage,
                onCreateLinks = actions.onCreateLinks
            )
        }

        RecoverySection(installed = ready, actions = actions)

        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

// ─── Header: the status card ────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UbuntuHeader(
    status: UbuntuStatus,
    runtime: UbuntuRuntimeMode,
    canSwitchRuntime: Boolean,
    actions: UbuntuDashboardActions,
    modifier: Modifier = Modifier,
) {
    val inks = AkaTheme.inks
    val chroot = runtime == UbuntuRuntimeMode.CHROOT
    val body = when (status) {
        UbuntuStatus.CHECKING -> stringResource(R.string.home_hero_checking)
        UbuntuStatus.NEEDS_ROOT -> stringResource(R.string.home_hero_needs_root)
        UbuntuStatus.NOT_INSTALLED ->
            stringResource(if (chroot) R.string.home_hero_not_installed_chroot else R.string.home_hero_not_installed)
        UbuntuStatus.READY -> null
    }
    val shape = MaterialTheme.shapes.large
    // A ready install is light on the horizon: the line rises along the card's bottom edge.
    val risen by animateFloatAsState(
        if (status == UbuntuStatus.READY) 1f else 0f,
        AkaMotion.rise(),
        label = "headerHorizon",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .akaTile(shape)
            .horizon(inks.horizon, inks.horizonGlow, risen = { risen })
            .animateContentSize(AkaMotion.standard()),
    ) {
        Column(Modifier.fillMaxWidth().padding(FluxSpacing.lg)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_ubuntu_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = inks.onChrome,
                        modifier = Modifier.semantics { heading() }
                    )
                    FlowRow(
                        modifier = Modifier.padding(top = FluxSpacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(FluxSpacing.md),
                    ) {
                        StatusLabel(status, Modifier.align(Alignment.CenterVertically))
                        RuntimeChip(
                            runtime = runtime,
                            canSwitch = canSwitchRuntime,
                            onSwitch = actions.onSwitchRuntime,
                            modifier = Modifier.align(Alignment.CenterVertically),
                        )
                    }
                }
                Spacer(Modifier.width(FluxSpacing.md))
                Image(
                    painter = painterResource(R.drawable.distro_ubuntu),
                    contentDescription = stringResource(R.string.home_ubuntu_logo),
                    modifier = Modifier.size(LogoSize)
                )
            }
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = inks.onChromeMuted,
                    modifier = Modifier.padding(top = FluxSpacing.md)
                )
            }
            when (status) {
                // Stacked: side by side, "Check root again" wraps at 360dp.
                UbuntuStatus.NEEDS_ROOT -> Column(
                    modifier = Modifier.padding(top = FluxSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
                ) {
                    AkaButton(
                        text = stringResource(R.string.home_action_retry_root),
                        icon = Icons.Sharp.Refresh,
                        onClick = actions.onRetryRoot,
                        modifier = Modifier.fillMaxWidth()
                    )
                    AkaButton(
                        text = stringResource(R.string.home_action_use_proot),
                        onClick = actions.onSwitchRuntime,
                        kind = AkaButtonKind.SECONDARY,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                UbuntuStatus.NOT_INSTALLED -> AkaButton(
                    text = stringResource(R.string.home_action_set_up),
                    icon = Icons.Sharp.PlayArrow,
                    onClick = actions.onSetUpUbuntu,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = FluxSpacing.lg)
                )
                UbuntuStatus.CHECKING, UbuntuStatus.READY -> Unit
            }
        }
    }
}

/** The install's state on the card: a mark (form first) and the words for it. */
@Composable
private fun StatusLabel(status: UbuntuStatus, modifier: Modifier = Modifier) {
    val (mark, label) = when (status) {
        UbuntuStatus.CHECKING -> MarkState.WORKING to stringResource(R.string.home_status_checking)
        UbuntuStatus.NEEDS_ROOT -> MarkState.FAILED to stringResource(R.string.home_status_needs_root)
        UbuntuStatus.NOT_INSTALLED -> MarkState.IDLE to stringResource(R.string.home_status_not_installed)
        UbuntuStatus.READY -> MarkState.IDLE to stringResource(R.string.home_status_ready)
    }
    Row(
        modifier = modifier.padding(vertical = FluxSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusMark(state = mark, size = HeaderMark, color = plateMarkColor(mark))
        Spacer(Modifier.width(FluxSpacing.sm))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = AkaTheme.inks.onChrome)
    }
}

/** State inks on the header card: the scheme's own state inks hold on kon. */
@Composable
private fun plateMarkColor(state: MarkState): Color =
    if (state == MarkState.IDLE) AkaTheme.inks.onChromeMuted else markColor(state)

/**
 * Which Ubuntu install the dashboard drives. Tappable (with a swap icon) only when the other
 * runtime's install exists and nothing is running.
 */
@Composable
private fun RuntimeChip(
    runtime: UbuntuRuntimeMode,
    canSwitch: Boolean,
    onSwitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inks = AkaTheme.inks
    val chroot = runtime == UbuntuRuntimeMode.CHROOT
    val label = stringResource(if (chroot) R.string.home_runtime_chroot else R.string.home_runtime_proot)
    val other = stringResource(if (chroot) R.string.home_runtime_proot else R.string.home_runtime_chroot)
    val switchLabel = stringResource(R.string.home_runtime_switch_to, other)
    val chipShape = MaterialTheme.shapes.extraSmall
    Box(
        modifier = modifier.then(
            if (canSwitch) {
                Modifier
                    .minimumInteractiveComponentSize()
                    .clip(chipShape)
                    .clickable(role = Role.Button, onClickLabel = switchLabel, onClick = onSwitch)
            } else {
                Modifier
            }
        ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(chipShape)
                .background(inks.tilePressed)
                .border(1.dp, if (canSwitch) inks.controlLine else inks.tileEdge, chipShape)
                .padding(horizontal = FluxSpacing.sm, vertical = FluxSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = inks.onChrome
            )
            if (canSwitch) {
                Spacer(Modifier.width(FluxSpacing.xs))
                Icon(
                    Icons.Sharp.SwapHoriz,
                    contentDescription = null,
                    tint = inks.onChrome,
                    modifier = Modifier.size(ChipIcon)
                )
            }
        }
    }
}

// ─── Desktop ──────────────────────────────────────────────────────────────────

/**
 * The optional desktop as one card: its row plus its actions. The row's mark and the
 * subtitle's ink carry the state; a failed install keeps the failed ink and mark until the
 * state changes (Try again, or the desktop turns up installed). While detection runs the card
 * says so and offers nothing, so an installed desktop never flashes "Install desktop".
 */
@Composable
fun DesktopPanel(
    desktop: DesktopAddOnUi,
    actions: UbuntuDashboardActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val inks = AkaTheme.inks
    val subtitle = when (desktop) {
        DesktopAddOnUi.Checking -> stringResource(R.string.home_desktop_checking)
        DesktopAddOnUi.NotInstalled -> stringResource(R.string.home_desktop_not_installed)
        is DesktopAddOnUi.Installing -> desktop.phaseLabel.ifBlank {
            stringResource(R.string.home_desktop_installing_starting)
        }
        is DesktopAddOnUi.Failed -> stringResource(R.string.home_desktop_install_failed)
        is DesktopAddOnUi.Installed -> when {
            desktop.run == DesktopRun.RUNNING -> stringResource(R.string.home_hero_running)
            desktop.run == DesktopRun.STARTING -> stringResource(R.string.home_hero_starting)
            desktop.otherRunning != null -> stringResource(R.string.home_hero_other_running, desktop.otherRunning)
            else -> stringResource(R.string.home_hero_stopped)
        }
    }
    val mark: MarkState? = when (desktop) {
        DesktopAddOnUi.NotInstalled -> null
        DesktopAddOnUi.Checking, is DesktopAddOnUi.Installing -> MarkState.WORKING
        is DesktopAddOnUi.Failed -> MarkState.FAILED
        is DesktopAddOnUi.Installed -> when (desktop.run) {
            DesktopRun.RUNNING -> MarkState.RUNNING
            DesktopRun.STARTING -> MarkState.WORKING
            DesktopRun.STOPPED -> MarkState.IDLE
        }
    }
    val subtitleColor = when (mark) {
        MarkState.FAILED -> inks.failed
        MarkState.RUNNING -> inks.running
        else -> colors.onSurfaceVariant
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FluxSpacing.lg)
            .akaTile(MaterialTheme.shapes.large)
            .animateContentSize(AkaMotion.standard())
            // Checking has no body under its row; the row's own padding closes the card.
            .then(if (desktop == DesktopAddOnUi.Checking) Modifier else Modifier.padding(bottom = FluxSpacing.lg))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = RowMinHeight)
                .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Sharp.DesktopWindows,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(RowIcon)
            )
            Spacer(Modifier.width(FluxSpacing.lg))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (desktop is DesktopAddOnUi.Installing) R.string.home_desktop_installing
                        else R.string.home_desktop_title
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = subtitleColor
                )
            }
            if (mark != null) {
                Spacer(Modifier.width(FluxSpacing.md))
                // The subtitle says the state in words; the mark is its printed form.
                StatusMark(state = mark, size = RowMark, color = markColor(mark))
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = FluxSpacing.lg)) {
            when (desktop) {
                // Nothing to act on until detection answers; the row says what is happening.
                DesktopAddOnUi.Checking -> Unit
                DesktopAddOnUi.NotInstalled -> {
                    Text(
                        text = stringResource(R.string.home_desktop_value),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    AkaButton(
                        text = stringResource(R.string.home_desktop_install),
                        icon = Icons.Sharp.Download,
                        onClick = actions.onInstallDesktop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = FluxSpacing.lg)
                    )
                }
                is DesktopAddOnUi.Installing -> {
                    val percent = desktop.percent
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        color = markColor(MarkState.WORKING),
                        trackColor = colors.surfaceContainerHighest,
                        strokeCap = StrokeCap.Butt,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.extraSmall),
                    )
                    Row(
                        modifier = Modifier.padding(top = FluxSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // apt's own output line: program text, so it is set in the code face.
                        Text(
                            text = desktop.detail,
                            style = CodeStyle,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(R.string.home_desktop_percent, percent),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.onSurface,
                            modifier = Modifier.padding(start = FluxSpacing.sm)
                        )
                    }
                    Text(
                        text = stringResource(R.string.home_desktop_installing_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = FluxSpacing.md)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = FluxSpacing.md),
                        horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
                    ) {
                        TextAction(
                            stringResource(R.string.home_desktop_show_log),
                            actions.onDesktopInstallLog,
                            Modifier.weight(1f)
                        )
                        TextAction(
                            stringResource(R.string.home_desktop_cancel),
                            actions.onCancelDesktopInstall,
                            Modifier.weight(1f)
                        )
                    }
                }
                is DesktopAddOnUi.Failed -> {
                    desktop.message?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = FluxSpacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
                    ) {
                        AkaButton(
                            text = stringResource(R.string.home_desktop_retry),
                            icon = Icons.Sharp.Refresh,
                            onClick = actions.onInstallDesktop,
                            contentPadding = CompactButtonPadding,
                            modifier = Modifier.weight(1f)
                        )
                        SecondaryButton(
                            label = stringResource(R.string.home_desktop_show_log),
                            icon = null,
                            onClick = actions.onDesktopInstallLog,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is DesktopAddOnUi.Installed -> {
                    when (desktop.run) {
                        DesktopRun.STOPPED -> AkaButton(
                            text = stringResource(R.string.home_action_start_desktop),
                            icon = Icons.Sharp.PlayArrow,
                            onClick = actions.onStartDesktop,
                            modifier = Modifier.fillMaxWidth()
                        )
                        DesktopRun.STARTING, DesktopRun.RUNNING -> Row(
                            horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm),
                        ) {
                            SecondaryButton(
                                label = stringResource(R.string.home_action_open_desktop),
                                icon = Icons.AutoMirrored.Sharp.OpenInNew,
                                onClick = actions.onOpenDesktop,
                                modifier = Modifier.weight(1.4f)
                            )
                            SecondaryButton(
                                label = stringResource(R.string.home_action_stop),
                                icon = Icons.Sharp.Stop,
                                onClick = actions.onStopDesktop,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    TextAction(
                        stringResource(R.string.home_recovery_view_log),
                        actions.onViewLog,
                        Modifier
                            .fillMaxWidth()
                            .padding(top = FluxSpacing.sm)
                    )
                }
            }
        }
    }
}

// ─── Shared storage ───────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SharedStorageCard(
    storage: SharedStorageUi,
    onAllow: () -> Unit,
    onCreateLinks: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = modifier.fillMaxWidth()) {
        GroupLabel(stringResource(R.string.home_storage_title))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FluxSpacing.lg)
                .akaTile(MaterialTheme.shapes.large)
                .padding(bottom = FluxSpacing.lg)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = RowMinHeight)
                    .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Sharp.SdStorage,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(RowIcon)
                )
                Spacer(Modifier.width(FluxSpacing.lg))
                Text(
                    text = when {
                        !storage.supported -> stringResource(R.string.home_storage_unsupported)
                        storage.granted -> stringResource(R.string.home_storage_granted)
                        else -> stringResource(R.string.home_storage_denied)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }
            if (!storage.supported) return@Column

            Column(Modifier.fillMaxWidth().padding(horizontal = FluxSpacing.lg)) {
                if (!storage.granted) {
                    AkaButton(
                        text = stringResource(R.string.home_storage_allow),
                        onClick = onAllow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = FluxSpacing.sm)
                    )
                }

                Text(
                    text = if (storage.linksPresent) {
                        stringResource(R.string.home_storage_links_ready)
                    } else {
                        stringResource(R.string.home_storage_links_missing)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = FluxSpacing.sm)
                )
                FlowRow(
                    modifier = Modifier.padding(top = FluxSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
                ) {
                    storage.linkNames.forEach { name -> PathChip(name) }
                }
                if (!storage.linksPresent) {
                    SecondaryButton(
                        label = if (storage.creatingLinks) {
                            stringResource(R.string.home_storage_creating_links)
                        } else {
                            stringResource(R.string.home_storage_create_links)
                        },
                        icon = null,
                        enabled = !storage.creatingLinks,
                        onClick = onCreateLinks,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = FluxSpacing.md)
                    )
                }
            }
        }
    }
}

/** A `~/storage` link name: a path, so it is set in the code face on a konjō tint. */
@Composable
private fun PathChip(name: String) {
    Text(
        text = name,
        style = CodeStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = FluxSpacing.sm, vertical = FluxSpacing.xxs)
    )
}

// ─── Recovery ─────────────────────────────────────────────────────────────────

@Composable
fun RecoverySection(
    installed: Boolean,
    actions: UbuntuDashboardActions,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        GroupLabel(stringResource(R.string.home_recovery_title))
        NavRow(
            Icons.Sharp.StopCircle,
            stringResource(R.string.home_recovery_stop_all),
            stringResource(R.string.home_recovery_stop_all_sub),
            actions.onStopEverything
        )
        NavRow(
            Icons.Sharp.Build,
            stringResource(R.string.home_recovery_repair),
            stringResource(R.string.home_recovery_repair_sub),
            actions.onRepairHost
        )
        if (installed) {
            NavRow(
                Icons.Sharp.Refresh,
                stringResource(R.string.home_recovery_rerun),
                stringResource(R.string.home_recovery_rerun_sub),
                actions.onRerunSetup
            )
            NavRow(
                Icons.Sharp.Settings,
                stringResource(R.string.home_recovery_settings),
                stringResource(R.string.home_recovery_settings_sub),
                actions.onUbuntuSettings
            )
        }
        NavRow(
            Icons.AutoMirrored.Sharp.HelpOutline,
            stringResource(R.string.home_recovery_troubleshooting),
            stringResource(R.string.home_recovery_troubleshooting_sub),
            actions.onTroubleshooting
        )
    }
}

// ─── Shared bits ──────────────────────────────────────────────────────────────

private val CompactButtonPadding = PaddingValues(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md)

/** A group's label, announced as a heading. */
@Composable
private fun GroupLabel(text: String) {
    SectionLabel(text = text, modifier = Modifier.semantics { heading() })
}

/** A navigation row in its own tile (the whole tile is the button). */
@Composable
private fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    AkaRow(
        title = title,
        supporting = subtitle,
        icon = icon,
        onClick = onClick,
        trailing = {
            Icon(
                Icons.AutoMirrored.Sharp.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
    )
}

/** A minor action (logs, cancel): still a container, never bare text; callers size it. */
@Composable
private fun TextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AkaButton(
        text = label,
        onClick = onClick,
        kind = AkaButtonKind.SECONDARY,
        contentPadding = CompactButtonPadding,
        modifier = modifier,
    )
}

/** Secondary actions: a kon container with the ai line, so akane stays with the one primary. */
@Composable
private fun SecondaryButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    AkaButton(
        text = label,
        icon = icon,
        onClick = onClick,
        enabled = enabled,
        kind = AkaButtonKind.SECONDARY,
        contentPadding = CompactButtonPadding,
        modifier = modifier,
    )
}
