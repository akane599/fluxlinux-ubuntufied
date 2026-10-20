package com.ivarna.fluxlinux.core.terminal

import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * M7: closing a tab below the active one must shift [SessionRegistry.activeIndex]
 * so the active tab (and its redraws) stay on the same shell.
 */
class SessionRegistryTest {

    private lateinit var tempDir: File
    private lateinit var ctx: FakeContext

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("session_registry_test").toFile()
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

    private fun managed(title: String) = SessionRegistry.ManagedSession(
        session = null,
        type = "shell",
        title = title,
        method = "proot",
    )

    private fun open(vararg titles: String, active: Int) {
        titles.forEach { SessionRegistry.addForTest(managed(it)) }
        SessionRegistry.switchSession(active)
    }

    private fun activeTitle(): String? = SessionRegistry.activeSession?.title

    @Test
    fun closeFirst_whileLastActive_keepsSameShellActive() {
        open("A", "B", "C", active = 2)
        SessionRegistry.closeSession(ctx, 0)
        assertEquals(1, SessionRegistry.activeIndex.value)
        assertEquals("C", activeTitle())
    }

    @Test
    fun closeFirst_ofTwo_whileSecondActive_auditScenario() {
        open("A", "B", active = 1)
        SessionRegistry.closeSession(ctx, 0)
        assertEquals(0, SessionRegistry.activeIndex.value)
        assertEquals("B", activeTitle())
    }

    @Test
    fun closeMiddle_whileLastActive_shiftsDown() {
        open("A", "B", "C", active = 2)
        SessionRegistry.closeSession(ctx, 1)
        assertEquals(1, SessionRegistry.activeIndex.value)
        assertEquals("C", activeTitle())
    }

    @Test
    fun closeMiddle_whileFirstActive_leavesIndex() {
        open("A", "B", "C", active = 0)
        SessionRegistry.closeSession(ctx, 1)
        assertEquals(0, SessionRegistry.activeIndex.value)
        assertEquals("A", activeTitle())
    }

    @Test
    fun closeActiveMiddle_focusesNextTab() {
        open("A", "B", "C", active = 1)
        SessionRegistry.closeSession(ctx, 1)
        assertEquals(1, SessionRegistry.activeIndex.value)
        assertEquals("C", activeTitle())
    }

    @Test
    fun closeActiveLast_focusesNewLast() {
        open("A", "B", "C", active = 2)
        SessionRegistry.closeSession(ctx, 2)
        assertEquals(1, SessionRegistry.activeIndex.value)
        assertEquals("B", activeTitle())
    }

    @Test
    fun closeOnlyTab_clearsActive() {
        open("A", active = 0)
        SessionRegistry.closeSession(ctx, 0)
        assertEquals(-1, SessionRegistry.activeIndex.value)
        assertNull(SessionRegistry.activeSession)
    }

    @Test
    fun closeOthers_onLastTab_keepsItActive() {
        // TerminalScreen "Close others" closes descending, skipping the kept tab.
        open("A", "B", "C", "D", active = 3)
        val keep = 3
        for (j in 3 downTo 0) if (j != keep) SessionRegistry.closeSession(ctx, j)
        assertEquals(1, SessionRegistry.sessionCount)
        assertEquals(0, SessionRegistry.activeIndex.value)
        assertEquals("D", activeTitle())
    }

    @Test
    fun closeOthers_keepMiddle_whileAnotherActive_endsOnKept() {
        open("A", "B", "C", "D", active = 3)
        val keep = 1
        for (j in 3 downTo 0) if (j != keep) SessionRegistry.closeSession(ctx, j)
        assertEquals(0, SessionRegistry.activeIndex.value)
        assertEquals("B", activeTitle())
    }

    @Test
    fun activeIndexAfterClose_table() {
        val f = SessionRegistry::activeIndexAfterClose
        assertEquals(-1, f(0, 0, 0))
        assertEquals(1, f(2, 0, 2)) // lower closed → shift
        assertEquals(0, f(0, 1, 2)) // higher closed → unchanged
        assertEquals(1, f(1, 1, 2)) // active closed → next takes its slot
        assertEquals(1, f(2, 2, 2)) // active last closed → new last
        assertEquals(-1, f(-1, 0, 2)) // nothing active stays nothing active
    }

    @Test
    fun activeSessionResolvesToSameObject() {
        open("A", "B", active = 1)
        val b = SessionRegistry.sessions()[1]
        SessionRegistry.closeSession(ctx, 0)
        assertSame(b, SessionRegistry.activeSession)
    }
}
