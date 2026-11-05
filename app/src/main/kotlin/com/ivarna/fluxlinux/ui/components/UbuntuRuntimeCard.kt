package com.ivarna.fluxlinux.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.LocalIndication
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.Check
import androidx.compose.material.icons.sharp.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.utils.UbuntuRuntimeMode
import com.ivarna.fluxlinux.ui.components.aka.MarkState
import com.ivarna.fluxlinux.ui.components.aka.PlateKind
import com.ivarna.fluxlinux.ui.components.aka.StatusMark
import com.ivarna.fluxlinux.ui.components.aka.plateInks
import com.ivarna.fluxlinux.ui.components.aka.plateOffset
import com.ivarna.fluxlinux.ui.theme.FluxSpacing
import com.ivarna.fluxlinux.ui.theme.misregistered

// Local sizes; spacing comes from FluxSpacing, radii from MaterialTheme.shapes.
private val IconSize = 22.dp
private val CheckIcon = 16.dp
private val StatusMarkSize = 12.dp

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
 * (root). Stateless: the screen probes root and applies the switch. The chosen runtime is
 * printed as the selection overprint, its plates resting out of register until touched.
 */
@Composable
fun UbuntuRuntimeCard(
    state: UbuntuRuntimeUi,
    onSelect: (UbuntuRuntimeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    GlassSettingCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(FluxSpacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Sharp.Layers,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(IconSize)
                )
                Spacer(Modifier.width(FluxSpacing.lg))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_runtime_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface
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

            Spacer(Modifier.height(FluxSpacing.md))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(colors.surfaceContainerHighest)
                    .padding(FluxSpacing.xs)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(FluxSpacing.xs)
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
                Spacer(Modifier.height(FluxSpacing.md))
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    val mark = when {
                        state.checking -> MarkState.WORKING
                        state.messageIsError -> MarkState.FAILED
                        else -> null
                    }
                    if (mark != null) {
                        StatusMark(
                            state = mark,
                            size = StatusMarkSize,
                            modifier = Modifier.padding(top = FluxSpacing.xxs)
                        )
                        Spacer(Modifier.width(FluxSpacing.sm))
                    }
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
    val shape = MaterialTheme.shapes.extraSmall
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ink = plateInks(PlateKind.SELECTION)
    val offset = plateOffset(registered = pressed)
    Row(
        modifier = modifier
            .heightIn(min = FluxSpacing.touch)
            .then(
                if (selected) {
                    Modifier.misregistered(shape, ink.fill, ink.plateA, ink.plateB) { offset }
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = FluxSpacing.sm),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val content = when {
            selected -> ink.onFill
            enabled -> colors.onSurface
            else -> colors.onSurfaceVariant.copy(alpha = 0.6f)
        }
        if (selected) {
            Icon(
                Icons.Sharp.Check,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(CheckIcon)
            )
            Spacer(Modifier.width(FluxSpacing.sm))
        }
        Text(
            label,
            style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
            color = content,
            textAlign = TextAlign.Center
        )
    }
}
