package com.ivarna.fluxlinux.ui.theme

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * The motion grammar. Routine state changes are short and decelerate; the one signature
 * motion is the plates settling into register: a spring with one visible overshoot.
 */
object AkaMotion {
    const val QUICK = 120
    const val STANDARD = 220
    const val EMPHASIZED = 360
    /** The wordmark's first print: long enough to read as two passes, never a wait. */
    const val PRINT = 640

    val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Plates registering: one damped overshoot, settles in about 300 ms. */
    fun <T> register(): SpringSpec<T> = spring(dampingRatio = 0.42f, stiffness = 650f)

    /** The same settle without the overshoot (large moves, sheets). */
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 520f)

    fun <T> standard(durationMillis: Int = STANDARD) = tween<T>(durationMillis, easing = Standard)

    /** How far the second plate rests out of register on primary and selected elements. */
    val PlateOffset: Dp = 2.dp
}

/**
 * True when the user has turned animations off (Developer options or Accessibility's
 * "Remove animations"). Compose already scales durations by the animator scale; this is for
 * motion that would otherwise jump, such as the wordmark's entrance offsets.
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
 * Misregistration: the element is the overprint of two plates. [plateA] peeks out
 * top-start and [plateB] bottom-end by [offset]; at 0 the plates are in register and only
 * [fill] shows. Drawn behind the content and outside the bounds, so leave [offset] of room.
 */
fun Modifier.misregistered(
    shape: Shape,
    fill: Color,
    plateA: Color,
    plateB: Color,
    offset: () -> Dp,
): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val d = offset().toPx()
    if (d > 0.01f || d < -0.01f) {
        translate(-d, -d) { drawOutline(outline, plateA) }
        translate(d, d) { drawOutline(outline, plateB) }
    }
    drawOutline(outline, fill)
}

/**
 * Riso grain over a printed field: a tiled speckle of light and dark dots at [alpha].
 * Only for large fields (drawer header, onboarding plates, the Ubuntu status sheet),
 * never behind body text at small sizes.
 */
fun Modifier.risoGrain(alpha: Float): Modifier =
    if (alpha <= 0f) this else drawWithContent {
        drawContent()
        drawRect(brush = GrainTexture.brush, alpha = alpha, topLeft = Offset.Zero, size = size)
    }

private object GrainTexture {
    private const val SIZE = 128

    private val bitmap: ImageBitmap by lazy {
        val rnd = Random(0x41_4B_41) // fixed seed: the same print every launch
        val pixels = IntArray(SIZE * SIZE) {
            when (rnd.nextInt(100)) {
                in 0..13 -> (rnd.nextInt(90, 220) shl 24) or 0xFFFFFF // light speck
                in 14..25 -> (rnd.nextInt(90, 200) shl 24) // dark speck
                else -> 0
            }
        }
        Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    val brush: ShaderBrush by lazy {
        ShaderBrush(ImageShader(bitmap, TileMode.Repeated, TileMode.Repeated))
    }
}
