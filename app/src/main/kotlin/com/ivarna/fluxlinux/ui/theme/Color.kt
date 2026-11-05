package com.ivarna.fluxlinux.ui.theme

import androidx.compose.ui.graphics.Color

// ─── Akane-iro · twilight ─────────────────────────────────────────────────────
// AkaTerminal at dusk: 茜 akane (madder red, the "aka" in the name) is the last light on the
// horizon over 藍 ai (indigo) night. Every colour carries its traditional Japanese name. The
// ground is sumi black with an indigo cast; surfaces step up through the indigo dyes; akane is
// the one accent, for primary actions and the horizon line; state inks (green, yellow, pink)
// always come with a mark form.

/** The traditional colours, as dyed for the dark (night) scheme. Light (dawn) values live in [LightInks]. */
object Iro {
    /** 墨 sumi: the ground. Black with an indigo cast; the terminal itself is pure black. */
    val Sumi = Color(0xFF05060B)
    /** 褐色 kachi-iro: the darkest indigo, the chrome under the terminal (keys bar, drawer). */
    val Kachi = Color(0xFF0B0F1E)
    /** One dye up from kachi: grouped blocks, scrolled bars. */
    val Kachi2 = Color(0xFF0F1528)
    /** 紺 kon: a resting container (rows, key caps, secondary buttons). */
    val Kon = Color(0xFF131B36)
    /** 紺青 konjō: pressed and selected containers. */
    val Konjo = Color(0xFF1B2852)
    /** 縹 hanada: the quiet edge of a container (rows, cards). */
    val Hanada = Color(0xFF2E4377)
    /** 藍 ai: the control line (switch tracks, key caps, outlined buttons; ≥ 3:1 on sumi). */
    val Ai = Color(0xFF4A66AA)
    /** 白藍 shiro-ai: indigo that reads as text (labels, links, progress). */
    val ShiroAi = Color(0xFFA3B8EC)

    /** 茜 akane: the accent. Primary fills with white text (≥ 4.5:1), the horizon line. */
    val Akane = Color(0xFFD2383F)
    /** 深緋 kokiake: akane pressed. */
    val Kokiake = Color(0xFF9E2A33)
    /** 朱 shu: the bright middle of the horizon. */
    val Shu = Color(0xFFEE6A2E)
    /** 茜 on dawn paper: deepened for text and fills on the light scheme. */
    val AkaneDeep = Color(0xFFB7282E)
    /** Akane's dark well (primary containers). */
    val AkaneWell = Color(0xFF3A1218)

    /** 若竹 wakatake: running / live. */
    val Wakatake = Color(0xFF68BE8D)
    /** 山吹 yamabuki: held / look at this. */
    val Yamabuki = Color(0xFFF8B500)
    /** 躑躅 tsutsuji: failed (always with a struck mark). */
    val Tsutsuji = Color(0xFFE95295)
    val TsutsujiDeep = Color(0xFFB4206A)

    /** 白練 shironeri: text on the night. */
    val Shironeri = Color(0xFFEEF0F5)
    /** 銀鼠 ginnezumi: secondary text (≥ 7:1 on sumi and on kon). */
    val Ginnezumi = Color(0xFFA9B0C4)
    /** Dark text on light fills (yamabuki, dawn paper). */
    val InkText = Color(0xFF0B0F1E)

    /** 白 dawn paper: the light scheme ground (cool, never cream). */
    val Dawn = Color(0xFFF2F3F7)
}

/**
 * The terminal's own background: pure black (the user's choice), so the shell reads as a
 * screen on OLED; the chrome around it is sumi and kachi.
 */
val TerminalBackground = Color(0xFF000000)

// ─── Legacy names ─────────────────────────────────────────────────────────────
// Older code still imports these; they resolve to the traditional colours so nothing drifts
// back to an older look. New code reads MaterialTheme.colorScheme / AkaTheme.inks.

val FluxAccentCyan = Iro.ShiroAi
val FluxAccentMagenta = Iro.Tsutsuji

/** Swatches previewing the XFCE desktop themes in setup: content, not app chrome. */
val DesktopThemeSwatchDark = Color(0xFF1E1E24)
val DesktopThemeSwatchLight = Color(0xFFE8EAF6)
