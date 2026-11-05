package com.ivarna.fluxlinux.ui.components.aka

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.ivarna.fluxlinux.ui.theme.horizon
import com.ivarna.fluxlinux.ui.theme.rememberReducedMotion

/** What a button is for: the one main action of a region, or everything else. */
enum class AkaButtonKind {
    /** Akane fill, white text: one per screen region. */
    PRIMARY,

    /** A container in the night's dye with the ai control line: always reads as a button. */
    SECONDARY,
}

/**
 * Every control sits in a container: [com.ivarna.fluxlinux.ui.theme.AkaInks.tile] at rest,
 * `tilePressed` while [pressed] or [selected], edged by `tileEdge` (or the brighter
 * `controlLine` when [selected] or a [control]). So a row, a key or a secondary button never
 * reads as bare text on the ground.
 */
@Composable
fun Modifier.akaTile(
    shape: Shape = MaterialTheme.shapes.medium,
    pressed: Boolean = false,
    selected: Boolean = false,
    control: Boolean = false,
): Modifier {
    val inks = AkaTheme.inks
    val fill by animateColorAsState(
        if (pressed || selected) inks.tilePressed else inks.tile,
        AkaMotion.standard(AkaMotion.QUICK),
        label = "tileFill",
    )
    val edge = if (selected || control) inks.controlLine else inks.tileEdge
    return this
        .clip(shape)
        .background(fill)
        .border(1.dp, edge, shape)
}

/**
 * A choice among siblings (a segment, an option, the current session): a tile, and when
 * [selected] the konjō container with the ai edge and the horizon rising along its bottom.
 * Content on it keeps the night's text (`colorScheme.onSurface`): the container says "selected".
 */
@Composable
fun Modifier.akaSelection(
    selected: Boolean,
    pressed: Boolean = false,
    shape: Shape = MaterialTheme.shapes.medium,
): Modifier {
    val inks = AkaTheme.inks
    val risen by animateFloatAsState(if (selected) 1f else 0f, AkaMotion.rise(), label = "selectionHorizon")
    return this
        .akaTile(shape, pressed = pressed, selected = selected)
        .horizon(inks.horizon, inks.horizonGlow, risen = { risen })
}

/**
 * A button. [AkaButtonKind.PRIMARY] is the akane fill that deepens to kokiake while pressed;
 * [AkaButtonKind.SECONDARY] is a container with the ai control line. Disabled buttons keep
 * a container in the darker kachi with the quiet hanada edge and dimmed content, so the
 * shape of the action stays visible without reading as available.
 */
@Composable
fun AkaButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    kind: AkaButtonKind = AkaButtonKind.PRIMARY,
    contentPadding: PaddingValues = PaddingValues(horizontal = FluxSpacing.xl, vertical = FluxSpacing.md),
) {
    val shape = MaterialTheme.shapes.small
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val inks = AkaTheme.inks
    val primary = kind == AkaButtonKind.PRIMARY && enabled
    val fill by animateColorAsState(
        when {
            primary && pressed -> inks.primaryPressed
            primary -> inks.primaryFill
            // Disabled sinks below a resting button: the chrome's darker dye, never a lighter one.
            !enabled -> inks.chrome
            pressed -> inks.tilePressed
            else -> inks.tile
        },
        AkaMotion.standard(AkaMotion.QUICK),
        label = "buttonFill",
    )
    val edge = when {
        primary -> Color.Transparent
        enabled -> inks.controlLine
        else -> inks.tileEdge
    }
    val content = when {
        primary -> inks.onPrimaryFill
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 64.dp, minHeight = FluxSpacing.touch)
            .clip(shape)
            .background(fill)
            .border(1.dp, edge, shape)
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
 * The AkaTerminal wordmark: "Aka" in akane, "Terminal" in [restColor], over a horizon line.
 * With [animate] the horizon rises on first composition (the line grows out from the centre
 * and its light lifts); reduced motion shows it risen.
 */
@Composable
fun AkaWordmark(
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displaySmall,
    restColor: Color = MaterialTheme.colorScheme.onBackground,
    animate: Boolean = false,
) {
    val reduced = rememberReducedMotion()
    val start = if (animate && !reduced) 0f else 1f
    val risen = remember { Animatable(start) }
    if (start < 1f) {
        LaunchedEffect(Unit) { risen.animateTo(1f, tween(AkaMotion.RISE, easing = AkaMotion.Decelerate)) }
    }
    val inks = AkaTheme.inks
    val word = buildAnnotatedString {
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("Aka") }
        withStyle(SpanStyle(color = restColor)) { append("Terminal") }
    }
    // One node for accessibility: the word and its horizon are one mark.
    Box(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = "AkaTerminal" }
            .horizon(inks.horizon, inks.horizonGlow, risen = { risen.value })
            .padding(bottom = FluxSpacing.sm),
    ) {
        Text(text = word, style = style, maxLines = 1)
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
            start = FluxSpacing.lg + FluxSpacing.xs,
            end = FluxSpacing.lg,
            top = FluxSpacing.xl,
            bottom = FluxSpacing.sm,
        ),
    )
}

/**
 * True inside a container that already draws the tile (a settings block): rows in it lie
 * flat instead of drawing a tile of their own.
 */
val LocalInTile = staticCompositionLocalOf { false }

/**
 * A full-width list row: optional leading icon, title, supporting text and trailing slot.
 * The whole row is the touch target (≥ 56dp) and sits in its own tile, inset from the screen
 * edge, so every row reads as something to press. Inside a block ([LocalInTile]) it lies flat.
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
    val inTile = LocalInTile.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (inTile) Modifier
                else Modifier
                    .padding(horizontal = FluxSpacing.lg, vertical = TileGap)
                    .akaTile(MaterialTheme.shapes.medium, pressed = pressed)
            )
            .then(modifier)
            .heightIn(min = 56.dp)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        enabled = enabled,
                        onClick = onClick,
                    )
                } else Modifier
            )
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

/** Half the gap between two stacked tiles. */
val TileGap: Dp = 3.dp
