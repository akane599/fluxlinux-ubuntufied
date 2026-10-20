package com.ivarna.fluxlinux.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyRepeatTest {

    @Test
    fun accelerated_firstRepeatStartsAbout400msAfterPressDown() {
        assertEquals(400L, KeyRepeat.PRESS_CONFIRM_DELAY_MS + KeyRepeat.delayBeforeRepeat(0, accelerated = true))
    }

    @Test
    fun accelerated_repeatingStartsAt100msInterval() {
        assertEquals(100L, KeyRepeat.delayBeforeRepeat(1, accelerated = true))
    }

    @Test
    fun accelerated_intervalsAfterFirstRepeatAreMonotonicNonIncreasing() {
        var previous = KeyRepeat.delayBeforeRepeat(1, accelerated = true)
        for (n in 2..200) {
            val current = KeyRepeat.delayBeforeRepeat(n, accelerated = true)
            assertTrue("repeat $n: $current > $previous", current <= previous)
            previous = current
        }
    }

    @Test
    fun accelerated_floorIsReachedWithinAboutOneSecondOfHolding() {
        var elapsed = KeyRepeat.PRESS_CONFIRM_DELAY_MS
        var n = 0
        while (KeyRepeat.delayBeforeRepeat(n, accelerated = true) > KeyRepeat.MIN_INTERVAL_MS) {
            elapsed += KeyRepeat.delayBeforeRepeat(n, accelerated = true)
            n++
        }
        assertEquals(30L, KeyRepeat.MIN_INTERVAL_MS)
        assertTrue("floor reached at ${elapsed}ms", elapsed <= 1_000L)
    }

    @Test
    fun accelerated_everyDelayStaysBetweenFloorAndInitialDelay() {
        for (n in listOf(0, 1, 2, 7, 8, 9, 100, 10_000, Int.MAX_VALUE)) {
            val d = KeyRepeat.delayBeforeRepeat(n, accelerated = true)
            assertTrue("repeat $n: $d", d in KeyRepeat.MIN_INTERVAL_MS..KeyRepeat.REPEAT_START_MS)
        }
        assertEquals(KeyRepeat.MIN_INTERVAL_MS, KeyRepeat.delayBeforeRepeat(Int.MAX_VALUE, accelerated = true))
    }

    @Test
    fun constant_firstRepeatStartsAt400msLikeTermuxLongPressTimeout() {
        assertEquals(400L, KeyRepeat.PRESS_CONFIRM_DELAY_MS + KeyRepeat.delayBeforeRepeat(0, accelerated = false))
    }

    @Test
    fun constant_laterRepeatsUseTermuxFixed80msDelay() {
        assertEquals(80L, KeyRepeat.CONSTANT_INTERVAL_MS)
        for (n in listOf(1, 2, 7, 100, 10_000, Int.MAX_VALUE)) {
            assertEquals("repeat $n", 80L, KeyRepeat.delayBeforeRepeat(n, accelerated = false))
        }
    }

    @Test
    fun constant_holdOfTwoSecondsFiresSteadily() {
        // 100 ms confirm fire, 400 ms first repeat, then every 80 ms: 1 + 1 + 20 fires by 2 s.
        var elapsed = KeyRepeat.PRESS_CONFIRM_DELAY_MS
        var fires = 1
        var n = 0
        while (true) {
            val next = elapsed + KeyRepeat.delayBeforeRepeat(n++, accelerated = false)
            if (next > 2_000L) break
            elapsed = next
            fires++
        }
        assertEquals(22, fires)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeRepeatIndexIsRejected() {
        KeyRepeat.delayBeforeRepeat(-1, accelerated = false)
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
