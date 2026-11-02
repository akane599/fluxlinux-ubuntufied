package com.ivarna.fluxlinux.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.utils.UbuntuRuntimeMode

// Same scale as the Settings cards (SettingsNavCard / SettingsSwitchCard).
private val CardPaddingH = 18.dp
private val CardPaddingV = 18.dp
private val IconBadgeSize = 44.dp
private val IconSize = 22.dp
private val BadgeGap = 14.dp
private val SectionGap = 14.dp
private val SegmentHeight = 48.dp
private val SegmentInset = 4.dp
private val StatusIcon = 16.dp
private val SegmentTrackShape = RoundedCornerShape(16.dp)
private val SegmentShape = RoundedCornerShape(12.dp)

/** What the Settings "Ubuntu runtime" row shows. */
@Immutable
data class UbuntuRuntimeUi(
    val selected: UbuntuRuntimeMode,
    /** A root probe for a chroot switch is running off the main thread. */
    val checking: Boolean = false,
    /** Result of the last attempt (no root, install missing), or null. */
    val message: String? = null,
    val messageIsError: Boolean = false,
)

/**
 * Settings row choosing which Ubuntu install the app drives: PRoot (any phone) or chroot
 * (root). Stateless: the screen probes root and applies the switch.
 */
@Composable
fun UbuntuRuntimeCard(
    state: UbuntuRuntimeUi,
    onSelect: (UbuntuRuntimeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val accent = colors.secondary
    GlassSettingCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = CardPaddingH, vertical = CardPaddingV)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(IconBadgeSize)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Layers, contentDescription = null, tint = accent, modifier = Modifier.size(IconSize))
                }
                Spacer(Modifier.width(BadgeGap))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_runtime_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = colors.onBackground
                    )
                    Text(
                        stringResource(
                            if (state.selected == UbuntuRuntimeMode.CHROOT) R.string.settings_runtime_chroot_sub
                            else R.string.settings_runtime_proot_sub
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(SectionGap))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(SegmentTrackShape)
                    .background(colors.onSurface.copy(alpha = 0.06f))
                    .border(1.dp, colors.onSurface.copy(alpha = 0.12f), SegmentTrackShape)
                    .padding(SegmentInset)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(SegmentInset)
            ) {
                RuntimeSegment(
                    label = stringResource(R.string.settings_runtime_proot),
                    selected = state.selected == UbuntuRuntimeMode.PROOT,
                    enabled = !state.checking,
                    onClick = { onSelect(UbuntuRuntimeMode.PROOT) },
                    modifier = Modifier.weight(1f)
                )
                RuntimeSegment(
                    label = stringResource(R.string.settings_runtime_chroot),
                    selected = state.selected == UbuntuRuntimeMode.CHROOT,
                    enabled = !state.checking,
                    onClick = { onSelect(UbuntuRuntimeMode.CHROOT) },
                    modifier = Modifier.weight(1f)
                )
            }

            val status = if (state.checking) stringResource(R.string.settings_runtime_checking) else state.message
            if (status != null) {
                Spacer(Modifier.height(SectionGap - 2.dp))
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    when {
                        state.checking -> CircularProgressIndicator(
                            modifier = Modifier.size(StatusIcon),
                            strokeWidth = 2.dp,
                            color = accent
                        )
                        state.messageIsError -> Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = colors.error,
                            modifier = Modifier.size(StatusIcon)
                        )
                    }
                    if (state.checking || state.messageIsError) Spacer(Modifier.width(SegmentInset * 2))
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.messageIsError && !state.checking) colors.error else colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeSegment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .heightIn(min = SegmentHeight)
            .clip(SegmentShape)
            .background(if (selected) colors.secondary else Color.Transparent)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = SegmentInset * 2),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = colors.onSecondary,
                modifier = Modifier.size(StatusIcon)
            )
            Spacer(Modifier.width(SegmentInset + 2.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = when {
                selected -> colors.onSecondary
                enabled -> colors.onSurface
                else -> colors.onSurfaceVariant.copy(alpha = 0.6f)
            },
            textAlign = TextAlign.Center
        )
    }
}
