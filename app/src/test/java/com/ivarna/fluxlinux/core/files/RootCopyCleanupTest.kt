package com.ivarna.fluxlinux.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A chroot copy that started `cp` but did not finish (timeout tree-kill, failure) must not leave
 * a partial destination that looks like a real copy, and data-sized ops get the long budget.
 */
class RootCopyCleanupTest {
    private val rootfs = "/data/local/tmp/chrootUbuntu"
    private val src = "$rootfs/home/akane/big dir"
    private val dest = "$rootfs/home/akane/big dir (1)"

    /** Answers by script kind; records (script, timeout). */
    private class FakeRunner(private val copyResult: RootResult) : RootRunner {
        val calls = mutableListOf<Pair<String, Long>>()
        override fun start(script: String): RootJob = throw UnsupportedOperationException()
        override fun run(script: String, timeoutMs: Long): RootResult {
            calls += script to timeoutMs
            return when {
                "cp -a" in script -> copyResult
                else -> RootResult(0, ByteArray(0), "")
            }
        }
        val deletes get() = calls.filter { "rm -rf" in it.first }
    }

    private fun started() = "${RootScripts.COPY_STARTED}\n".toByteArray()

    @Test
    fun timedOutCopy_removesExactlyTheNewDestination_andFails() {
        val runner = FakeRunner(RootResult(-2, started(), ""))
        val fs = RootGuestFs(ShellRootOps(runner, rootfs), rootfs)

        val e = runCatching { fs.copyTree(src, dest) }.exceptionOrNull()

        assertTrue("copy must fail: $e", e is RootFsException)
        assertEquals(1, runner.deletes.size)
        val (script, timeout) = runner.deletes.single()
        assertTrue(script, script.contains("P=${RootScripts.q(dest)};"))
        assertFalse(script, script.contains("P=${RootScripts.q(src)};"))
        // The cleanup carries the same guards as any delete.
        for (guard in listOf("_inside \"\$P\" || exit 34", "|| exit 31", "|| exit 30")) assertTrue(guard, script.contains(guard))
        assertEquals(RootRunner.LONG_TIMEOUT_MS, timeout)
        assertEquals(RootRunner.LONG_TIMEOUT_MS, runner.calls.single { "cp -a" in it.first }.second)
    }

    @Test
    fun failedCopy_afterStart_isCleanedUpAndReportsFalse() {
        val runner = FakeRunner(RootResult(33, started(), "cp: short read"))
        val fs = RootGuestFs(ShellRootOps(runner, rootfs), rootfs)
        assertFalse(fs.copyTree(src, dest))
        assertEquals(1, runner.deletes.size)
    }

    @Test
    fun refusedCopy_beforeStart_neverDeletesAnything() {
        // exit 17: destination already existed (someone else's) — never remove it.
        for (code in listOf(17, 30, 31, 34, 2, -1)) {
            val runner = FakeRunner(RootResult(code, ByteArray(0), ""))
            val fs = RootGuestFs(ShellRootOps(runner, rootfs), rootfs)
            runCatching { fs.copyTree(src, dest) }
            assertTrue("exit $code", runner.deletes.isEmpty())
        }
    }

    @Test
    fun shortOps_keepTheTenSecondBudget_dataSizedOpsGetTheLongOne() {
        val runner = FakeRunner(RootResult(0, started(), ""))
        val ops = ShellRootOps(runner, rootfs)
        ops.mutate(RootMutation.CreateFile("$rootfs/home/akane/x"))
        ops.mutate(RootMutation.Delete("$rootfs/home/akane/x"))
        ops.mutate(RootMutation.Move("$rootfs/home/akane/a", "$rootfs/home/akane/b"))
        assertEquals(
            listOf(RootRunner.TIMEOUT_MS, RootRunner.LONG_TIMEOUT_MS, RootRunner.LONG_TIMEOUT_MS),
            runner.calls.map { it.second },
        )
    }
}
