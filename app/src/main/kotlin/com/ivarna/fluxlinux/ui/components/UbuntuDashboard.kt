package com.ivarna.fluxlinux.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
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
import com.ivarna.fluxlinux.ui.components.aka.PlatedButton
import com.ivarna.fluxlinux.ui.components.aka.SectionLabel
import com.ivarna.fluxlinux.ui.components.aka.StatusMark
import com.ivarna.fluxlinux.ui.components.aka.markColor
import com.ivarna.fluxlinux.ui.theme.AkaMotion
import com.ivarna.fluxlinux.ui.theme.AkaTheme
import com.ivarna.fluxlinux.ui.theme.CodeStyle
import com.ivarna.fluxlinux.ui.theme.FluxSpacing
import com.ivarna.fluxlinux.ui.theme.Riso
import com.ivarna.fluxlinux.ui.theme.risoGrain

// Sizes local to the dashboard; spacing comes from FluxSpacing, radii from MaterialTheme.shapes.
private val LogoSize = 40.dp
private val RowIcon = 22.dp
private val ButtonIcon = 18.dp
private val ChipIcon = 14.dp
private val HeaderMark = 12.dp
private val RowMark = 12.dp
private val RowMinHeight = 56.dp

/**
 * The active Ubuntu install as the dashboard header shows it. STOPPED / STARTING / RUNNING mean
 * Ubuntu is ready; they also describe the optional desktop once it is installed.
 */
enum class UbuntuStatus {
    /** Chroot mode: root / install detection still running off the main thread. */
    CHECKING,

    /** Chroot mode without root access: the install can't be checked or started. */
    NEEDS_ROOT,
    NOT_INSTALLED,
    STOPPED,
    STARTING,
    RUNNING,
}

/** Ubuntu is installed and usable (the terminal works), whatever the desktop is doing. */
val UbuntuStatus.isReady: Boolean
    get() = this == UbuntuStatus.STOPPED || this == UbuntuStatus.STARTING || this == UbuntuStatus.RUNNING

/** The optional XFCE desktop as the dashboard's Desktop section shows it. */
@Immutable
sealed interface DesktopAddOnUi {
    data object NotInstalled : DesktopAddOnUi

    /** The add-on install is running ([InstallController] kind DESKTOP_ADD_ON). */
    data class Installing(val percent: Int, val phaseLabel: String, val detail: String) : DesktopAddOnUi

    data class Failed(val message: String?) : DesktopAddOnUi

    /** Installed; [UbuntuDashboardState.status] says whether it is stopped, starting or running. */
    data object Installed : DesktopAddOnUi
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
    /** Name of another distro whose desktop is running, if any. */
    val otherDesktopRunning: String? = null,
    val storage: SharedStorageUi,
    /** Which Ubuntu install the dashboard drives (PRoot `ubuntu` or chroot `ubuntu_chroot`). */
    val runtime: UbuntuRuntimeMode = UbuntuRuntimeMode.PROOT,
    /** The other runtime's install exists and nothing is running: offer the quick switch. */
    val canSwitchRuntime: Boolean = false,
    val desktop: DesktopAddOnUi = DesktopAddOnUi.Installed,
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
 * One printed plate (the Federal shell field) carries the install's state; below it the
 * Desktop, Files, Shared storage and Recovery groups sit as full-width rows on the stock.
 */
@Composable
fun UbuntuDashboard(
    state: UbuntuDashboardState,
    actions: UbuntuDashboardActions,
    modifier: Modifier = Modifier
) {
    val ready = state.status.isReady
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
            DesktopPanel(
                desktop = state.desktop,
                status = state.status,
                otherDesktopRunning = state.otherDesktopRunning,
                actions = actions,
            )

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

// ─── Header: the printed status plate ────────────────────────────────────────

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
        else -> null
    }
    val shape = MaterialTheme.shapes.large
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .animateContentSize(AkaMotion.standard()),
    ) {
        // The field and its grain print under the content, never over the type.
        Box(
            Modifier
                .matchParentSize()
                .background(inks.shellField)
                .risoGrain(inks.grainAlpha)
        )
        Column(Modifier.fillMaxWidth().padding(FluxSpacing.lg)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_ubuntu_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = inks.onShellField,
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
                    color = inks.onShellFieldMuted,
                    modifier = Modifier.padding(top = FluxSpacing.md)
                )
            }
            when (status) {
                // Stacked: side by side, "Check root again" wraps at 360dp.
                UbuntuStatus.NEEDS_ROOT -> Column(
                    modifier = Modifier.padding(top = FluxSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
                ) {
                    PlatedButton(
                        text = stringResource(R.string.home_action_retry_root),
                        icon = Icons.Sharp.Refresh,
                        onClick = actions.onRetryRoot,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = actions.onSwitchRuntime,
                        shape = MaterialTheme.shapes.small,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = inks.onShellField),
                        border = BorderStroke(1.dp, inks.onShellFieldMuted),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = FluxSpacing.touch)
                    ) {
                        ButtonContent(stringResource(R.string.home_action_use_proot), icon = null)
                    }
                }
                UbuntuStatus.NOT_INSTALLED -> PlatedButton(
                    text = stringResource(R.string.home_action_set_up),
                    icon = Icons.Sharp.PlayArrow,
                    onClick = actions.onSetUpUbuntu,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = FluxSpacing.lg)
                )
                else -> Unit
            }
        }
    }
}

