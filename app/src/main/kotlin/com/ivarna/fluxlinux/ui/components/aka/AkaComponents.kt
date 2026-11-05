package com.ivarna.fluxlinux.ui.components.aka

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ivarna.fluxlinux.ui.theme.AkaMotion
import com.ivarna.fluxlinux.ui.theme.AkaTheme
import com.ivarna.fluxlinux.ui.theme.FluxSpacing
import com.ivarna.fluxlinux.ui.theme.misregistered
import com.ivarna.fluxlinux.ui.theme.rememberReducedMotion

/** Which plates an element is printed with. */
enum class PlateKind {
    /** Primary action: vermilion overprint, red and yellow fringes. */
    PRIMARY,

    /** Selected / current item: plum overprint, red and Federal fringes. */
    SELECTION,
}

/**
 * The signature move as a reusable state: the plates rest [AkaMotion.PlateOffset] out of
 * register and settle into register (one damped overshoot) while [registered] is true,
 * e.g. while the element is pressed.
 */
@Composable
fun plateOffset(registered: Boolean, rest: Dp = AkaMotion.PlateOffset): Dp {
    val target = if (registered) 0.dp else rest
    val offset by animateDpAsState(target, AkaMotion.register(), label = "plateOffset")
    return offset
}

/** Fill, on-fill and the two fringe inks for [kind] in the current scheme. */
@Composable
fun plateInks(kind: PlateKind): PlateInks {
    val inks = AkaTheme.inks
    return when (kind) {
        PlateKind.PRIMARY -> PlateInks(inks.primaryFill, inks.onPrimaryFill, inks.primaryPlateA, inks.primaryPlateB)
        PlateKind.SELECTION -> PlateInks(inks.selectionFill, inks.onSelectionFill, inks.selectionPlateA, inks.selectionPlateB)
    }
}

data class PlateInks(val fill: Color, val onFill: Color, val plateA: Color, val plateB: Color)

/**
 * The primary button: a printed plate that registers when pressed. Use one per screen
 * region for the main action; secondary actions stay Material OutlinedButton / TextButton.
 */
@Composable
fun PlatedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    kind: PlateKind = PlateKind.PRIMARY,
    contentPadding: PaddingValues = PaddingValues(horizontal = FluxSpacing.xl, vertical = FluxSpacing.md),
) {
    val shape = MaterialTheme.shapes.small
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ink = plateInks(kind)
    val offset = plateOffset(registered = pressed || !enabled)
    val fill = if (enabled) ink.fill else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val content = if (enabled) ink.onFill else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = FluxSpacing.touch)
            .misregistered(shape, fill, ink.plateA, ink.plateB) { if (enabled) offset else 0.dp }
            .clip(shape)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides content) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(FluxSpacing.sm))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** State told by form first, ink second (the mark never relies on hue alone). */
enum class MarkState {
    /** Filled disc: running / live. */
    RUNNING,

    /** Open ring: installed, idle, stopped. */
    IDLE,

    /** Dashed ring, turning: starting, installing, working. */
    WORKING,

    /** Half-filled disc: held (the wakelock). */
    HELD,

    /** Struck ring: failed, exited with an error, missing. */
    FAILED,
}

/**
 * A drawn state mark. [label] becomes its content description, so a row that already says
 * the state in words should pass null to avoid reading it twice.
 */
