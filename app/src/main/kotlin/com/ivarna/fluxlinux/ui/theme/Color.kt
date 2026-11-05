package com.ivarna.fluxlinux.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

// ─── Brand ────────────────────────────────────────────────────────────────────
// FluxLinux is warm cream on warm dark. Cream is the one accent: primary actions,
// the current selection and active state. Everything else is a warm neutral.

/** The brand cream. Primary actions, selection, active modifiers. */
val BrandCream = Color(0xFFF5E6CA)
/** Text and icons on cream. */
val BrandInk = Color(0xFF23201A)

// Warm neutral ramp (dark scheme), lowest → highest.
val FluxNeutral0 = Color(0xFF0E0D0C)
val FluxNeutral1 = Color(0xFF131211)
val FluxNeutral2 = Color(0xFF1A1917)
val FluxNeutral3 = Color(0xFF1F1E1B)
val FluxNeutral4 = Color(0xFF282622)
val FluxNeutral5 = Color(0xFF322F2B)
val FluxNeutral6 = Color(0xFF3A3732)
val FluxOutline = Color(0xFF8C857A)
val FluxOnSurface = Color(0xFFEDE8E0)
val FluxOnSurfaceVariant = Color(0xFFC9C2B6)
val FluxCreamContainer = Color(0xFF3A352C)
val FluxCreamContainerStrong = Color(0xFF4A4235)

/** Status: a running session/desktop. Only for state, never decoration. */
val FluxSuccess = Color(0xFF8FD6A8)

/** The terminal's own background (TerminalView's renderer paints #000000). */
val TerminalBackground = Color(0xFF000000)

// ─── Legacy names still referenced by older screens ───────────────────────────
val FluxCream = Color(0xFFFDFCF0)
val FluxDarkGrey = Color(0xFF1A1C1E)
val FluxDarkSurface = FluxNeutral1

/** Legacy accents. Do not use in new or restyled UI: the accent is cream. */
val FluxAccentCyan = Color(0xFF00E5FF)
val FluxAccentMagenta = Color(0xFFFF00E6)

// Glass defaults (deprecated, kept for compilation of older screens)
val FluxBackgroundStart = Color(0xFF0F0C29)
val FluxBackgroundMid = Color(0xFF302B63)
val FluxBackgroundEnd = Color(0xFF24243E)
val GlassWhiteHigh = Color(0x26FFFFFF)
val GlassWhiteMedium = Color(0x1AFFFFFF)
val GlassWhiteLow = Color(0x0DFFFFFF)
val GlassBorder = Color(0x4DFFFFFF)
val TextWhite = Color(0xFFFFFFFF)
val TextGrey = Color(0xFFDDDDDD)
val Seed = Color(0xFF00E5FF)

// Contrast-safe on the dark ground
val FluxBodyMuted = FluxOnSurfaceVariant
val FluxHairline = Color(0x33FFFFFF)
val FluxCardFill = Color(0xE61A1C1E)
val FluxDanger = Color(0xFFC62828)
val FluxSwitchCheckedTrack = BrandCream
val FluxSwitchCheckedThumb = BrandInk
val FluxSwitchUncheckedTrack = FluxNeutral5
val FluxSwitchUncheckedThumb = Color(0xFFE8E8E8)
val FluxSwitchUncheckedBorder = Color(0xFF6B6B6B)

@Composable
fun fluxMutedText(): Color {
    return if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        FluxBodyMuted
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** Swatches previewing the XFCE desktop themes in setup: content, not app chrome. */
val DesktopThemeSwatchDark = Color(0xFF1E1E24)
val DesktopThemeSwatchLight = Color(0xFFE8EAF6)
