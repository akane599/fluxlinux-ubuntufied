package com.ivarna.fluxlinux.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRepeatTest {

    @Test
    fun firstRepeatStartsAbout400msAfterPressDown() {
        assertEquals(400L, KeyRepeat.PRESS_CONFIRM_DELAY_MS + KeyRepeat.delayBeforeRepeat(0))
    }

    @Test
    fun repeatingStartsAt100msInterval() {
        assertEquals(100L, KeyRepeat.delayBeforeRepeat(1))
    }

    @Test
    fun intervalsAfterFirstRepeatAreMonotonicNonIncreasing() {
        var previous = KeyRepeat.delayBeforeRepeat(1)
        for (n in 2..200) {
            val current = KeyRepeat.delayBeforeRepeat(n)
            assertTrue("repeat $n: $current > $previous", current <= previous)
            previous = current
        }
    }

    @Test
    fun floorIsReachedWithinAboutOneSecondOfHolding() {
        var elapsed = KeyRepeat.PRESS_CONFIRM_DELAY_MS
        var n = 0
        while (KeyRepeat.delayBeforeRepeat(n) > KeyRepeat.MIN_INTERVAL_MS) {
            elapsed += KeyRepeat.delayBeforeRepeat(n)
            n++
        }
        assertEquals(30L, KeyRepeat.MIN_INTERVAL_MS)
        assertTrue("floor reached at ${elapsed}ms", elapsed <= 1_000L)
    }

    @Test
    fun everyDelayStaysBetweenFloorAndInitialDelay() {
        for (n in listOf(0, 1, 2, 7, 8, 9, 100, 10_000, Int.MAX_VALUE)) {
            val d = KeyRepeat.delayBeforeRepeat(n)
            assertTrue("repeat $n: $d", d in KeyRepeat.MIN_INTERVAL_MS..KeyRepeat.REPEAT_START_MS)
        }
        assertEquals(KeyRepeat.MIN_INTERVAL_MS, KeyRepeat.delayBeforeRepeat(Int.MAX_VALUE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeRepeatIndexIsRejected() {
        KeyRepeat.delayBeforeRepeat(-1)
    }

    @Test
    fun navigationAndDeleteKeysRepeatButOneShotKeysDoNot() {
        listOf("UP", "DOWN", "LEFT", "RIGHT", "PGUP", "PGDN", "BKSP", "DEL").forEach {
            assertTrue(it, KeyRepeat.isRepeatable(it))
        }
        listOf("ESC", "TAB", "ENTER", "INS", "F1", "/").forEach {
            assertFalse(it, KeyRepeat.isRepeatable(it))
        }
    }
}
