package com.ivarna.fluxlinux.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.Article
import androidx.compose.material.icons.automirrored.sharp.OpenInNew
import androidx.compose.material.icons.sharp.PlayArrow
import androidx.compose.material.icons.sharp.Settings
import androidx.compose.material.icons.sharp.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.ui.components.aka.PlateKind
import com.ivarna.fluxlinux.ui.components.aka.PlatedButton
import com.ivarna.fluxlinux.ui.components.aka.plateInks
import com.ivarna.fluxlinux.ui.theme.AkaTheme
import com.ivarna.fluxlinux.ui.theme.FluxSpacing

// Local sizes; spacing comes from FluxSpacing, radii from MaterialTheme.shapes.
private val DistroLogo = 36.dp
private val ActionIcon = 16.dp
private val SettingsIcon = 20.dp

fun Distro.isChrootCard(): Boolean =
    id.contains("chroot", ignoreCase = true) || (chrootSupported && !prootSupported)

/**
 * A legacy distro entry (other distros are not in the main UI any more): logo, name with its
 * method / state chips, and its actions, as rows on the stock. Start and Install are the
 * printed primary plate; the rest are tonal.
 */
@Composable
fun DistroCard(
    distro: Distro,
    isInstalled: Boolean = false,
    isGuiRunning: Boolean = false,
    /** True while start_gui is streaming (termux-lib Starting phase). */
    isGuiStarting: Boolean = false,
    isGlobalInstalling: Boolean = false,
    isCurrentlyInstalling: Boolean = false,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToStart: () -> Unit,
    onStop: () -> Unit = {},
    onOpenDisplay: () -> Unit = {},
    onViewLogs: () -> Unit = {},
    logsAvailable: Boolean = false,
    startEnabled: Boolean = true,
    statusMessage: String? = null,
    statusIsError: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isChroot = distro.isChrootCard()
    val colors = MaterialTheme.colorScheme
    val inks = AkaTheme.inks
    val showLogs = logsAvailable || isGuiStarting || isGuiRunning

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (distro.iconRes != null) {
                androidx.compose.foundation.Image(
                    painter = painterResource(id = distro.iconRes),
                    contentDescription = stringResource(R.string.ubuntu_aka_distro_logo, distro.name),
                    modifier = Modifier
                        .size(DistroLogo)
                        .clip(MaterialTheme.shapes.small)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(DistroLogo)
                        .clip(MaterialTheme.shapes.small)
                        .background(colors.surfaceContainerHighest)
                )
            }

            Spacer(modifier = Modifier.width(FluxSpacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = distro.name.removeSuffix(" (Rooted)"),
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(FluxSpacing.sm))
                    MethodChip(
                        label = stringResource(if (isChroot) R.string.ubuntu_aka_chip_chroot else R.string.ubuntu_aka_chip_proot),
                        color = colors.secondary
                    )
                    if (isInstalled && isChroot && !startEnabled) {
                        Spacer(modifier = Modifier.width(FluxSpacing.xs))
                        MethodChip(
                            label = stringResource(R.string.ubuntu_aka_chip_no_root),
                            color = inks.failed
                        )
                    }
                    if (isInstalled && (isGuiRunning || isGuiStarting)) {
                        Spacer(modifier = Modifier.width(FluxSpacing.xs))
                        MethodChip(
                            label = stringResource(
                                if (isGuiStarting) R.string.ubuntu_aka_chip_starting else R.string.ubuntu_aka_chip_running
                            ),
                            color = if (isGuiStarting) inks.held else inks.running
                        )
                    }
                    if (distro.comingSoon) {
                        Spacer(modifier = Modifier.width(FluxSpacing.xs))
                        MethodChip(
                            label = stringResource(R.string.ubuntu_aka_chip_soon),
                            color = colors.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = statusMessage ?: distro.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (statusIsError) colors.error else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isInstalled) {
                IconButton(onClick = onNavigateToSettings) {
                    Icon(
                        imageVector = Icons.Sharp.Settings,
                        contentDescription = stringResource(R.string.ubuntu_aka_distro_settings),
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(SettingsIcon)
                    )
                }
            }
        }

        if (!isInstalled && distro.description.isNotBlank()) {
            Text(
                text = distro.description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = FluxSpacing.xs)
            )
        }

        Spacer(modifier = Modifier.height(FluxSpacing.sm))

        if (!isInstalled) {
            PlatedButton(
                text = stringResource(
                    when {
                        isCurrentlyInstalling -> R.string.ubuntu_aka_card_progress
                        isGlobalInstalling -> R.string.ubuntu_aka_card_busy
                        distro.comingSoon -> R.string.ubuntu_aka_chip_soon
                        else -> R.string.ubuntu_aka_card_install
                    }
                ),
                onClick = if (distro.comingSoon || (isGlobalInstalling && !isCurrentlyInstalling)) {
                    {}
                } else {
                    onInstall
                },
                enabled = !distro.comingSoon && (!isGlobalInstalling || isCurrentlyInstalling),
                modifier = Modifier.fillMaxWidth()
            )
        } else if (isGuiRunning || isGuiStarting) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
            ) {
                CompactAction(
                    label = stringResource(R.string.ubuntu_aka_card_open),
                    icon = Icons.AutoMirrored.Sharp.OpenInNew,
                    onClick = onOpenDisplay,
                    enabled = isGuiRunning || isGuiStarting,
                    containerColor = colors.secondaryContainer,
                    contentColor = colors.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                CompactAction(
                    label = stringResource(R.string.ubuntu_aka_card_stop),
                    icon = Icons.Sharp.Stop,
                    onClick = onStop,
                    containerColor = colors.surfaceContainerHighest,
                    contentColor = colors.onSurface,
                    modifier = Modifier.weight(0.85f)
                )
                if (showLogs) {
                    CompactAction(
                        label = stringResource(R.string.ubuntu_aka_card_logs),
                        icon = Icons.AutoMirrored.Sharp.Article,
                        onClick = onViewLogs,
                        containerColor = colors.surfaceContainerHighest,
                        contentColor = colors.onSurface,
                        modifier = Modifier.weight(0.85f)
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(FluxSpacing.sm)
            ) {
                PlatedButton(
                    text = stringResource(
                        if (!startEnabled && isChroot) R.string.ubuntu_aka_card_need_root else R.string.ubuntu_aka_card_start
                    ),
                    icon = Icons.Sharp.PlayArrow,
                    onClick = onNavigateToStart,
                    enabled = startEnabled,
                    contentPadding = PaddingValues(horizontal = FluxSpacing.md, vertical = FluxSpacing.sm),
                    modifier = Modifier.weight(1.2f)
                )
                if (showLogs) {
                    CompactAction(
                        label = stringResource(R.string.ubuntu_aka_card_logs),
                        icon = Icons.AutoMirrored.Sharp.Article,
                        onClick = onViewLogs,
                        containerColor = colors.surfaceContainerHighest,
                        contentColor = colors.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

enum class MethodTab { PROOT, CHROOT }

/** PRoot / Chroot selector; the selected side is printed as the selection overprint. */
@Composable
fun MethodTabs(
    selected: MethodTab,
    onSelected: (MethodTab) -> Unit,
    modifier: Modifier = Modifier,
    prootCount: Int? = null,
    chrootCount: Int? = null,
    prootLabel: String = "PRoot",
    chrootLabel: String = "Chroot",
    chrootEnabled: Boolean = true,
    horizontalPadding: Dp = FluxSpacing.lg,
) {
    val prootText = if (prootCount != null) "$prootLabel  $prootCount" else prootLabel
    val chrootText = if (chrootCount != null) "$chrootLabel  $chrootCount" else chrootLabel
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(FluxSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(FluxSpacing.xs)
    ) {
        MethodTabSegment(
            label = prootText,
            selected = selected == MethodTab.PROOT,
            onClick = { onSelected(MethodTab.PROOT) },
            modifier = Modifier.weight(1f)
        )
        MethodTabSegment(
            label = chrootText,
            selected = selected == MethodTab.CHROOT,
            enabled = chrootEnabled,
            onClick = { onSelected(MethodTab.CHROOT) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MethodTabSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val ink = plateInks(PlateKind.SELECTION)
    val labelColor = when {
        selected -> ink.onFill
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .heightIn(min = FluxSpacing.touch)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (selected) ink.fill else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = labelColor,
            style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A small printed label: [label] in [color] ink on a light tint of the same ink, no border. */
@Composable
fun MethodChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = FluxSpacing.xs + FluxSpacing.xxs, vertical = FluxSpacing.xxs)
    ) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
internal fun CompactAction(
    label: String,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.35f),
            disabledContentColor = contentColor.copy(alpha = 0.5f)
        ),
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = FluxSpacing.sm, vertical = 0.dp),
        modifier = modifier.heightIn(min = FluxSpacing.touch)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(ActionIcon)
            )
            Spacer(modifier = Modifier.width(FluxSpacing.xs))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * A settings group: a flat tonal block on the stock (no border, no shadow). Kept under its
 * old name for the screens that still use it; new rows use `aka.AkaRow` under a `SectionLabel`.
 */
@Composable
fun GlassSettingCard(
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") delayMillis: Int = 0,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val shape = MaterialTheme.shapes.large
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        content()
    }
}