/** The install's state on the plate: a mark (form first) and the words for it. */
@Composable
private fun StatusLabel(status: UbuntuStatus, modifier: Modifier = Modifier) {
    val (mark, label) = when (status) {
        UbuntuStatus.CHECKING -> MarkState.WORKING to stringResource(R.string.home_status_checking)
        UbuntuStatus.NEEDS_ROOT -> MarkState.FAILED to stringResource(R.string.home_status_needs_root)
        UbuntuStatus.NOT_INSTALLED -> MarkState.IDLE to stringResource(R.string.home_status_not_installed)
        UbuntuStatus.STOPPED -> MarkState.IDLE to stringResource(R.string.home_status_ready)
        UbuntuStatus.STARTING -> MarkState.WORKING to stringResource(R.string.ubuntu_aka_status_starting)
        UbuntuStatus.RUNNING -> MarkState.RUNNING to stringResource(R.string.ubuntu_aka_status_running)
    }
    Row(
        modifier = modifier.padding(vertical = FluxSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusMark(state = mark, size = HeaderMark, color = plateMarkColor(mark))
        Spacer(Modifier.width(FluxSpacing.sm))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = AkaTheme.inks.onShellField)
    }
}

/**
 * State inks on the Federal plate. The plate stays a dark printed field in both schemes, so its
 * marks use the inks as printed on dark stock (the light-stock state inks vanish on blue).
 */
@Composable
private fun plateMarkColor(state: MarkState): Color {
    val inks = AkaTheme.inks
    return when (state) {
        MarkState.RUNNING -> Riso.GreenTint
        MarkState.IDLE -> inks.onShellFieldMuted
        MarkState.WORKING -> inks.onShellField
        MarkState.HELD -> Riso.Yellow
        MarkState.FAILED -> Riso.Pink
    }
}

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
                .background(inks.shellFieldHigh)
                .padding(horizontal = FluxSpacing.sm, vertical = FluxSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = inks.onShellField
            )
            if (canSwitch) {
                Spacer(Modifier.width(FluxSpacing.xs))
                Icon(
                    Icons.Sharp.SwapHoriz,
                    contentDescription = null,
                    tint = inks.onShellField,
                    modifier = Modifier.size(ChipIcon)
                )
            }
        }
    }
}

// ─── Desktop ──────────────────────────────────────────────────────────────────

/**
 * The optional desktop as one row plus its actions, on the stock. The row's mark and the
 * subtitle's ink carry the state; a failed install keeps the failed ink and mark until the
 * state changes (Try again, or the desktop turns up installed).
 */
