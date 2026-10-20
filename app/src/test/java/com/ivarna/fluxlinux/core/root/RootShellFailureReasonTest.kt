package com.ivarna.fluxlinux.core.root

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RootShellFailureReasonTest {

    @After
    fun tearDown() {
        RootShell.clearSuCache()
    }

    @Test
    fun failureReason_mapsSentinelExitCodes() {
        assertEquals("timeout", RootShell.failureReason(-2))
        assertEquals("su_failed", RootShell.failureReason(-1))
        assertNull(RootShell.failureReason(0))
        assertNull(RootShell.failureReason(1))
        assertNull(RootShell.failureReason(127))
    }

    @Test
    fun captureResult_hungRoot_isTimeoutNotEmptySuccess() {
        assumeTrue(File("/bin/sh").canExecute())
        RootShell.seedSuInvocationForTest(listOf("/bin/sh", "-c"))
        val r = RootShell.captureResult("exec sleep 30", timeoutMs = 300L)
        assertEquals(-2, r.exitCode)
        assertEquals("timeout", RootShell.failureReason(r.exitCode))
    }
}
