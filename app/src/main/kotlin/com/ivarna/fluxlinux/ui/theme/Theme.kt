package com.ivarna.fluxlinux.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The FluxLinux scheme: cream (primary and secondary, since older screens use `secondary`
 * as the accent) on a warm neutral ramp. Containers step up one neutral per level, so
 * hierarchy comes from tone, not borders or shadows.
 */
private val DarkColorScheme = darkColorScheme(
    primary = BrandCream,
    onPrimary = BrandInk,
    primaryContainer = FluxCreamContainerStrong,
    onPrimaryContainer = BrandCream,
    inversePrimary = Color(0xFF6B5E45),
    secondary = BrandCream,
    onSecondary = BrandInk,
    secondaryContainer = FluxCreamContainer,
    onSecondaryContainer = BrandCream,
    tertiary = Color(0xFFD9C9A8),
    onTertiary = BrandInk,
    tertiaryContainer = FluxCreamContainer,
    onTertiaryContainer = BrandCream,
    background = FluxNeutral1,
    onBackground = FluxOnSurface,
    surface = FluxNeutral3,
    onSurface = FluxOnSurface,
    surfaceVariant = FluxNeutral4,
    onSurfaceVariant = FluxOnSurfaceVariant,
    surfaceTint = BrandCream,
    inverseSurface = FluxOnSurface,
    inverseOnSurface = FluxNeutral4,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = FluxOutline,
    outlineVariant = FluxNeutral6,
    scrim = Color(0xFF000000),
    surfaceBright = FluxNeutral6,
    surfaceDim = FluxNeutral1,
    surfaceContainerLowest = FluxNeutral0,
    surfaceContainerLow = FluxNeutral2,
    surfaceContainer = FluxNeutral3,
    surfaceContainerHigh = FluxNeutral4,
    surfaceContainerHighest = FluxNeutral5,
)

/** Light scheme: only previews use it (the app forces dark). */
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6B5E45),
    onPrimary = Color.White,
    primaryContainer = BrandCream,
    onPrimaryContainer = BrandInk,
    secondary = Color(0xFF6B5E45),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1E4CB),
    onSecondaryContainer = BrandInk,
    tertiary = Color(0xFF6B5E45),
    background = Color(0xFFFBF8F3),
    onBackground = BrandInk,
    surface = Color(0xFFFBF8F3),
    onSurface = BrandInk,
    surfaceVariant = Color(0xFFEDE6DA),
    onSurfaceVariant = Color(0xFF4D463B),
    outline = Color(0xFF7E776B),
    outlineVariant = Color(0xFFD0C8BA),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F1E9),
    surfaceContainer = Color(0xFFF1EBE2),
    surfaceContainerHigh = Color(0xFFEBE5DB),
    surfaceContainerHighest = Color(0xFFE5DFD5),
)

/**
 * App theme. Dynamic (wallpaper) color is off by default: FluxLinux is a branded tool and
 * wallpaper hues leaking into containers made the extra keys and drawer drift per phone.
 */
@Composable
fun FluxLinuxTheme(
    themeMode: com.ivarna.fluxlinux.core.utils.ThemeMode = com.ivarna.fluxlinux.core.utils.ThemeMode.SYSTEM,
    @Suppress("UNUSED_PARAMETER") dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        com.ivarna.fluxlinux.core.utils.ThemeMode.LIGHT -> false
        com.ivarna.fluxlinux.core.utils.ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = FluxShapes,
        content = content
    )
}
