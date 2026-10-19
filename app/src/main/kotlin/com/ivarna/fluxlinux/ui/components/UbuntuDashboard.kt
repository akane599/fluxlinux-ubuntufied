package com.ivarna.fluxlinux.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.ui.theme.FluxHairline
import kotlinx.coroutines.launch

// Local layout scale for the dashboard (the theme has no spacing tokens yet).
private val Gutter = 16.dp
private val Gap = 12.dp
private val SmallGap = 8.dp
private val TinyGap = 4.dp
private val CardPadding = 18.dp
private val ButtonHeight = 48.dp
private val TileMinHeight = 76.dp
private val RowMinHeight = 56.dp
private val LogoBox = 56.dp
private val LogoSize = 40.dp
private val IconBadge = 40.dp
private val IconSize = 22.dp
private val SmallIcon = 18.dp
private val StatusDot = 8.dp
private val HeroShape = RoundedCornerShape(24.dp)
private val TileShape = RoundedCornerShape(18.dp)
private val ChipShape = RoundedCornerShape(50)
private val LogoShape = RoundedCornerShape(16.dp)
private val ButtonShape = RoundedCornerShape(14.dp)

/** Lifecycle state of the Ubuntu desktop as shown on Home. */
enum class UbuntuStatus { NOT_INSTALLED, STOPPED, STARTING, RUNNING }

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
    /** Cold-launch countdown before auto-start; shows Cancel. */
    val autoStartPending: Boolean = false,
    /** Crash-loop guard tripped: the last automatic start never finished. */
    val lastStartFailed: Boolean = false,
    /** Name of another distro whose desktop is running, if any. */
    val otherDesktopRunning: String? = null,
    val storage: SharedStorageUi
)

/** Every user action on the dashboard. Stateless leaves call these; HomeScreen wires them. */
@Immutable
class UbuntuDashboardActions(
    val onStartDesktop: () -> Unit = {},
    val onOpenDesktop: () -> Unit = {},
    val onStopDesktop: () -> Unit = {},
    val onCancelAutoStart: () -> Unit = {},
    val onSetUpUbuntu: () -> Unit = {},
    val onTerminal: () -> Unit = {},
    val onRootTerminal: () -> Unit = {},
    val onFiles: () -> Unit = {},
    val onFilesystem: () -> Unit = {},
    val onAllowStorage: () -> Unit = {},
    val onCreateLinks: () -> Unit = {},
    val onViewLog: () -> Unit = {},
    val onStopEverything: () -> Unit = {},
    val onRepairHost: () -> Unit = {},
    val onRerunSetup: () -> Unit = {},
    val onUbuntuSettings: () -> Unit = {},
    val onTroubleshooting: () -> Unit = {}
)

