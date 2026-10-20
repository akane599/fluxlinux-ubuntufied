package com.ivarna.fluxlinux.core.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [H3] Cancel must kill the whole job (grandchildren included) and unblock the
 * reader. Runs the real wrapper under the host /bin/bash (Linux JVM).
 */
class ProcessGroupKillTest {

    private val bash = "/bin/bash"

    @Test
    fun cancel_killsGrandchild_andUnblocksReader() {
        assumeTrue(File(bash).canExecute() && File("/proc/self").isDirectory)
        val holder = AtomicReference<Process?>(null)
        val grandchild = AtomicReference<Int?>(null)
        val started = CountDownLatch(1)
        val result = AtomicReference<Pair<Int, String>?>(null)
        val reader = Thread {
            result.set(
                ShellCommandRunner.captureExit(
                    ProcessBuilder(
                        *ProcessGroupLauncher.wrap(bash, arrayOf("sh", "-c", "sleep 30 & echo \$!; wait")),
                    ),
                    grouped = true,
                    processHolder = holder,
                    onLine = { line ->
                        line.trim().toIntOrNull()?.let { grandchild.set(it); started.countDown() }
                    },
                ),
            )
        }
        reader.start()
        assertTrue("job did not start", started.await(10, TimeUnit.SECONDS))
        val pid = grandchild.get()
        assertNotNull(pid)
        assertTrue(isAlive(pid!!))

        val proc = holder.get()
        assertTrue(proc is GroupKillingProcess)
        val t0 = System.nanoTime()
        proc!!.destroyForcibly()

        reader.join(5_000)
        assertFalse("reader still blocked after cancel", reader.isAlive)
        assertTrue("cancel took too long", System.nanoTime() - t0 < TimeUnit.SECONDS.toNanos(5))
        assertTrue("grandchild survived cancel", waitDead(pid, 5_000))
        assertEquals("wrapper exits 143 after killing the group", 143, result.get()?.first)
    }

    @Test
    fun wrapper_preservesJobExitCode_andOutput() {
        assumeTrue(File(bash).canExecute())
        val (exit, out) = ShellCommandRunner.captureExit(
            ProcessBuilder(*ProcessGroupLauncher.wrap(bash, arrayOf("sh", "-c", "echo hi; echo err >&2; exit 7"))),
            grouped = true,
            processHolder = AtomicReference(null),
            onLine = null,
        )
        assertEquals(7, exit)
        assertEquals("hi\nerr\n", out)
    }

    @Test
    fun wrapper_runsJobInItsOwnProcessGroup() {
        assumeTrue(File(bash).canExecute() && File("/proc/self").isDirectory)
        // The job's pgid equals its own pid (group leader), not the wrapper's group.
        val (_, out) = ShellCommandRunner.captureExit(
            ProcessBuilder(
                *ProcessGroupLauncher.wrap(
                    bash,
                    arrayOf("sh", "-c", "echo \$\$ \$(cut -d' ' -f5 /proc/\$\$/stat)"),
                ),
            ),
            grouped = true,
            processHolder = AtomicReference(null),
            onLine = null,
        )
        val (pid, pgid) = out.trim().split(" ")
        assertEquals(pid, pgid)
    }

    private fun isAlive(pid: Int): Boolean {
        val stat = File("/proc/$pid/stat")
        if (!stat.exists()) return false
        // Field 3 is the state; a zombie is already dead.
        val state = runCatching { stat.readText().substringAfterLast(") ").split(" ")[0] }.getOrNull()
        return state != null && state != "Z" && state != "X"
    }

    private fun waitDead(pid: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive(pid)) return true
            Thread.sleep(50)
        }
        return !isAlive(pid)
    }
}
