package com.ivarna.fluxlinux.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** [RootUtils.runWithStdin] against the host `sh` (stands in for `su`, which is device-only). */
class RootUtilsTest {

    @Before
    fun needsSh() {
        assumeTrue(File("/bin/sh").canExecute())
    }

    @Test
    fun runWithStdin_success_mergesStderrIntoOutput() {
        val r = RootUtils.runWithStdin(listOf("/bin/sh"), "echo out; echo err >&2", 5_000L)
        assertTrue(r.isSuccess)
        assertTrue(r.output.contains("out"))
        assertTrue(r.output.contains("err"))
        assertEquals("", r.error)
    }

    @Test
    fun runWithStdin_failure_reportsOutputAsError() {
        val r = RootUtils.runWithStdin(listOf("/bin/sh"), "echo denied >&2; exit 3", 5_000L)
        assertFalse(r.isSuccess)
        assertEquals("denied", r.error)
    }

    @Test
    fun runWithStdin_hungCommand_timesOut() {
        val start = System.nanoTime()
        val r = RootUtils.runWithStdin(listOf("/bin/sh"), "exec sleep 30", 300L)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertFalse(r.isSuccess)
        assertTrue(r.error.contains("timed out"))
        assertTrue("took ${ms}ms", ms < 5_000L)
    }

    @Test
    fun runWithStdin_largeOutput_doesNotDeadlock() {
        // > 64 KiB pipe buffer: the old waitFor-before-read order deadlocked here.
        val r = RootUtils.runWithStdin(
            listOf("/bin/sh"),
            "i=0; while [ \$i -lt 4000 ]; do echo 0123456789012345678901234567890123456789; i=\$((i+1)); done",
            10_000L,
        )
        assertTrue(r.isSuccess)
        assertTrue(r.output.length > 64 * 1024)
    }
}