@Composable
fun DesktopPanel(
    desktop: DesktopAddOnUi,
    status: UbuntuStatus,
    otherDesktopRunning: String?,
    actions: UbuntuDashboardActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val inks = AkaTheme.inks
    val subtitle = when (desktop) {
        DesktopAddOnUi.NotInstalled -> stringResource(R.string.home_desktop_not_installed)
        is DesktopAddOnUi.Installing -> desktop.phaseLabel.ifBlank {
            stringResource(R.string.home_desktop_installing_starting)
        }
        is DesktopAddOnUi.Failed -> stringResource(R.string.home_desktop_install_failed)
        DesktopAddOnUi.Installed -> when {
            otherDesktopRunning != null && status == UbuntuStatus.STOPPED ->
                stringResource(R.string.home_hero_other_running, otherDesktopRunning)
            status == UbuntuStatus.RUNNING -> stringResource(R.string.home_hero_running)
            status == UbuntuStatus.STARTING -> stringResource(R.string.home_hero_starting)
            else -> stringResource(R.string.home_hero_stopped)
        }
    }
    val mark: MarkState? = when (desktop) {
        DesktopAddOnUi.NotInstalled -> null
        is DesktopAddOnUi.Installing -> MarkState.WORKING
        is DesktopAddOnUi.Failed -> MarkState.FAILED
        DesktopAddOnUi.Installed -> when (status) {
            UbuntuStatus.RUNNING -> MarkState.RUNNING
            UbuntuStatus.STARTING -> MarkState.WORKING
            else -> MarkState.IDLE
        }
    }
    val subtitleColor = when (mark) {
        MarkState.FAILED -> inks.failed
        MarkState.RUNNING -> inks.running
        else -> colors.onSurfaceVariant
    }

    Column(modifier = modifier.fillMaxWidth().animateContentSize(AkaMotion.standard())) {
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
                DesktopAddOnUi.NotInstalled -> {
                    Text(
                        text = stringResource(R.string.home_desktop_value),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    PlatedButton(
                        text = stringResource(R.string.home_desktop_install),
                        icon = Icons.Sharp.Download,
                        onClick = actions.onInstallDesktop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = FluxSpacing.lg)
                    )
                }
                is DesktopAddOnUi.Installing -> {
                    val percent = desktop.percent.coerceIn(0, 100)
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
                            .padding(top = FluxSpacing.xs),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextAction(stringResource(R.string.home_desktop_show_log), actions.onDesktopInstallLog)
                        TextAction(stringResource(R.string.home_desktop_cancel), actions.onCancelDesktopInstall)
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
                        PlatedButton(
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
                DesktopAddOnUi.Installed -> {
                    when (status) {
                        UbuntuStatus.STOPPED -> PlatedButton(
                            text = stringResource(R.string.home_action_start_desktop),
                            icon = Icons.Sharp.PlayArrow,
                            onClick = actions.onStartDesktop,
                            modifier = Modifier.fillMaxWidth()
                        )
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm)) {
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
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = FluxSpacing.xs),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextAction(stringResource(R.string.home_recovery_view_log), actions.onViewLog)
                    }
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
                PlatedButton(
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

/** A `~/storage` link name: a path, so it is set in the code face on a printed tint. */
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

/** A group's label on the stock, announced as a heading. */
@Composable
private fun GroupLabel(text: String) {
    SectionLabel(text = text, modifier = Modifier.semantics { heading() })
}

/** A full-width navigation row on the stock (the whole row is the button). */
@Composable
private fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(RowIcon))
        Spacer(Modifier.width(FluxSpacing.lg))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(FluxSpacing.sm))
        Icon(
            Icons.AutoMirrored.Sharp.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant
        )
    }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
        modifier = Modifier.heightIn(min = FluxSpacing.touch)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

/** Secondary actions: outlined, in the text ink, so red stays with the one primary plate. */
@Composable
private fun SecondaryButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        modifier = modifier.heightIn(min = FluxSpacing.touch)
    ) {
        ButtonContent(label, icon)
    }
}

@Composable
private fun ButtonContent(label: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonIcon))
        Spacer(Modifier.width(FluxSpacing.sm))
    }
    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
