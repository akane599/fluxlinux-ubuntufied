package com.ivarna.fluxlinux.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ─── Riso inks ────────────────────────────────────────────────────────────────
// AkaTerminal is printed, not rendered: a two-ink risograph sheet. Every colour is a spot
// ink or the stock it is printed on. Red (aka) carries primary actions, Federal Blue is the
// shell field (extra keys, drawer), their overprint marks selection, Yellow means "held /
// look at this", Green means running, Fluorescent Pink means failed (always with a mark).

/** The spot inks, as printed on dark stock. Light-stock variants live in [LightInks]. */
object Riso {
    /** Dark stock: the app ground and the terminal's background. */
    val Stock = Color(0xFF0F1326)
    /** One step under the stock: the terminal well and scrims. */
    val StockDeep = Color(0xFF0A0D1C)
    /** Light stock (light scheme ground). Cool white, never cream. */
    val StockLight = Color(0xFFEFF1F5)

    /** Federal Blue ink. */
    val Federal = Color(0xFF3255A4)
    /** Federal Blue laid down as a solid field on dark stock: the shell. */
    val FederalField = Color(0xFF1B2A5C)
    /** One printed tint up from the field: pressed keys, hovered rows. */
    val FederalFieldHigh = Color(0xFF243670)
    /** Federal ink tint that reads as text on dark stock. */
    val FederalTint = Color(0xFFA9BCF2)
    /** The one hairline: outlines, unchecked controls (≥ 3:1 on dark stock). */
    val FederalLine = Color(0xFF5D78C4)
    /** The hairline on light stock. */
    val FederalLineDeep = Color(0xFF3F5AA8)

    /** Bright Red: aka, the brand ink. */
    val Red = Color(0xFFF15060)
    /** Red ink deepened for text and fills on light stock. */
    val RedDeep = Color(0xFFC62E3F)

    /** Red over Federal Blue: the overprint that marks selection. */
    val Overprint = Color(0xFF4A2150)
    /** Red over Yellow: the overprint of primary buttons. */
    val Vermilion = Color(0xFFF25C3C)

    val Yellow = Color(0xFFFFE800)
    val Green = Color(0xFF00A95C)
    val GreenTint = Color(0xFF3FD98A)
    val Pink = Color(0xFFFF48B0)
    val PinkDeep = Color(0xFFC2186B)

    /** Cool bone: text on dark stock and on the shell field. */
    val Bone = Color(0xFFE9ECF2)
    /** Secondary text on dark stock (≥ 7:1 on [Stock]). */
    val BoneMuted = Color(0xFFB4BCD3)
    /** Secondary text on the Federal field (≥ 4.5:1 on [FederalField]). */
    val BoneOnField = Color(0xFFC3CDEB)
    /** Ink-black text on light stock and on Red / Yellow / Vermilion. */
    val InkText = Color(0xFF14193A)
}

/** The terminal's own background: the dark stock, so chrome and terminal read as one sheet. */
val TerminalBackground = Riso.Stock

// ─── Legacy names ─────────────────────────────────────────────────────────────
// Older screens still import these; they now resolve to the riso inks so nothing drifts
// back to the old cream/neon look. New code reads MaterialTheme.colorScheme / AkaTheme.inks.

val FluxSuccess = Riso.GreenTint
val FluxAccentCyan = Riso.FederalTint
val FluxAccentMagenta = Riso.Pink
val FluxBackgroundStart = Riso.Stock
val FluxBackgroundMid = Riso.Stock
val FluxBackgroundEnd = Riso.Stock
val Seed = Riso.Red
val FluxBodyMuted = Riso.BoneMuted
val FluxHairline = Color(0x33A9BCF2)
val FluxCardFill = Color(0xFF171D38)
val FluxDanger = Riso.Pink
val FluxSwitchUncheckedTrack = Color(0xFF242C50)

@Composable
fun fluxMutedText(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/** Swatches previewing the XFCE desktop themes in setup: content, not app chrome. */
val DesktopThemeSwatchDark = Color(0xFF1E1E24)
val DesktopThemeSwatchLight = Color(0xFFE8EAF6)
