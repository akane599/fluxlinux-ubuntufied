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
 * The app's switch: a soft-cornered track (the small corner) with a soft square thumb rather
 * than Material's stadium. On = akane track, white thumb pushed to the end; off = a kon track
 * edged with the ai control line and a smaller ai thumb. The thumb slides with the settle
 * spring; reduced motion snaps.
 *
 * With [onCheckedChange] null the switch is only the visual and the row it sits in owns the
 * toggle and its semantics (Settings rows, the drawer).
 */
@Composable
fun FluxSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val inks = AkaTheme.inks
    val shapes = MaterialTheme.shapes
    val reduced = rememberReducedMotion()

    val trackColor by animateColorAsState(
        targetValue = when {
            checked -> inks.primaryFill
            else -> inks.tile
        },
        animationSpec = if (reduced) snap() else AkaMotion.standard(),
        label = "switchTrack",
    )
    val borderColor = if (checked) inks.primaryFill else inks.controlLine
    val thumbColor = if (checked) inks.onPrimaryFill else inks.controlLine
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
