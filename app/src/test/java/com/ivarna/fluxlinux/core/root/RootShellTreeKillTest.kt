package com.ivarna.fluxlinux.core.root

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

/**
 * H3 (root side): timeout / cancel must kill the whole root job tree, not just the su client.
 * su is device-only, so the invocation is seeded to the host `/bin/sh -c`; the marker and the
 * kill script are plain POSIX sh over /proc and run unchanged here.
 */
class RootShellTreeKillTest {

    private val tmp: File = Files.createTempDirectory("rootshell-tree").toFile()

    @After
    fun tearDown() {
        RootShell.clearSuCache()
        tmp.listFiles()?.forEach { f ->
            f.readText().trim().toIntOrNull()?.let { pid ->
                if (alive(pid)) ProcessBuilder("kill", "-9", pid.toString()).start().waitFor()
            }
            f.delete()
        }
        tmp.delete()
    }

    private fun seedHostShell() {
        assumeTrue(File("/bin/sh").canExecute() && File("/proc/self/stat").exists())
        RootShell.seedSuInvocationForTest(listOf("/bin/sh", "-c"))
    }

    /** Two background children plus a grandchild; pids written to [tmp]. */
    private fun treeCmd(): String {
        val d = tmp.absolutePath
        return "sleep 120 & echo \$! > '$d/a'; " +
            "( sleep 120 & echo \$! > '$d/c'; wait ) & echo \$! > '$d/b'; " +
            "echo started; wait"
    }

    private fun pids(): List<Int> =
        listOf("a", "b", "c").map { File(tmp, it) }.filter { it.isFile }
            .mapNotNull { it.readText().trim().toIntOrNull() }

    private fun alive(pid: Int): Boolean {
        val stat = File("/proc/$pid/stat")
        val text = try {
            stat.readText()
        } catch (_: Exception) {
            return false
        }
        return text.substringAfterLast(") ").firstOrNull() != 'Z'
    }

    private fun awaitPids(n: Int) {
        val end = System.currentTimeMillis() + 5_000
        while (pids().size < n && System.currentTimeMillis() < end) Thread.sleep(50)
        assertEquals(n, pids().size)
    }

    private fun assertAllDead(ps: List<Int>) {
        val end = System.currentTimeMillis() + 5_000
        while (ps.any { alive(it) } && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue("still alive: ${ps.filter { alive(it) }}", ps.none { alive(it) })
    }

    @Test
    fun parsePidMarker_parsesAndRejects() {
        assertEquals(
            RootShell.RootPid(1234, "98765"),
            RootShell.parsePidMarker("${RootShell.ROOT_PID_MARKER} 1234 98765"),
        )
        assertEquals(
            RootShell.RootPid(1234, ""),
            RootShell.parsePidMarker("${RootShell.ROOT_PID_MARKER} 1234 "),
        )
        assertNull(RootShell.parsePidMarker("hello 1234 5"))
        assertNull(RootShell.parsePidMarker("${RootShell.ROOT_PID_MARKER} 1 5"))
        assertNull(RootShell.parsePidMarker("${RootShell.ROOT_PID_MARKER} x 5"))
    }

    @Test
    fun captureResult_stripsMarker_andKeepsExitCode() {
        seedHostShell()
        val r = RootShell.captureResult("echo hi; exit 3", timeoutMs = 5_000L)
        assertEquals(3, r.exitCode)
        assertEquals("hi\n", r.stdout)
        assertFalse(r.stdout.contains(RootShell.ROOT_PID_MARKER))
    }

    @Test
    fun captureResult_timeout_killsWholeTree() {
        seedHostShell()
        val r = RootShell.captureResult(treeCmd(), timeoutMs = 1_000L)
        assertEquals(-2, r.exitCode)
        awaitPids(3)
        assertAllDead(pids())
    }

    @Test
    fun captureResult_cancelWithoutKillingClient_killsWholeTree() {
        // KernelSU-like: the owner clears the holder but cannot signal the root process.
        seedHostShell()
        val holder = AtomicReference<Process?>(null)
        val result = AtomicReference<RootShell.CaptureResult?>(null)
        val t = Thread { result.set(RootShell.captureResult(treeCmd(), processHolder = holder)) }
        t.start()
        awaitPids(3)
        assertNotNull(holder.get())
        holder.set(null)
        t.join(10_000)
        assertFalse(t.isAlive)
        assertTrue(result.get()?.exitCode != 0)
        assertAllDead(pids())
    }

    @Test
    fun cancelRootJob_killsWholeTree() {
        seedHostShell()
        val holder = AtomicReference<Process?>(null)
        val t = Thread { RootShell.captureResult(treeCmd(), processHolder = holder) }
        t.start()
        awaitPids(3)
        RootShell.cancelRootJob(holder.getAndSet(null))
        t.join(10_000)
        assertFalse(t.isAlive)
        assertAllDead(pids())
    }

    @Test
    fun executeSync_timeout_killsWholeTree() {
        seedHostShell()
        assertEquals(-2, RootShell.executeSync(treeCmd(), timeoutMs = 1_000L))
        awaitPids(3)
        assertAllDead(pids())
    }

    @Test
    fun treeKill_staleStartTime_isNoOp() {
        seedHostShell()
        val p = ProcessBuilder("/bin/sh", "-c", "echo \$\$; exec sleep 120").start()
        try {
            val pid = p.inputStream.bufferedReader().readLine().trim().toInt()
            val script = RootShell.buildTreeKillScript(RootShell.RootPid(pid, "1"))
            val k = ProcessBuilder("/bin/sh", "-c", script).start()
            k.waitFor()
            Thread.sleep(200)
            assertTrue(p.isAlive)
        } finally {
            p.destroyForcibly()
        }
    }
}
