package com.ivarna.fluxlinux.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG contrast of the traditional colours where they carry text, state or a control edge (night is the app's scheme). */
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
    fun textOnTheNight() {
        assertContrast("shironeri on sumi", Iro.Shironeri, Iro.Sumi, 7.0)
        assertContrast("ginnezumi on sumi", Iro.Ginnezumi, Iro.Sumi, 7.0)
        assertContrast("shiro-ai on sumi", Iro.ShiroAi, Iro.Sumi, 4.5)
        assertContrast("akane (wordmark, large) on sumi", Iro.Akane, Iro.Sumi, 3.0)
    }

    @Test
    fun textInsideContainers() {
        assertContrast("shironeri on kon", Iro.Shironeri, Iro.Kon, 7.0)
        assertContrast("ginnezumi on kon", Iro.Ginnezumi, Iro.Kon, 4.5)
        assertContrast("ginnezumi on konjo", Iro.Ginnezumi, Iro.Konjo, 4.5)
        assertContrast("shironeri on kachi", Iro.Shironeri, Iro.Kachi, 7.0)
        assertContrast("shiro-ai on kon", Iro.ShiroAi, Iro.Kon, 4.5)
    }

    @Test
    fun fillsCarryTheirText() {
        assertContrast("white on akane", Color.White, Iro.Akane, 4.5)
        assertContrast("white on kokiake", Color.White, Iro.Kokiake, 4.5)
        assertContrast("ink on yamabuki", Iro.InkText, Iro.Yamabuki, 4.5)
    }

    @Test
    fun controlsReadAgainstTheGround() {
        // WCAG 1.4.11: a control's boundary needs 3:1 against what it sits on.
        assertContrast("ai line on sumi", Iro.Ai, Iro.Sumi, 3.0)
        assertContrast("ai line on kachi", Iro.Ai, Iro.Kachi, 3.0)
        assertContrast("ai line on kon", Iro.Ai, Iro.Kon, 3.0)
        assertContrast("akane fill on sumi", Iro.Akane, Iro.Sumi, 3.0)
        assertContrast("hanada tile edge on sumi", Iro.Hanada, Iro.Sumi, 1.8)
    }

    @Test
    fun statesReadOnTheNight() {
        assertContrast("running", Iro.Wakatake, Iro.Sumi, 4.5)
        assertContrast("held", Iro.Yamabuki, Iro.Sumi, 4.5)
        assertContrast("failed", Iro.Tsutsuji, Iro.Sumi, 4.5)
        assertContrast("failed on kachi", Iro.Tsutsuji, Iro.Kachi, 4.5)
    }

    @Test
    fun dawnScheme() {
        assertContrast("ink on dawn", Iro.InkText, Iro.Dawn, 7.0)
        assertContrast("deep akane on dawn", Iro.AkaneDeep, Iro.Dawn, 4.5)
        assertContrast("white on deep akane", Color.White, Iro.AkaneDeep, 4.5)
        assertContrast("deep tsutsuji on dawn", Iro.TsutsujiDeep, Iro.Dawn, 4.5)
    }
}
