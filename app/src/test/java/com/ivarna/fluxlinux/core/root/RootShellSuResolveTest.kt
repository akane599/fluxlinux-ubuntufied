package com.ivarna.fluxlinux.core.root

import com.ivarna.fluxlinux.core.root.RootShell.SuProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootShellSuResolveTest {

    private val a = listOf("/system/bin/su", "-c")
    private val b = listOf("su", "-c")
    private val c = listOf("su", "0", "-c")

    @Test
    fun walk_skipsDenialsAndReturnsFirstOk() {
        val probed = mutableListOf<List<String>>()
        val found = RootShell.walkSuTrials(listOf(a, b, c)) {
            probed += it
            if (it == b) SuProbe.OK else SuProbe.DENIED
        }
        assertEquals(b, found)
        assertEquals(listOf(a, b), probed)
    }

    @Test
    fun walk_stopsAtFirstTimeout_noFurtherPrompts() {
        val probed = mutableListOf<List<String>>()
        val found = RootShell.walkSuTrials(listOf(a, b, c)) {
            probed += it
            if (it == a) SuProbe.TIMEOUT else SuProbe.OK
        }
        assertNull(found)
        assertEquals(listOf(a), probed)
    }

    @Test
    fun walk_allDenied_returnsNull() {
        assertNull(RootShell.walkSuTrials(listOf(a, b)) { SuProbe.DENIED })
    }
}
