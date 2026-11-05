package com.ivarna.fluxlinux.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ivarna.fluxlinux.ui.theme.AkaMotion
import com.ivarna.fluxlinux.ui.theme.AkaTheme
import com.ivarna.fluxlinux.ui.theme.rememberReducedMotion

private val TrackWidth = 52.dp
private val TrackHeight = 32.dp
private val ThumbOn = 22.dp
private val ThumbOff = 16.dp
private val ThumbInset = 5.dp
private val Hairline = 1.dp

/**
 * The app's switch, cut from paper rather than Material's stadium: a squared track (the
 * small corner) with a squared thumb. On = red track, ink-black thumb pushed to the end;
 * off = an outlined track in the Federal hairline with a smaller thumb. The thumb slides with
 * the settle spring; reduced motion snaps.
 *
 * With [onCheckedChange] null the switch is only the visual and the row it sits in owns the
 * toggle and its semantics (Settings rows, the drawer). [onShellField] is for switches on the
 * Federal Blue shell, where the off track takes the field's own tint so it doesn't read as a
 * hole.
 */
@Composable
fun FluxSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onShellField: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val inks = AkaTheme.inks
    val shapes = MaterialTheme.shapes
    val reduced = rememberReducedMotion()

    val trackColor by animateColorAsState(
        targetValue = when {
            checked -> scheme.primary
            onShellField -> inks.shellFieldHigh
            else -> scheme.surfaceContainerHighest
        },
        animationSpec = if (reduced) snap() else AkaMotion.standard(),
        label = "switchTrack",
    )
    val borderColor = when {
        checked -> scheme.primary
        onShellField -> inks.onShellFieldMuted
        else -> scheme.outline
    }
    val thumbColor = when {
        checked -> scheme.onPrimary
        onShellField -> inks.onShellFieldMuted
        else -> scheme.outline
    }
    val thumbSize by animateDpAsState(
        targetValue = if (checked) ThumbOn else ThumbOff,
        animationSpec = if (reduced) snap() else AkaMotion.settle(),
        label = "switchThumbSize",
    )
    val travel = TrackWidth - ThumbInset * 2 - ThumbOn
    val thumbX by animateDpAsState(
        targetValue = if (checked) ThumbInset + travel else ThumbInset + (ThumbOn - ThumbOff) / 2,
        animationSpec = if (reduced) snap() else AkaMotion.settle(),
        label = "switchThumbX",
    )

    val toggle = if (onCheckedChange != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
            )
    } else {
        Modifier
    }

    Box(modifier = modifier.then(toggle), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(TrackWidth, TrackHeight)
                .alpha(if (enabled) 1f else 0.38f)
                .clip(shapes.small)
                .background(trackColor)
                .border(Hairline, borderColor, shapes.small),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .offset(x = thumbX)
                    .size(thumbSize)
                    .clip(shapes.extraSmall)
                    .background(thumbColor),
            )
        }
    }
}
