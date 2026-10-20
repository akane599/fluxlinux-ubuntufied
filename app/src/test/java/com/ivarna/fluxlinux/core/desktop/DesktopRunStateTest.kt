package com.ivarna.fluxlinux.core.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M5: Stop→Start ordering of [DesktopRunState]. */
class DesktopRunStateTest {

    private val state = DesktopRunState()
    private val launched = mutableListOf<String>()

    @Test
    fun startWithoutStop_launchesImmediately() {
        val gen = state.beginStart()
        state.runAfterStops(gen) { launched += "a" }
        assertEquals(listOf("a"), launched)
    }

    @Test
    fun startDuringStop_waitsForStop_andStopDoesNotWriteIdle() {
        val stop = state.beginStop()
        val start = state.beginStart()
        state.runAfterStops(start) { launched += "start" }
        assertTrue("start must not launch while the stop script runs", launched.isEmpty())

        val end = state.endStop(stop)
        assertFalse("a stop superseded by a start must not write Idle", end.isLatest)
        assertNotNull(end.resume)
        end.resume?.invoke()
        assertEquals(listOf("start"), launched)
        assertTrue(state.isCurrent(start))
        assertFalse(state.isStopRunning())
    }

    @Test
    fun stopWithNothingAfter_isLatest() {
        val stop = state.beginStop()
        val end = state.endStop(stop)
        assertTrue(end.isLatest)
        assertNull(end.resume)
    }

    @Test
    fun stopSupersedesStart_startCallbacksAreStale() {
        val start = state.beginStart()
        state.beginStop()
        assertFalse(state.isCurrent(start))
        state.runAfterStops(start) { launched += "stale" }
        assertTrue(launched.isEmpty())
    }

    @Test
    fun secondStopDropsWaitingStart() {
        val stop1 = state.beginStop()
        val start = state.beginStart()
        state.runAfterStops(start) { launched += "dropped" }
        val stop2 = state.beginStop()

        assertNull(state.endStop(stop1).resume)
        val end2 = state.endStop(stop2)
        assertTrue(end2.isLatest)
        assertNull(end2.resume)
        assertTrue(launched.isEmpty())
    }

    @Test
    fun overlappingStops_startWaitsForBoth() {
        val stop1 = state.beginStop()
        val stop2 = state.beginStop()
        val start = state.beginStart()
        state.runAfterStops(start) { launched += "start" }

        assertNull("one stop still running", state.endStop(stop1).resume)
        assertTrue(state.isStopRunning())
        val end2 = state.endStop(stop2)
        assertFalse(end2.isLatest)
        end2.resume?.invoke()
        assertEquals(listOf("start"), launched)
    }

    @Test
    fun newerStartSupersedesOlderStart() {
        val first = state.beginStart()
        val second = state.beginStart()
        assertFalse(state.isCurrent(first))
        assertTrue(state.isCurrent(second))
    }
}
