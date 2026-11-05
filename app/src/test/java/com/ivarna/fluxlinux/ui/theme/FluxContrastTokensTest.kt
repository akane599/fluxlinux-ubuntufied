package com.ivarna.fluxlinux.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG contrast of the riso inks where they carry text or state (dark stock is the app's scheme). */
class FluxContrastTokensTest {

    private fun channelToLinear(c: Float): Double =
        if (c <= 0.04045f) (c / 12.92).toDouble() else ((c + 0.055) / 1.055).toDouble().pow(2.4)

    private fun luminance(color: Color): Double =
        0.2126 * channelToLinear(color.red) + 0.7152 * channelToLinear(color.green) + 0.0722 * channelToLinear(color.blue)

    private fun contrast(c1: Color, c2: Color): Double {
        val l1 = luminance(c1)
        val l2 = luminance(c2)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    private fun assertContrast(name: String, fg: Color, bg: Color, min: Double) {
        val c = contrast(fg, bg)
        assertTrue("$name contrast $c must be >= $min", c >= min)
    }

    @Test
    fun textOnDarkStock() {
        assertContrast("bone on stock", Riso.Bone, Riso.Stock, 7.0)
        assertContrast("muted on stock", Riso.BoneMuted, Riso.Stock, 7.0)
        assertContrast("federal tint on stock", Riso.FederalTint, Riso.Stock, 4.5)
        assertContrast("red on stock", Riso.Red, Riso.Stock, 4.5)
    }

    @Test
    fun textOnShellField() {
        assertContrast("bone on field", Riso.Bone, Riso.FederalField, 7.0)
        assertContrast("muted on field", Riso.BoneOnField, Riso.FederalField, 4.5)
        assertContrast("bone on pressed field", Riso.Bone, Riso.FederalFieldHigh, 4.5)
        assertContrast("bone on selection overprint", Riso.Bone, Riso.Overprint, 7.0)
    }

    @Test
    fun textOnInkFills() {
        assertContrast("ink on red", Riso.InkText, Riso.Red, 4.5)
        assertContrast("ink on vermilion", Riso.InkText, Riso.Vermilion, 4.5)
        assertContrast("ink on yellow", Riso.InkText, Riso.Yellow, 4.5)
    }

    @Test
    fun stateInksReadOnStock() {
        assertContrast("running", Riso.GreenTint, Riso.Stock, 4.5)
        assertContrast("held", Riso.Yellow, Riso.Stock, 4.5)
        assertContrast("failed", Riso.Pink, Riso.Stock, 4.5)
    }

    @Test
    fun lightStockText() {
        assertContrast("ink on light stock", Riso.InkText, Riso.StockLight, 7.0)
        assertContrast("deep red on light stock", Riso.RedDeep, Riso.StockLight, 4.5)
        assertContrast("deep pink on light stock", Riso.PinkDeep, Riso.StockLight, 4.5)
    }

    @Test
    fun switchTrackIsVisibleOnStock() {
        assertContrast("unchecked track vs stock", FluxSwitchUncheckedTrack, Riso.Stock, 1.3)
    }
}
