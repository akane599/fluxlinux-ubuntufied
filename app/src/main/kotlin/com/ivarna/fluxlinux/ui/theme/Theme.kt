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
 * Night (the scene: a phone at night, long sessions): sumi ground, the indigo dyes stepping up
 * for surfaces, akane as the one accent. Shiro-ai is secondary (older screens use `secondary`
 * for accent text, so it must read as text), yamabuki is tertiary, tsutsuji is error.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Iro.Akane,
    onPrimary = Color.White,
    primaryContainer = Iro.AkaneWell,
    onPrimaryContainer = Color(0xFFFFD9D6),
    inversePrimary = Iro.Kokiake,
    secondary = Iro.ShiroAi,
    onSecondary = Iro.InkText,
    secondaryContainer = Iro.Konjo,
    onSecondaryContainer = Iro.Shironeri,
    tertiary = Iro.Yamabuki,
    onTertiary = Iro.InkText,
    tertiaryContainer = Color(0xFF3A2C00),
    onTertiaryContainer = Color(0xFFFFE6A6),
    background = Iro.Sumi,
    onBackground = Iro.Shironeri,
    surface = Iro.Sumi,
    onSurface = Iro.Shironeri,
    surfaceVariant = Iro.Kon,
    onSurfaceVariant = Iro.Ginnezumi,
    surfaceTint = Iro.Sumi,
    inverseSurface = Iro.Shironeri,
    inverseOnSurface = Iro.InkText,
    error = Iro.Tsutsuji,
    onError = Iro.InkText,
    errorContainer = Color(0xFF4A1030),
    onErrorContainer = Color(0xFFFFD6E6),
    outline = Iro.Ai,
    outlineVariant = Iro.Hanada,
    scrim = Color(0xFF000000),
    surfaceBright = Iro.Konjo,
    surfaceDim = Iro.Sumi,
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Iro.Kachi,
    surfaceContainer = Iro.Kachi2,
    surfaceContainerHigh = Iro.Kon,
    surfaceContainerHighest = Iro.Konjo,
)

/** Dawn: cool paper, the same dyes deepened where they carry text. */
private val LightColorScheme = lightColorScheme(
    primary = Iro.AkaneDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF8CDCB),
    onPrimaryContainer = Color(0xFF4A0D12),
    inversePrimary = Iro.Akane,
    secondary = Color(0xFF2B4177),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6DFF4),
    onSecondaryContainer = Color(0xFF0B1636),
    tertiary = Color(0xFF6B5200),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE08A),
    onTertiaryContainer = Color(0xFF2E2300),
    background = Iro.Dawn,
    onBackground = Iro.InkText,
    surface = Iro.Dawn,
    onSurface = Iro.InkText,
    surfaceVariant = Color(0xFFE0E5F0),
    onSurfaceVariant = Color(0xFF424A63),
    surfaceTint = Iro.Dawn,
    inverseSurface = Color(0xFF1B2242),
    inverseOnSurface = Iro.Shironeri,
    error = Iro.TsutsujiDeep,
    onError = Color.White,
    errorContainer = Color(0xFFFFD6E6),
    onErrorContainer = Color(0xFF4A0628),
    outline = Color(0xFF4A66AA),
    outlineVariant = Color(0xFFC3CADB),
    scrim = Color(0xFF000000),
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFD8DDE9),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F8FB),
    surfaceContainer = Color(0xFFEAEDF4),
    surfaceContainerHigh = Color(0xFFE2E6F0),
    surfaceContainerHighest = Color(0xFFD8DDE9),
)

/**
 * Colours Material 3 has no role for: the chrome under the terminal, the containers every
 * control sits in (so a button never reads as bare text), and the horizon line.
 */
@Immutable
data class AkaInks(
    /**
     * The chrome beside the terminal: the drawer sheet, and the sunken fill of a disabled
     * button. The extra-keys bar sits on [com.ivarna.fluxlinux.ui.theme.TerminalBackground].
     */
    val chrome: Color,
    val onChrome: Color,
    val onChromeMuted: Color,
    /** A resting container: list rows, key caps, secondary buttons, cards. */
    val tile: Color,
    /** A pressed container, and the selected one (with [controlLine] as its edge). */
    val tilePressed: Color,
    /** A container's quiet edge. */
    val tileEdge: Color,
    /**
     * A control's edge (secondary buttons, selected tiles, switch tracks, key caps while
     * pressed; at rest they take [tileEdge]): ≥ 3:1 on the ground.
     */
    val controlLine: Color,
    /** Primary fills: akane, its pressed dye and the text on it. */
    val primaryFill: Color,
    val primaryPressed: Color,
    val onPrimaryFill: Color,
    /** The horizon: akane at the edges, shu at the brightest point. */
    val horizon: Color,
    val horizonGlow: Color,
    /** State inks: always paired with a [com.ivarna.fluxlinux.ui.components.aka.StatusMark] form. */
    val running: Color,
    val held: Color,
    val failed: Color,
)

private val DarkInks = AkaInks(
    chrome = Iro.Kachi,
    onChrome = Iro.Shironeri,
    onChromeMuted = Iro.Ginnezumi,
    tile = Iro.Kon,
    tilePressed = Iro.Konjo,
    tileEdge = Iro.Hanada,
    controlLine = Iro.Ai,
    primaryFill = Iro.Akane,
    primaryPressed = Iro.Kokiake,
    onPrimaryFill = Color.White,
    horizon = Iro.Akane,
    horizonGlow = Iro.Shu,
    running = Iro.Wakatake,
    held = Iro.Yamabuki,
    failed = Iro.Tsutsuji,
)

private val LightInks = AkaInks(
    chrome = Color(0xFFE6EAF3),
    onChrome = Iro.InkText,
    onChromeMuted = Color(0xFF424A63),
    tile = Color.White,
    tilePressed = Color(0xFFDCE3F3),
    tileEdge = Color(0xFFC3CADB),
    controlLine = Color(0xFF4A66AA),
    primaryFill = Iro.AkaneDeep,
    primaryPressed = Color(0xFF8E1E24),
    onPrimaryFill = Color.White,
    horizon = Iro.AkaneDeep,
    horizonGlow = Color(0xFFD9541C),
    running = Color(0xFF007A45),
    held = Color(0xFF8A6A00),
    failed = Iro.TsutsujiDeep,
)

val LocalAkaInks = staticCompositionLocalOf { DarkInks }

/** Entry point for the inks that have no Material role. */
object AkaTheme {
    val inks: AkaInks
        @Composable @ReadOnlyComposable get() = LocalAkaInks.current
}

/**
 * App theme. Dynamic (wallpaper) colour stays off: AkaTerminal has one fixed set of
 * traditional colours, and wallpaper hues leaking into the shell made it drift per phone.
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