/** The whole Ubuntu dashboard. Stateless: callers own state and scrolling. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UbuntuDashboard(
    state: UbuntuDashboardState,
    actions: UbuntuDashboardActions,
    modifier: Modifier = Modifier
) {
    val recoveryRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val installed = state.status != UbuntuStatus.NOT_INSTALLED

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Gutter, vertical = SmallGap),
        verticalArrangement = Arrangement.spacedBy(Gap)
    ) {
        if (state.lastStartFailed && !state.autoStartPending &&
            state.status == UbuntuStatus.STOPPED
        ) {
            StartFailedBanner(
                onViewLog = actions.onViewLog,
                onTryAgain = actions.onStartDesktop,
                onRecovery = { scope.launch { recoveryRequester.bringIntoView() } }
            )
        }

        UbuntuHeroCard(
            status = state.status,
            autoStartPending = state.autoStartPending,
            otherDesktopRunning = state.otherDesktopRunning,
            actions = actions
        )

        if (installed) {
            QuickActionsGrid(actions = actions)
            SharedStorageCard(
                storage = state.storage,
                onAllow = actions.onAllowStorage,
                onCreateLinks = actions.onCreateLinks
            )
        }

        RecoverySection(
            installed = installed,
            actions = actions,
            modifier = Modifier.bringIntoViewRequester(recoveryRequester)
        )

        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

// ─── Hero ─────────────────────────────────────────────────────────────────────

@Composable
fun UbuntuHeroCard(
    status: UbuntuStatus,
    autoStartPending: Boolean,
    otherDesktopRunning: String?,
    actions: UbuntuDashboardActions,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val shownStatus = if (autoStartPending) UbuntuStatus.STARTING else status
    val body = when {
        autoStartPending -> stringResource(R.string.home_autostart_body)
        status == UbuntuStatus.NOT_INSTALLED -> stringResource(R.string.home_hero_not_installed)
        otherDesktopRunning != null && status == UbuntuStatus.STOPPED ->
            stringResource(R.string.home_hero_other_running, otherDesktopRunning)
        status == UbuntuStatus.RUNNING -> stringResource(R.string.home_hero_running)
        status == UbuntuStatus.STARTING -> stringResource(R.string.home_hero_starting)
        else -> stringResource(R.string.home_hero_stopped)
    }

    GlassPanel(shape = HeroShape, modifier = modifier) {
        Column(Modifier.padding(CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(LogoBox)
                        .clip(LogoShape)
                        .background(colors.secondary.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.distro_ubuntu),
                        contentDescription = stringResource(R.string.home_ubuntu_logo),
                        modifier = Modifier.size(LogoSize)
                    )
                }
                Spacer(Modifier.width(Gap + TinyGap))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (autoStartPending) {
                            stringResource(R.string.home_autostart_title)
                        } else {
                            stringResource(R.string.home_ubuntu_name)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Text(
                        text = stringResource(R.string.home_ubuntu_version),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(Gap))
            StatusChip(shownStatus)
            Spacer(Modifier.height(SmallGap))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant
            )

            if (shownStatus == UbuntuStatus.STARTING) {
                Spacer(Modifier.height(Gap))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ChipShape),
                    color = colors.secondary,
                    trackColor = colors.secondary.copy(alpha = 0.16f)
                )
            }

            Spacer(Modifier.height(Gap + TinyGap))
            HeroButtons(
                status = status,
                autoStartPending = autoStartPending,
                actions = actions
            )
        }
    }
}

@Composable
private fun HeroButtons(
    status: UbuntuStatus,
    autoStartPending: Boolean,
    actions: UbuntuDashboardActions
) {
    when {
        autoStartPending -> SecondaryButton(
            label = stringResource(R.string.home_autostart_cancel),
            icon = null,
            onClick = actions.onCancelAutoStart,
            modifier = Modifier.fillMaxWidth()
        )
        status == UbuntuStatus.NOT_INSTALLED -> PrimaryButton(
            label = stringResource(R.string.home_action_set_up),
            icon = Icons.Filled.PlayArrow,
            onClick = actions.onSetUpUbuntu,
            modifier = Modifier.fillMaxWidth()
        )
        status == UbuntuStatus.STOPPED -> PrimaryButton(
            label = stringResource(R.string.home_action_start_desktop),
            icon = Icons.Filled.PlayArrow,
            onClick = actions.onStartDesktop,
            modifier = Modifier.fillMaxWidth()
        )
        else -> Row(horizontalArrangement = Arrangement.spacedBy(SmallGap)) {
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
}

@Composable
private fun StatusChip(status: UbuntuStatus) {
    val colors = MaterialTheme.colorScheme
    val (label, tint) = when (status) {
        UbuntuStatus.RUNNING -> stringResource(R.string.home_status_running) to colors.secondary
        UbuntuStatus.STARTING -> stringResource(R.string.home_status_starting) to colors.secondary
        UbuntuStatus.STOPPED -> stringResource(R.string.home_status_stopped) to colors.onSurfaceVariant
        UbuntuStatus.NOT_INSTALLED -> stringResource(R.string.home_status_not_installed) to colors.error
    }
    val filled = status == UbuntuStatus.RUNNING
    Row(
        modifier = Modifier
            .clip(ChipShape)
            .background(if (filled) tint else tint.copy(alpha = 0.12f))
            .border(1.dp, tint.copy(alpha = if (filled) 0f else 0.45f), ChipShape)
            .padding(horizontal = Gap, vertical = TinyGap + 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(StatusDot)
                .clip(CircleShape)
                .background(if (filled) colors.onSecondary else tint)
        )
        Spacer(Modifier.width(SmallGap))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (filled) colors.onSecondary else tint
        )
    }
}

// ─── Banners ──────────────────────────────────────────────────────────────────

@Composable
fun StartFailedBanner(
    onViewLog: () -> Unit,
    onTryAgain: () -> Unit,
    onRecovery: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    GlassPanel(
        shape = TileShape,
        borderColor = colors.error.copy(alpha = 0.55f),
        modifier = modifier
    ) {
        Column(Modifier.padding(start = CardPadding, end = SmallGap, top = CardPadding, bottom = TinyGap)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(IconSize)
                )
                Spacer(Modifier.width(Gap))
                Column(Modifier.weight(1f).padding(end = SmallGap)) {
                    Text(
                        stringResource(R.string.home_failed_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                    Text(
                        stringResource(R.string.home_failed_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                BannerAction(stringResource(R.string.home_failed_view_log), onViewLog)
                BannerAction(stringResource(R.string.home_failed_recovery), onRecovery)
                BannerAction(stringResource(R.string.home_failed_try_again), onTryAgain)
            }
        }
    }
}

@Composable
private fun BannerAction(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.secondary),
        modifier = Modifier.heightIn(min = ButtonHeight)
    ) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}

// ─── Quick actions ────────────────────────────────────────────────────────────

@Composable
fun QuickActionsGrid(actions: UbuntuDashboardActions, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(SmallGap + TinyGap)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(SmallGap + TinyGap)) {
            QuickActionTile(
                icon = Icons.Filled.Terminal,
                label = stringResource(R.string.home_quick_terminal),
                onClick = actions.onTerminal,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            QuickActionTile(
                icon = Icons.Filled.AdminPanelSettings,
                label = stringResource(R.string.home_quick_root_terminal),
                onClick = actions.onRootTerminal,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(SmallGap + TinyGap)) {
            QuickActionTile(
                icon = Icons.Filled.Folder,
                label = stringResource(R.string.home_quick_files),
                onClick = actions.onFiles,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            QuickActionTile(
                icon = Icons.Filled.FolderOpen,
                label = stringResource(R.string.home_quick_filesystem),
                onClick = actions.onFilesystem,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun QuickActionTile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    // Icon above label so the label keeps the full tile width at large font scales.
    GlassPanel(shape = TileShape, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .heightIn(min = TileMinHeight)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = Gap + TinyGap, vertical = Gap + TinyGap)
        ) {
            IconBadge(icon)
            Spacer(Modifier.height(SmallGap + TinyGap))
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
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
    GlassPanel(shape = TileShape, modifier = modifier) {
        Column(Modifier.padding(CardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.SdStorage)
                Spacer(Modifier.width(Gap))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.home_storage_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
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
                Spacer(Modifier.height(Gap))
                PrimaryButton(
                    label = stringResource(R.string.home_storage_allow),
                    icon = null,
                    onClick = onAllow,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(Gap + TinyGap))
            Text(
                text = if (storage.linksPresent) {
                    stringResource(R.string.home_storage_links_ready)
                } else {
                    stringResource(R.string.home_storage_links_missing)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface
            )
            Spacer(Modifier.height(SmallGap))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(SmallGap - 2.dp),
                verticalArrangement = Arrangement.spacedBy(SmallGap - 2.dp)
            ) {
                storage.linkNames.forEach { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier
                            .clip(ChipShape)
                            .background(colors.onSurface.copy(alpha = 0.08f))
                            .padding(horizontal = SmallGap + 2.dp, vertical = TinyGap)
                    )
                }
            }
            if (!storage.linksPresent) {
                Spacer(Modifier.height(Gap))
                SecondaryButton(
                    label = if (storage.creatingLinks) {
                        stringResource(R.string.home_storage_creating_links)
                    } else {
                        stringResource(R.string.home_storage_create_links)
                    },
                    icon = null,
                    enabled = !storage.creatingLinks,
                    onClick = onCreateLinks,
                    modifier = Modifier.fillMaxWidth()
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
    Column(modifier) {
        Text(
            stringResource(R.string.home_recovery_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .padding(start = TinyGap, top = SmallGap, bottom = SmallGap)
                .semantics { heading() }
        )
        GlassPanel(shape = TileShape) {
            Column(Modifier.padding(vertical = TinyGap)) {
                RecoveryRow(
                    Icons.AutoMirrored.Filled.Article,
                    stringResource(R.string.home_recovery_view_log),
                    stringResource(R.string.home_recovery_view_log_sub),
                    actions.onViewLog
                )
                RecoveryRow(
                    Icons.Filled.StopCircle,
                    stringResource(R.string.home_recovery_stop_all),
                    stringResource(R.string.home_recovery_stop_all_sub),
                    actions.onStopEverything
                )
                RecoveryRow(
                    Icons.Filled.Build,
                    stringResource(R.string.home_recovery_repair),
                    stringResource(R.string.home_recovery_repair_sub),
                    actions.onRepairHost
                )
                if (installed) {
                    RecoveryRow(
                        Icons.Filled.Refresh,
                        stringResource(R.string.home_recovery_rerun),
                        stringResource(R.string.home_recovery_rerun_sub),
                        actions.onRerunSetup
                    )
                    RecoveryRow(
                        Icons.Filled.Settings,
                        stringResource(R.string.home_recovery_settings),
                        stringResource(R.string.home_recovery_settings_sub),
                        actions.onUbuntuSettings
                    )
                }
                RecoveryRow(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    stringResource(R.string.home_recovery_troubleshooting),
                    stringResource(R.string.home_recovery_troubleshooting_sub),
                    actions.onTroubleshooting
                )
            }
        }
    }
}

@Composable
private fun RecoveryRow(
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
            .padding(horizontal = CardPadding, vertical = Gap - TinyGap),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(icon)
        Spacer(Modifier.width(Gap + TinyGap))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant
        )
    }
}

// ─── Shared bits ──────────────────────────────────────────────────────────────

/** Glass surface matching [GlassSettingCard]: translucent surface + hairline border. */
@Composable
private fun GlassPanel(
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    borderColor: Color = FluxHairline,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.90f))
            .border(1.dp, borderColor, shape)
    ) {
        content()
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    val tint = MaterialTheme.colorScheme.secondary
    Box(
        modifier = Modifier
            .size(IconBadge)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(IconSize))
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary,
            contentColor = MaterialTheme.colorScheme.onSecondary
        ),
        modifier = modifier.heightIn(min = ButtonHeight)
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
    val accent = MaterialTheme.colorScheme.secondary
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonShape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.6f)),
        modifier = modifier.heightIn(min = ButtonHeight)
    ) {
        ButtonContent(label, icon)
    }
}

@Composable
private fun ButtonContent(label: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(SmallIcon))
        Spacer(Modifier.width(SmallGap))
    }
    Text(label, fontWeight = FontWeight.Bold)
}