@Composable
fun StatusMark(
    state: MarkState,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    color: Color = markColor(state),
    label: String? = null,
) {
    val reduced = rememberReducedMotion()
    val spin = remember { Animatable(0f) }
    if (state == MarkState.WORKING && !reduced) {
        LaunchedEffect(Unit) {
            while (true) {
                spin.animateTo(360f, tween(1400, easing = { it }))
                spin.snapTo(0f)
            }
        }
    }
    Canvas(
        modifier = modifier
            .size(size)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier)
            .graphicsLayer { rotationZ = if (state == MarkState.WORKING) spin.value else 0f },
    ) {
        val stroke = (this.size.minDimension / 5f).coerceAtLeast(1.5f)
        val r = this.size.minDimension / 2f
        val inner = r - stroke / 2f
        when (state) {
            MarkState.RUNNING -> drawCircle(color, r)
            MarkState.IDLE -> drawCircle(color, inner, style = Stroke(stroke))
            MarkState.WORKING -> drawCircle(
                color,
                inner,
                style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(inner * 0.9f, inner * 0.6f))),
            )
            MarkState.HELD -> {
                drawCircle(color, inner, style = Stroke(stroke))
                drawArc(color, 90f, 180f, useCenter = true, topLeft = Offset.Zero, size = Size(r * 2, r * 2))
            }
            MarkState.FAILED -> {
                drawCircle(color, inner, style = Stroke(stroke))
                drawLine(
                    color,
                    start = Offset(r - inner * 0.75f, r + inner * 0.75f),
                    end = Offset(r + inner * 0.75f, r - inner * 0.75f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Square,
                )
            }
        }
    }
}

@Composable
fun markColor(state: MarkState): Color {
    val inks = AkaTheme.inks
    return when (state) {
        MarkState.RUNNING -> inks.running
        MarkState.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
        MarkState.WORKING -> MaterialTheme.colorScheme.secondary
        MarkState.HELD -> inks.held
        MarkState.FAILED -> inks.failed
    }
}

/**
 * The AkaTerminal wordmark, printed in two passes: a Federal plate under, the inks over
 * ("Aka" in red, "Terminal" in [restColor]). On first composition with [animate] the plates
 * slide in from opposite sides and register with one overshoot; they rest slightly out of
 * register, which is the mark.
 */
@Composable
fun AkaWordmark(
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displaySmall,
    restColor: Color = MaterialTheme.colorScheme.onBackground,
    underPlate: Color = AkaTheme.inks.selectionPlateB,
    animate: Boolean = false,
) {
    val reduced = rememberReducedMotion()
    val start = if (animate && !reduced) 1f else 0f
    val progress = remember { Animatable(start) }
    if (start > 0f) {
        LaunchedEffect(Unit) { progress.animateTo(0f, AkaMotion.register()) }
    }
    val red = MaterialTheme.colorScheme.primary
    val word = buildAnnotatedString {
        withStyle(SpanStyle(color = red)) { append("Aka") }
        withStyle(SpanStyle(color = restColor)) { append("Terminal") }
    }
    Box(modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "AkaTerminal" }) {
        // Under plate: offset down-end, and further out while printing.
        Text(
            text = "AkaTerminal",
            style = style,
            color = underPlate,
            maxLines = 1,
            modifier = Modifier.graphicsLayer {
                val rest = 1.5.dp.toPx()
                val p = progress.value
                translationX = rest + p * 14.dp.toPx()
                translationY = rest + p * 6.dp.toPx()
                alpha = 1f - p * 0.6f
            },
        )
        Text(
            text = word,
            style = style,
            maxLines = 1,
            modifier = Modifier.graphicsLayer {
                val p = progress.value
                translationX = -p * 14.dp.toPx()
                translationY = -p * 6.dp.toPx()
                alpha = 1f - p
            },
        )
    }
}

/** A list group's label (Material list subheader), sentence case. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = modifier.padding(
            start = FluxSpacing.lg,
            end = FluxSpacing.lg,
            top = FluxSpacing.xl,
            bottom = FluxSpacing.sm,
        ),
    )
}

/**
 * A full-width list row: optional leading icon, title, supporting text and trailing slot.
 * The whole row is the touch target (≥ 56dp). Rows sit on the stock, grouped by
 * [SectionLabel]s, not inside cards.
 */
@Composable
fun AkaRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val alpha = if (enabled) 1f else 0.38f
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.md)
            .graphicsLayer { this.alpha = alpha },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(FluxSpacing.lg))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor)
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(FluxSpacing.md))
            trailing()
        }
    }
}

/** Shape helper for callers that print their own plates. */
@Composable
fun plateShape(): Shape = MaterialTheme.shapes.small
