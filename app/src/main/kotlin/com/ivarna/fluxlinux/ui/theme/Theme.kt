package com.ivarna.fluxlinux.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Dark stock (the scene: a phone at night, long sessions): black paper. Surfaces step up in
 * neutral greys, so hierarchy comes from tone and the inks stay the only colour. Red is primary, the Federal tint is
 * secondary (older screens use `secondary` for accent text, so it must read as text),
 * Yellow is tertiary, Fluorescent Pink is error.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Riso.Red,
    onPrimary = Riso.InkText,
    primaryContainer = Riso.Overprint,
    onPrimaryContainer = Color(0xFFFFD9DD),
    inversePrimary = Riso.RedDeep,
    secondary = Riso.FederalTint,
    onSecondary = Riso.InkText,
    secondaryContainer = Riso.FederalField,
    onSecondaryContainer = Riso.Bone,
    tertiary = Riso.Yellow,
    onTertiary = Riso.InkText,
    tertiaryContainer = Color(0xFF3D3A12),
    onTertiaryContainer = Color(0xFFFFF4A3),
    background = Riso.Stock,
    onBackground = Riso.Bone,
    surface = Riso.Stock,
    onSurface = Riso.Bone,
    surfaceVariant = Color(0xFF1C1D21),
    onSurfaceVariant = Riso.BoneMuted,
    surfaceTint = Riso.Stock,
    inverseSurface = Riso.Bone,
    inverseOnSurface = Riso.InkText,
    error = Riso.Pink,
    onError = Riso.InkText,
    errorContainer = Color(0xFF55123F),
    onErrorContainer = Color(0xFFFFD6EA),
    outline = Riso.FederalLine,
    outlineVariant = Color(0xFF2A2C33),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2D2F35),
    surfaceDim = Riso.StockDeep,
    surfaceContainerLowest = Riso.StockDeep,
    surfaceContainerLow = Color(0xFF0F1012),
    surfaceContainer = Color(0xFF151619),
    surfaceContainerHigh = Color(0xFF1C1D21),
    surfaceContainerHighest = Color(0xFF24262B),
)

/** Light stock: cool white, the same inks deepened where they carry text. */
private val LightColorScheme = lightColorScheme(
    primary = Riso.RedDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF8CDD2),
    onPrimaryContainer = Color(0xFF4A0D17),
    inversePrimary = Riso.Red,
    secondary = Color(0xFF2A4A94),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD5DEF4),
    onSecondaryContainer = Color(0xFF0F1A40),
    tertiary = Color(0xFF6B5D00),
    onTertiary = Color.White,
    tertiaryContainer = Riso.Yellow,
    onTertiaryContainer = Color(0xFF2E2900),
    background = Riso.StockLight,
    onBackground = Riso.InkText,
    surface = Riso.StockLight,
    onSurface = Riso.InkText,
    surfaceVariant = Color(0xFFE1E5EE),
    onSurfaceVariant = Color(0xFF444B66),
    surfaceTint = Riso.StockLight,
    inverseSurface = Color(0xFF242A45),
    inverseOnSurface = Riso.Bone,
    error = Riso.PinkDeep,
    onError = Color.White,
    errorContainer = Color(0xFFFFD6EA),
    onErrorContainer = Color(0xFF4A0628),
    outline = Riso.FederalLineDeep,
    outlineVariant = Color(0xFFC3C8D6),
    scrim = Color(0xFF000000),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFD9DEE9),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F7FA),
    surfaceContainer = Color(0xFFE8EBF1),
    surfaceContainerHigh = Color(0xFFE1E5EE),
    surfaceContainerHighest = Color(0xFFD9DEE9),
)

/**
 * Inks Material 3 has no role for. The shell field (extra keys, drawer, onboarding plates)
 * is Federal Blue in both schemes: it is the app's printed sheet, not a theme surface.
 */
@Immutable
data class AkaInks(
    val shellField: Color,
    val shellFieldHigh: Color,
    val onShellField: Color,
    val onShellFieldMuted: Color,
    val shellHairline: Color,
    /**
     * Primary plates: the Bright Red fill and the two misregistered fringes. The second plate
     * is Federal, the same pair as the wordmark and the mark; Yellow stays for held state.
     */
    val primaryFill: Color,
    val onPrimaryFill: Color,
    val primaryPlateA: Color,
    val primaryPlateB: Color,
    /** Selection plates: overprint fill and fringes, on the shell field. */
    val selectionFill: Color,
    val onSelectionFill: Color,
    val selectionPlateA: Color,
    val selectionPlateB: Color,
    /** State inks: always paired with a [com.ivarna.fluxlinux.ui.components.aka.StatusMark] form. */
    val running: Color,
    val held: Color,
    val failed: Color,
    val idle: Color,
    /** Grain strength over large printed fields (0 disables it). */
    val grainAlpha: Float,
    val isDark: Boolean,
)

private val DarkInks = AkaInks(
    shellField = Riso.FederalField,
    shellFieldHigh = Riso.FederalFieldHigh,
    onShellField = Riso.Bone,
    onShellFieldMuted = Riso.BoneOnField,
    shellHairline = Color(0x40A9BCF2),
    primaryFill = Riso.Red,
    onPrimaryFill = Riso.InkText,
    primaryPlateA = Riso.Red,
    primaryPlateB = Riso.Federal,
    selectionFill = Riso.Overprint,
    onSelectionFill = Riso.Bone,
    selectionPlateA = Riso.Red,
    selectionPlateB = Riso.Federal,
    running = Riso.GreenTint,
    held = Riso.Yellow,
    failed = Riso.Pink,
    idle = Riso.BoneOnField,
    grainAlpha = 0.07f,
    isDark = true,
)

private val LightInks = AkaInks(
    shellField = Color(0xFF2A4A94),
    shellFieldHigh = Color(0xFF34569F),
    onShellField = Color.White,
    onShellFieldMuted = Color(0xFFD5DEF4),
    shellHairline = Color(0x4DFFFFFF),
    primaryFill = Riso.Red,
    onPrimaryFill = Riso.InkText,
    primaryPlateA = Riso.RedDeep,
    primaryPlateB = Color(0xFF2A4A94),
    selectionFill = Color(0xFF6E2C66),
    onSelectionFill = Color.White,
    selectionPlateA = Riso.Red,
    selectionPlateB = Color(0xFF7D9BE0),
    running = Color(0xFF007A45),
    held = Color(0xFF8A7700),
    failed = Riso.PinkDeep,
    idle = Color(0xFF444B66),
    grainAlpha = 0.06f,
    isDark = false,
)

val LocalAkaInks = staticCompositionLocalOf { DarkInks }

/** Entry point for the inks that have no Material role. */
object AkaTheme {
    val inks: AkaInks
        @Composable @ReadOnlyComposable get() = LocalAkaInks.current
}

/**
 * App theme. Dynamic (wallpaper) colour stays off: AkaTerminal is a printed object with a
 * fixed set of inks, and wallpaper hues leaking into the shell made it drift per phone.
 */
@Composable
fun FluxLinuxTheme(
    themeMode: com.ivarna.fluxlinux.core.utils.ThemeMode = com.ivarna.fluxlinux.core.utils.ThemeMode.SYSTEM,
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        com.ivarna.fluxlinux.core.utils.ThemeMode.LIGHT -> false
        com.ivarna.fluxlinux.core.utils.ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val inks = if (darkTheme) DarkInks else LightInks

    val view = LocalView.current
    if (LocalInspectionMode.current) usePreviewFontFiles()
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalAkaInks provides inks) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = FluxShapes,
            content = content,
        )
    }
}
