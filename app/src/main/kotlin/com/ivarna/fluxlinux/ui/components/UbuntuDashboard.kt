package com.ivarna.fluxlinux.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.ivarna.fluxlinux.ui.theme.FluxSpacing
import com.ivarna.fluxlinux.ui.theme.FluxSuccess

// Sizes local to the dashboard; spacing comes from FluxSpacing, radii from MaterialTheme.shapes.
private val LogoBox = 56.dp
private val LogoSize = 40.dp
private val RowIcon = 24.dp
private val ButtonIcon = 18.dp
private val StatusDot = 8.dp
private const val STATE_ANIM_MS = 200

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
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(FluxSpacing.lg)
    ) {
        UbuntuHeader(
            status = state.status,
            runtime = state.runtime,
            canSwitchRuntime = state.canSwitchRuntime,
            actions = actions,
        )

        if (ready) {
            Section(title = stringResource(R.string.home_section_desktop)) {
                DesktopPanel(
                    desktop = state.desktop,
                    status = state.status,
                    otherDesktopRunning = state.otherDesktopRunning,
                    actions = actions,
                )
            }
            Section(title = stringResource(R.string.home_section_files)) {
                Panel {
                    Column(Modifier.padding(vertical = FluxSpacing.xs)) {
                        NavRow(
                            Icons.Filled.Folder,
                            stringResource(R.string.home_files_home),
                            stringResource(R.string.home_files_home_sub),
                            actions.onFiles
                        )
                        NavRow(
                            Icons.Filled.FolderOpen,
                            stringResource(R.string.home_quick_filesystem),
                            stringResource(R.string.home_files_system_sub),
                            actions.onFilesystem
                        )
                    }
                }
                SharedStorageCard(
                    storage = state.storage,
                    onAllow = actions.onAllowStorage,
                    onCreateLinks = actions.onCreateLinks
                )
            }
        }

        RecoverySection(installed = ready, actions = actions)

        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

// ─── Header ───────────────────────────────────────────────────────────────────

@Composable
fun UbuntuHeader(
    status: UbuntuStatus,
    runtime: UbuntuRuntimeMode,
    canSwitchRuntime: Boolean,
    actions: UbuntuDashboardActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val chroot = runtime == UbuntuRuntimeMode.CHROOT
    val body = when (status) {
        UbuntuStatus.CHECKING -> stringResource(R.string.home_hero_checking)
        UbuntuStatus.NEEDS_ROOT -> stringResource(R.string.home_hero_needs_root)
        UbuntuStatus.NOT_INSTALLED ->
            stringResource(if (chroot) R.string.home_hero_not_installed_chroot else R.string.home_hero_not_installed)
        else -> null
    }
    Panel(modifier = modifier) {
        Column(Modifier.padding(FluxSpacing.lg).animateContentSize(tween(STATE_ANIM_MS))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(LogoBox)
                        .clip(MaterialTheme.shapes.medium)
                        .background(colors.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.distro_ubuntu),
                        contentDescription = stringResource(R.string.home_ubuntu_logo),
                        modifier = Modifier.size(LogoSize)
                    )
                }
                Spacer(Modifier.width(FluxSpacing.lg))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.home_ubuntu_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.onSurface
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusLabel(status)
                        Spacer(Modifier.width(FluxSpacing.sm))
                        RuntimeChip(
                            runtime = runtime,
                            canSwitch = canSwitchRuntime,
                            onSwitch = actions.onSwitchRuntime
                        )
                    }
                }
            }
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = FluxSpacing.md)
                )
            }
            when (status) {
                UbuntuStatus.CHECKING -> LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = FluxSpacing.md)
                        .clip(CircleShape),
                )
                // Stacked: side by side, "Check root again" wraps at 360dp.
                UbuntuStatus.NEEDS_ROOT -> Column(
                    modifier = Modifier.padding(top = FluxSpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
                ) {
                    PrimaryButton(
                        label = stringResource(R.string.home_action_retry_root),
                        icon = Icons.Filled.Refresh,
                        onClick = actions.onRetryRoot,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SecondaryButton(
                        label = stringResource(R.string.home_action_use_proot),
                        icon = null,
                        onClick = actions.onSwitchRuntime,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                UbuntuStatus.NOT_INSTALLED -> PrimaryButton(
                    label = stringResource(R.string.home_action_set_up),
                    icon = Icons.Filled.PlayArrow,
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

/** "● Ready" etc. Error states use the error colour; ready uses the success tint. */
@Composable
private fun StatusLabel(status: UbuntuStatus) {
    val colors = MaterialTheme.colorScheme
    val (label, tint) = when (status) {
        UbuntuStatus.CHECKING -> stringResource(R.string.home_status_checking) to colors.onSurfaceVariant
        UbuntuStatus.NEEDS_ROOT -> stringResource(R.string.home_status_needs_root) to colors.error
        UbuntuStatus.NOT_INSTALLED -> stringResource(R.string.home_status_not_installed) to colors.error
        else -> stringResource(R.string.home_status_ready) to FluxSuccess
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(StatusDot)
                .clip(CircleShape)
                .background(tint)
        )
        Spacer(Modifier.width(FluxSpacing.sm))
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = colors.onSurface)
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
) {
    val colors = MaterialTheme.colorScheme
    val chroot = runtime == UbuntuRuntimeMode.CHROOT
    val label = stringResource(if (chroot) R.string.home_runtime_chroot else R.string.home_runtime_proot)
    val other = stringResource(if (chroot) R.string.home_runtime_proot else R.string.home_runtime_chroot)
    val switchLabel = stringResource(R.string.home_runtime_switch_to, other)
    Box(
        modifier = if (canSwitch) {
            Modifier
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = switchLabel, onClick = onSwitch)
        } else {
            Modifier
        },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(colors.secondaryContainer)
                .padding(horizontal = FluxSpacing.sm + FluxSpacing.xxs, vertical = FluxSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSecondaryContainer
            )
            if (canSwitch) {
                Spacer(Modifier.width(FluxSpacing.xs))
                Icon(
                    Icons.Filled.SwapHoriz,
                    contentDescription = null,
                    tint = colors.onSecondaryContainer,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ─── Desktop ──────────────────────────────────────────────────────────────────

@Composable
fun DesktopPanel(
    desktop: DesktopAddOnUi,
    status: UbuntuStatus,
    otherDesktopRunning: String?,
    actions: UbuntuDashboardActions,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // A trailing text-button row already carries its own 48dp touch height.
    val endsWithTextAction = desktop is DesktopAddOnUi.Installing || desktop == DesktopAddOnUi.Installed
    Panel(modifier = modifier) {
        Column(
            Modifier
                .padding(
                    start = FluxSpacing.lg,
                    top = FluxSpacing.lg,
                    end = FluxSpacing.lg,
                    bottom = if (endsWithTextAction) FluxSpacing.xs else FluxSpacing.lg,
                )
                .animateContentSize(tween(STATE_ANIM_MS))
        ) {
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
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.DesktopWindows,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = FluxSpacing.xxs).size(RowIcon)
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
                        color = if (desktop is DesktopAddOnUi.Failed) colors.error else colors.onSurfaceVariant
                    )
                }
                if (desktop == DesktopAddOnUi.Installed && status == UbuntuStatus.RUNNING) {
                    Box(
                        Modifier
                            .padding(top = FluxSpacing.sm)
                            .size(StatusDot)
                            .clip(CircleShape)
                            .background(FluxSuccess)
                    )
                }
            }

            when (desktop) {
                DesktopAddOnUi.NotInstalled -> {
                    Text(
                        text = stringResource(R.string.home_desktop_value),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = FluxSpacing.md)
                    )
                    TonalButton(
                        label = stringResource(R.string.home_desktop_install),
                        icon = Icons.Filled.Download,
                        onClick = actions.onInstallDesktop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = FluxSpacing.lg)
                    )
                }
                is DesktopAddOnUi.Installing -> {
                    LinearProgressIndicator(
                        progress = { desktop.percent.coerceIn(0, 100) / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = FluxSpacing.lg)
                            .clip(CircleShape),
                    )
                    Row(
                        modifier = Modifier.padding(top = FluxSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = desktop.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(R.string.home_desktop_percent, desktop.percent.coerceIn(0, 100)),
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
                            .padding(top = FluxSpacing.sm),
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
                            modifier = Modifier.padding(top = FluxSpacing.md)
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = FluxSpacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
                    ) {
                        TonalButton(
                            label = stringResource(R.string.home_desktop_retry),
                            icon = Icons.Filled.Refresh,
                            onClick = actions.onInstallDesktop,
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
                    if (status == UbuntuStatus.STARTING) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = FluxSpacing.lg)
                                .clip(CircleShape),
                        )
                    }
                    Spacer(Modifier.height(FluxSpacing.lg))
                    when (status) {
                        UbuntuStatus.STOPPED -> PrimaryButton(
                            label = stringResource(R.string.home_action_start_desktop),
                            icon = Icons.Filled.PlayArrow,
                            onClick = actions.onStartDesktop,
                            modifier = Modifier.fillMaxWidth()
                        )
                        else -> Row(horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm)) {
                            PrimaryButton(
                                label = stringResource(R.string.home_action_open_desktop),
                                icon = Icons.AutoMirrored.Filled.OpenInNew,
                                onClick = actions.onOpenDesktop,
                                modifier = Modifier.weight(1.4f)
                            )
                            SecondaryButton(
                                label = stringResource(R.string.home_action_stop),
                                icon = Icons.Filled.Stop,
                                onClick = actions.onStopDesktop,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
    Panel(modifier = modifier) {
        Column(Modifier.padding(FluxSpacing.lg)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.SdStorage,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = FluxSpacing.xxs).size(RowIcon)
                )
                Spacer(Modifier.width(FluxSpacing.lg))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.home_storage_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface
                    )
                    Text(
                        text = when {
                            !storage.supported -> stringResource(R.string.home_storage_unsupported)
                            storage.granted -> stringResource(R.string.home_storage_granted)
                            else -> stringResource(R.string.home_storage_denied)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            if (!storage.supported) return@Column

            if (!storage.granted) {
                PrimaryButton(
                    label = stringResource(R.string.home_storage_allow),
                    icon = null,
                    onClick = onAllow,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = FluxSpacing.md)
                )
            }

            Text(
                text = if (storage.linksPresent) {
                    stringResource(R.string.home_storage_links_ready)
                } else {
                    stringResource(R.string.home_storage_links_missing)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
                modifier = Modifier.padding(top = FluxSpacing.lg)
            )
            FlowRow(
                modifier = Modifier.padding(top = FluxSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
            ) {
                storage.linkNames.forEach { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(colors.surfaceContainerHighest)
                            .padding(horizontal = FluxSpacing.md, vertical = FluxSpacing.xs)
                    )
                }
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

// ─── Recovery ─────────────────────────────────────────────────────────────────

@Composable
fun RecoverySection(
    installed: Boolean,
    actions: UbuntuDashboardActions,
    modifier: Modifier = Modifier
) {
    Section(title = stringResource(R.string.home_recovery_title), modifier = modifier) {
        Panel {
            Column(Modifier.padding(vertical = FluxSpacing.xs)) {
                NavRow(
                    Icons.Filled.StopCircle,
                    stringResource(R.string.home_recovery_stop_all),
                    stringResource(R.string.home_recovery_stop_all_sub),
                    actions.onStopEverything
                )
                NavRow(
                    Icons.Filled.Build,
                    stringResource(R.string.home_recovery_repair),
                    stringResource(R.string.home_recovery_repair_sub),
                    actions.onRepairHost
                )
                if (installed) {
                    NavRow(
                        Icons.Filled.Refresh,
                        stringResource(R.string.home_recovery_rerun),
                        stringResource(R.string.home_recovery_rerun_sub),
                        actions.onRerunSetup
                    )
                    NavRow(
                        Icons.Filled.Settings,
                        stringResource(R.string.home_recovery_settings),
                        stringResource(R.string.home_recovery_settings_sub),
                        actions.onUbuntuSettings
                    )
                }
                NavRow(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    stringResource(R.string.home_recovery_troubleshooting),
                    stringResource(R.string.home_recovery_troubleshooting_sub),
                    actions.onTroubleshooting
                )
            }
        }
    }
}

// ─── Shared bits ──────────────────────────────────────────────────────────────

/** A titled group: heading on the background, its panels below. */
@Composable
private fun Section(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(FluxSpacing.sm)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(start = FluxSpacing.xs, top = FluxSpacing.xs)
                .semantics { heading() }
        )
        content()
    }
}

/** Tonal surface one step above the background; hierarchy through tone, no borders. */
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        content = content
    )
}

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
            .heightIn(min = FluxSpacing.touch + FluxSpacing.sm)
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
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant
        )
    }
}

@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = FluxSpacing.touch)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(onClick = onClick, shape = MaterialTheme.shapes.medium, modifier = modifier.heightIn(min = FluxSpacing.touch)) {
        ButtonContent(label, icon)
    }
}

@Composable
private fun TonalButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.heightIn(min = FluxSpacing.touch)
    ) {
        ButtonContent(label, icon)
    }
}

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
        shape = MaterialTheme.shapes.medium,
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
