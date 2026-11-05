package com.ivarna.fluxlinux.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.R
import java.io.File

/**
 * One family, Archivo, ranked by width and weight: the semi-expanded cut is the display voice
 * (headlines, titles, the wordmark), calm rather than shouted at night; the normal width sets
 * body and labels. JetBrains Mono is only for code, paths and key caps. Static instances live
 * in res/font (subset to Latin + Latin Extended); see third_party/fonts/README.md for their
 * provenance.
 *
 * The display cut is a single wght 650 (wdth 112) instance. It is declared as
 * [FontWeight.SemiBold] (600) because it is the only weight in its family, so every
 * SemiBold display or title style resolves to it and renders at 650.
 *
 * The families sit behind one switch: the app loads them from res/font; Compose Preview
 * Screenshot Testing (layoutlib, plugin 0.0.1-alpha10) can't retrieve font resources, so in
 * inspection mode the theme points them at the same files on disk (the render's working
 * directory is the app module). See [usePreviewFontFiles].
 */
private class AkaFamilies(private val fromFiles: Boolean) {
    private fun font(res: Int, file: String, weight: FontWeight): Font =
        if (fromFiles) fileFont(file, weight) else Font(res, weight)

    val wide = FontFamily(font(R.font.archivo_semiexpanded_semibold, "archivo_semiexpanded_semibold", FontWeight.SemiBold))
    val text = FontFamily(
        font(R.font.archivo_regular, "archivo_regular", FontWeight.Normal),
        font(R.font.archivo_medium, "archivo_medium", FontWeight.Medium),
        font(R.font.archivo_semibold, "archivo_semibold", FontWeight.SemiBold),
    )
    val mono = FontFamily(
        font(R.font.jetbrains_mono_regular, "jetbrains_mono_regular", FontWeight.Normal),
        font(R.font.jetbrains_mono_semibold, "jetbrains_mono_semibold", FontWeight.SemiBold),
    )
    val typography = buildTypography(this)
    val code = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp)
}

@OptIn(ExperimentalTextApi::class)
private fun fileFont(name: String, weight: FontWeight): Font = Font(File(PREVIEW_FONT_DIR, "$name.ttf"), weight)

private const val PREVIEW_FONT_DIR = "src/main/res/font"

private val resFamilies by lazy { AkaFamilies(fromFiles = false) }
private val fileFamilies by lazy { AkaFamilies(fromFiles = true) }

@Volatile
private var previewFiles = false

private val families: AkaFamilies get() = if (previewFiles) fileFamilies else resFamilies

/** Inspection mode only (called by the theme): load the fonts from disk when they are there. */
internal fun usePreviewFontFiles() {
    if (!previewFiles && File(PREVIEW_FONT_DIR, "archivo_regular.ttf").isFile) previewFiles = true
}

val ArchivoWide: FontFamily get() = families.wide

val Archivo: FontFamily get() = families.text

val AkaMono: FontFamily get() = families.mono

private fun display(f: AkaFamilies, size: Int, line: Int, tracking: Double) = TextStyle(
    fontFamily = f.wide,
    fontWeight = FontWeight.SemiBold,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

private fun title(f: AkaFamilies, size: Int, line: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = f.wide,
    fontWeight = FontWeight.SemiBold,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

private fun text(f: AkaFamilies, weight: FontWeight, size: Int, line: Int, tracking: Double) = TextStyle(
    fontFamily = f.text,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
)

/**
 * Steps of about 1.1 to 1.25 (44, 36, 30, 27, 23, 20, 16, 14, 12, 11); display tracking
 * tightens as size grows.
 */
private fun buildTypography(f: AkaFamilies) = Typography(
    displayLarge = display(f, 44, 48, -1.0),
    displayMedium = display(f, 36, 42, -0.7),
    displaySmall = display(f, 30, 36, -0.5),
    headlineLarge = display(f, 27, 33, -0.4),
    headlineMedium = display(f, 23, 29, -0.3),
    headlineSmall = display(f, 20, 26, -0.2),
    titleLarge = title(f, 20, 26, -0.1),
    titleMedium = title(f, 16, 22),
    titleSmall = text(f, FontWeight.SemiBold, 14, 20, 0.1),
    bodyLarge = text(f, FontWeight.Normal, 16, 24, 0.1),
    bodyMedium = text(f, FontWeight.Normal, 14, 20, 0.15),
    bodySmall = text(f, FontWeight.Normal, 12, 17, 0.2),
    labelLarge = text(f, FontWeight.SemiBold, 14, 20, 0.1),
    labelMedium = text(f, FontWeight.SemiBold, 12, 16, 0.3),
    labelSmall = text(f, FontWeight.Medium, 11, 16, 0.4),
)

/** The app's type scale. */
val Typography: Typography get() = families.typography

/** Code, paths, commands and key caps. */
val CodeStyle: TextStyle get() = families.code
