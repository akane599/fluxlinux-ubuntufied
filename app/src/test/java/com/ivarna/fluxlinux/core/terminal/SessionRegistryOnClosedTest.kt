package com.ivarna.fluxlinux.core.terminal

import com.ivarna.fluxlinux.core.system.SafeFiles
import com.termux.terminal.TerminalSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * L2: closing a running tab (closeSession / closeAll) must still fire
 * [SessionRegistry.ManagedSession.onClosed] (uninstall refresh, chroot helper
 * invalidation), exactly once even when the process exit arrives too.
 */
class SessionRegistryOnClosedTest {

    private lateinit var tempDir: File
    private lateinit var ctx: FakeContext

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("session_registry_closed").toFile()
        ctx = FakeContext(tempDir, "/fake/jni")
        SessionRegistry.clearForTest()
        SessionRegistry.switchSession(-1)
    }

    @After
    fun tearDown() {
        SessionRegistry.clearForTest()
        SessionRegistry.switchSession(-1)
        SafeFiles.deleteTree(tempDir)
    }

    /** Never started (pid 0): isRunning() is true, finishIfRunning() is a no-op. */
    private fun unstartedSession() =
        TerminalSession("/bin/sh", "/", arrayOf("sh"), arrayOf(), 100, SessionRegistry.sessionClient())

    /** Simulates the waiter thread's exit handling (pid -1 + exit status). */
    private fun markExited(session: TerminalSession, status: Int) {
        TerminalSession::class.java.getDeclaredField("mShellPid").apply { isAccessible = true }
            .setInt(session, -1)
        TerminalSession::class.java.getDeclaredField("mShellExitStatus").apply { isAccessible = true }
            .setInt(session, status)
    }

    private fun add(session: TerminalSession, calls: MutableList<Int>, title: String = "Uninstall X") {
        SessionRegistry.addForTest(
            SessionRegistry.ManagedSession(
                session = session,
                type = "install",
                title = title,
                method = "proot",
                onClosed = { calls += it },
            ),
        )
    }

    @Test
    fun closeSession_running_firesOnClosedOnce_evenIfExitArrivesLater() {
        val calls = mutableListOf<Int>()
        val session = unstartedSession()
        add(session, calls)
        SessionRegistry.switchSession(0)

        SessionRegistry.closeSession(ctx, 0)
        assertEquals(listOf(SessionRegistry.CLOSED_EXIT_STATUS), calls)

        // SIGKILL'd process exit reaches the client afterwards.
        markExited(session, SessionRegistry.CLOSED_EXIT_STATUS)
        SessionRegistry.sessionClient().onSessionFinished(session)
        assertEquals(1, calls.size)
    }

    @Test
    fun closeSession_afterProcessExit_doesNotFireAgain() {
        val calls = mutableListOf<Int>()
        val session = unstartedSession()
        add(session, calls)
        SessionRegistry.switchSession(0)

        markExited(session, 0)
        SessionRegistry.sessionClient().onSessionFinished(session)
        assertEquals(listOf(0), calls)

        SessionRegistry.closeSession(ctx, 0)
        assertEquals(listOf(0), calls)
    }

    @Test
    fun closeAll_firesForRunningSessionsOnly_once() {
        val running = mutableListOf<Int>()
        val finished = mutableListOf<Int>()
        val a = unstartedSession()
        val b = unstartedSession()
        add(a, running, "A")
        add(b, finished, "B")
        SessionRegistry.switchSession(0)
        markExited(b, 3)
        SessionRegistry.sessionClient().onSessionFinished(b)

        SessionRegistry.closeAll(ctx)
        assertEquals(listOf(SessionRegistry.CLOSED_EXIT_STATUS), running)
        assertEquals(listOf(3), finished)
        assertEquals(0, SessionRegistry.sessionCount)

        markExited(a, SessionRegistry.CLOSED_EXIT_STATUS)
        SessionRegistry.sessionClient().onSessionFinished(a)
        assertEquals(1, running.size)
    }
}
