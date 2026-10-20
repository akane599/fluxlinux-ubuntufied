package com.ivarna.fluxlinux.core.root

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [RootShell.executeSync] with the su invocation seeded to the host `/bin/sh -c`
 * (su itself is device-only).
 */
class RootShellExecTimeoutTest {

    @Before
    fun seedHostShell() {
        assumeTrue(File("/bin/sh").canExecute())
        RootShell.seedSuInvocationForTest(listOf("/bin/sh", "-c"))
    }

    @After
    fun tearDown() {
        RootShell.clearSuCache()
    }

    @Test
    fun executeSync_returnsExitCode() {
        assertEquals(0, RootShell.executeSync("true"))
        assertEquals(7, RootShell.executeSync("exit 7"))
    }

    @Test
    fun executeSync_hungCommand_returnsMinus2WithinTimeout() {
        val start = System.nanoTime()
        val code = RootShell.executeSync("exec sleep 30", timeoutMs = 300L)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(-2, code)
        assertTrue("took ${ms}ms", ms < 5_000L)
    }
}
