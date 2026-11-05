package com.ivarna.fluxlinux.core.service

import com.ivarna.fluxlinux.core.service.AppTerminalService.Companion.ACTION_EXIT
import com.ivarna.fluxlinux.core.service.AppTerminalService.Companion.ACTION_TOGGLE_WAKELOCK
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the terminal foreground service does with each start command: notification Exit, the
 * Hold wakelock toggle (notification and drawer) and SessionRegistry's session-count updates.
 * The wake lock never outlives the sessions or the service.
 */
class TerminalServiceCommandsTest {

    private class Recorder(private val live: Int) : TerminalServiceCommands.Actions {
        val calls = mutableListOf<String>()
        override fun liveSessionCount(): Int = live
        override fun exitAll() { calls += "exitAll" }
        override fun toggleWakeLock() { calls += "toggle" }
        override fun releaseWakeLock() { calls += "release" }
        override fun stop() { calls += "stop" }
        override fun showForeground(sessions: Int) { calls += "show:$sessions" }
    }

    private fun start(action: String?, sessionCountExtra: Int = 0, live: Int = 0): List<String> =
        Recorder(live).also { TerminalServiceCommands.onStartCommand(action, sessionCountExtra, it) }.calls

    @Test
    fun exit_closesEverything_thenReleasesAndStops() {
        assertEquals(listOf("exitAll", "release", "stop"), start(ACTION_EXIT, sessionCountExtra = 2, live = 2))
        assertEquals(listOf("exitAll", "release", "stop"), start(ACTION_EXIT))
    }

    @Test
    fun toggle_withoutLiveSessions_releasesAndStops_andNeverAcquires() {
        assertEquals(listOf("release", "stop"), start(ACTION_TOGGLE_WAKELOCK, live = 0))
        // The live count wins over a stale extra: sessions may run without this service.
        assertEquals(listOf("release", "stop"), start(ACTION_TOGGLE_WAKELOCK, sessionCountExtra = 3, live = 0))
    }

    @Test
    fun toggle_withLiveSessions_togglesThenShowsTheLiveCount() {
        assertEquals(listOf("toggle", "show:2"), start(ACTION_TOGGLE_WAKELOCK, live = 2))
        assertEquals(listOf("toggle", "show:1"), start(ACTION_TOGGLE_WAKELOCK, sessionCountExtra = 4, live = 1))
    }

    @Test
    fun sessionCountUpdate_showsTheGivenCount() {
        assertEquals(listOf("show:3"), start(null, sessionCountExtra = 3, live = 5))
        assertEquals(listOf("show:1"), start("some.other.action", sessionCountExtra = 1))
    }

    @Test
    fun noSessionsLeft_releasesAndStops() {
        assertEquals(listOf("release", "stop"), start(null, sessionCountExtra = 0, live = 2))
        assertEquals(listOf("release", "stop"), start(null, sessionCountExtra = -1))
    }

    @Test
    fun destroyed_releasesTheWakeLock() {
        val r = Recorder(live = 1)
        TerminalServiceCommands.onDestroy(r)
        assertEquals(listOf("release"), r.calls)
    }
}
