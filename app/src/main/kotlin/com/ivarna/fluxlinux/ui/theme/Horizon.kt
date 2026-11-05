package com.ivarna.fluxlinux.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The motion grammar. Routine state changes are short and decelerate; the one signature
 * motion is the horizon rising: a slow settle, like light coming up, never a bounce.
 */
object AkaMotion {
    const val QUICK = 120
    const val STANDARD = 220
    const val EMPHASIZED = 360
    /** The wordmark's horizon on first launch: long enough to read as a sunrise, never a wait. */
    const val RISE = 720

    val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** The horizon rising (drawer opening, a session selected): no overshoot, ~400 ms. */
    fun <T> rise(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 180f)

    /** A quicker settle without overshoot (thumbs, sheets). */
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f)

    fun <T> standard(durationMillis: Int = STANDARD) = tween<T>(durationMillis, easing = Standard)

    /** The horizon line's weight. */
    val HorizonWidth: Dp = 2.dp
    /** How far the glow above the line reaches when fully risen. */
    val HorizonGlow: Dp = 14.dp
}

/**
 * True when the user has turned animations off (Developer options or Accessibility's
 * "Remove animations"). Compose already scales durations by the animator scale; this is for
 * motion that would otherwise jump, such as the horizon's first rise.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/**
 * The signature: a horizon along the bottom edge. A [AkaMotion.HorizonWidth] line, akane at
 * the ends and [glow] (shu) at its brightest point in the middle, with the sunset light
 * fading up from it. [risen] (0..1) grows the line out from the centre and lifts the glow, so
 * animating it reads as the sun's last light coming up. Drawn over the content, inside the
 * bounds; only for the current item (the selected session, the wordmark), never as decoration.
 */
fun Modifier.horizon(
    line: Color,
    glow: Color,
    risen: () -> Float = { 1f },
    inset: Dp = 0.dp,
): Modifier = drawWithContent {
    drawContent()
    val p = risen().coerceIn(0f, 1f)
    val stroke = AkaMotion.HorizonWidth.toPx()
    val pad = inset.toPx()
    val full = size.width - pad * 2
    val w = full * p
    // Nothing to draw yet (or a degenerate gradient, which some GPU drivers can't take).
    if (w < 1f || size.height < stroke) return@drawWithContent
    val left = pad + (full - w) / 2f
    val y = size.height - stroke
    val glowHeight = minOf(AkaMotion.HorizonGlow.toPx() * p, y)
    // Sunset light: strongest at the middle of the line, fading up and out towards its ends.
    // A radial gradient squashed into a half-ellipse: no layers or blend modes, which some GPU
    // drivers (the emulator's swiftshader) can't take.
    if (glowHeight >= 1f) {
        val cx = left + w / 2f
        val r = w / 2f
        scale(scaleX = 1f, scaleY = glowHeight / r, pivot = Offset(cx, y)) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(line.copy(alpha = 0.26f * p), glow.copy(alpha = 0.10f * p), Color.Transparent),
                    center = Offset(cx, y),
                    radius = r,
                ),
                topLeft = Offset(left, y - r),
                size = Size(w, r),
            )
        }
    }
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(line.copy(alpha = 0f), line, glow, line, line.copy(alpha = 0f)),
            startX = left,
            endX = left + w,
        ),
        topLeft = Offset(left, y),
        size = Size(w, stroke),
    )
}
