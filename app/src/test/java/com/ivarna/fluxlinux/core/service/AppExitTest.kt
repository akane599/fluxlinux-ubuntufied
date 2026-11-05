package com.ivarna.fluxlinux.core.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppExitTest {

    private class Recorder(
        private val desktop: String?,
        private val failOn: String? = null,
    ) : AppExit.Actions {
        val calls = mutableListOf<String>()
        private fun record(name: String) {
            calls += name
            if (name == failOn) throw IllegalStateException(name)
        }
        override fun releaseWakeLock() = record("wakelock")
        override fun closeTerminals() = record("terminals")
        override fun activeDesktopId(): String? = desktop
        override fun stopDesktop(distroId: String) = record("desktop:$distroId")
        override fun finishUi() = record("finish")
    }

    @Test
    fun exit_stopsEverythingThenClosesTheUi() {
        val r = Recorder(desktop = "ubuntu_chroot")
        AppExit.run(r)
        assertEquals(listOf("wakelock", "terminals", "desktop:ubuntu_chroot", "finish"), r.calls)
    }

    @Test
    fun exit_withoutDesktop_skipsTheStop() {
        val r = Recorder(desktop = null)
        AppExit.run(r)
        assertEquals(listOf("wakelock", "terminals", "finish"), r.calls)
    }

    @Test
    fun aFailingStep_doesNotLeaveTheOthersUndone() {
        val r = Recorder(desktop = "ubuntu", failOn = "terminals")
        AppExit.run(r)
        assertEquals(listOf("wakelock", "terminals", "desktop:ubuntu", "finish"), r.calls)
    }

    @Test
    fun notification_offersExitNextToHoldWakelock() {
        val cwd = File("").absoluteFile
        val rel = "src/main/kotlin/com/ivarna/fluxlinux/core/service/AppTerminalService.kt"
        val src = listOf(File(cwd, rel), File(cwd, "app/$rel")).first { it.isFile }.readText()
        val wakeAction = src.indexOf("R.string.terminal_wakelock_release else R.string.terminal_wakelock_hold")
        val exitAction = src.indexOf(".addAction(0, getString(R.string.terminal_notification_exit), exit)")
        assertTrue("wakelock action present", wakeAction > 0)
        assertTrue("exit action follows the wakelock action", exitAction > wakeAction)
        val handler = src.indexOf("if (intent?.action == ACTION_EXIT)")
        assertTrue("onStartCommand handles ACTION_EXIT", handler > src.indexOf("override fun onStartCommand"))
        assertTrue("exit runs AppExit", src.indexOf("AppExit.exitAll(this)", handler) > handler)
    }
}
