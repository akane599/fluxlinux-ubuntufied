package com.ivarna.fluxlinux.ui.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scrolls stay in the terminal even when the finger drifts sideways, and a swipe towards the
 * drawer still opens it from anywhere on the terminal (not only from the edge strip).
 */
class TerminalGesturesTest {

    private val decideAfter = 4f

    private fun claims(dx: Float, dy: Float, pointers: Int = 1, rtl: Boolean = false) =
        TerminalGestures.terminalClaims(dx, dy, pointers, decideAfter, rtl)

    @Test
    fun undecidedUntilTheFingerHasMoved() {
        assertFalse(claims(0f, 0f))
        assertFalse(claims(-2f, 3f)) // 3.6 < 4: jitter, not yet a direction
    }

    @Test
    fun scrollsWithSidewaysDriftBelongToTheTerminal() {
        assertTrue(claims(0f, -10f))
        assertTrue(claims(0f, 10f))
        assertTrue(claims(5f, -10f)) // drifting right while scrolling up
        assertTrue(claims(-5f, 10f)) // drifting left while scrolling down
        assertTrue(claims(10f, 10.5f)) // just past 45°
    }

    @Test
    fun swipesTowardsTheDrawerAreLeftToIt() {
        assertFalse(claims(10f, 0f))
        assertFalse(claims(10f, -5f)) // about 27° off horizontal
        assertFalse(claims(10f, 10f)) // exactly 45°
    }

    @Test
    fun swipesAwayFromTheDrawerBelongToTheTerminal() {
        assertTrue(claims(-10f, 0f))
        assertTrue(claims(-10f, 2f))
    }

    @Test
    fun secondFingerIsAlwaysTheTerminals() {
        assertTrue(claims(0f, 0f, pointers = 2)) // pinch zoom
        assertTrue(claims(10f, 0f, pointers = 2))
    }

    @Test
    fun rightToLeftLayoutsOpenTheDrawerLeftwards() {
        assertFalse(claims(-10f, 0f, rtl = true))
        assertTrue(claims(10f, 0f, rtl = true))
        assertTrue(claims(0f, 10f, rtl = true))
    }
}
