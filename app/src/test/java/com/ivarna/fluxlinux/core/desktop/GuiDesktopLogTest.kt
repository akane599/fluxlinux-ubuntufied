package com.ivarna.fluxlinux.core.desktop

import com.ivarna.fluxlinux.core.terminal.FakeContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GuiDesktopLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var ctx: FakeContext

    @Before
    fun setUp() {
        val files = tmp.newFolder("files")
        ctx = FakeContext(files, nativeLibDir = File(files, "lib").absolutePath)
    }

    @Test
    fun emptyLog_hasNoContent() {
        GuiDesktopLog.clear(ctx)
        assertFalse(GuiDesktopLog.hasContent(ctx))
        assertTrue(GuiDesktopLog.read(ctx).isEmpty())
    }

    @Test
    fun appendAndHeader_areReadable() {
        GuiDesktopLog.clear(ctx)
        GuiDesktopLog.header(ctx, "START", "start_gui.sh", "proot")
        GuiDesktopLog.append(ctx, "FluxLinux: Starting termux-x11 server...")
        GuiDesktopLog.append(ctx, "FluxLinux: X server PID=123")
        assertTrue(GuiDesktopLog.hasContent(ctx))
        val text = GuiDesktopLog.read(ctx)
        assertTrue(text.contains("=== START method=proot script=start_gui.sh ==="))
        assertTrue(text.contains("X server PID=123"))
    }

    @Test
    fun clear_wipesFile() {
        GuiDesktopLog.append(ctx, "keep-me")
        assertTrue(GuiDesktopLog.hasContent(ctx))
        GuiDesktopLog.clear(ctx)
        assertTrue(GuiDesktopLog.read(ctx).isEmpty() || !GuiDesktopLog.hasContent(ctx))
    }

    @Test
    fun manyAppends_arePersistedInOrder_andRotateAtTheCap() {
        GuiDesktopLog.clear(ctx)
        val line = "x".repeat(200)
        val n = (GuiDesktopLog.MAX_BYTES / 200).toInt() + 500 // crosses the 512 KB cap
        for (i in 0 until n) GuiDesktopLog.append(ctx, "$i $line")
        val text = GuiDesktopLog.read(ctx)
        val size = GuiDesktopLog.logFile(ctx).length()
        assertTrue("size=$size", size in 1..GuiDesktopLog.MAX_BYTES)
        assertTrue(text.endsWith("${n - 1} $line\n"))
        // Newest half kept, in order.
        val nums = text.lineSequence().filter { it.isNotBlank() }.map { it.substringBefore(' ').toIntOrNull() }
            .filterNotNull().toList()
        assertEquals(nums.sorted(), nums)
    }

    @Test
    fun clear_isOrderedAfterQueuedAppends() {
        GuiDesktopLog.append(ctx, "old-1")
        GuiDesktopLog.append(ctx, "old-2")
        GuiDesktopLog.clear(ctx)
        GuiDesktopLog.append(ctx, "new")
        assertEquals("new\n", GuiDesktopLog.read(ctx))
    }

    @Test
    fun append_onlyQueuesToTheWriterThread() {
        // The caller (main thread) only queues; the dedicated writer does the I/O.
        GuiDesktopLog.clear(ctx)
        GuiDesktopLog.append(ctx, "probe")
        val src = File(
            listOf("src/main", "app/src/main").map { File(it) }.first { it.isDirectory },
            "kotlin/com/ivarna/fluxlinux/core/desktop/GuiDesktopLog.kt",
        ).readText()
        val append = src.substringAfter("fun append(").substringBefore("\n    }\n")
        assertTrue(append, append.contains("writer.execute { write(f,"))
        assertFalse(append, append.contains("appendText") || append.contains("writeText"))
        assertEquals("probe\n", GuiDesktopLog.read(ctx))
    }

    @Test
    fun desktopLauncher_coalescesLiveUiUpdates() {
        val src = File(
            listOf("src/main", "app/src/main").map { File(it) }.first { it.isDirectory },
            "kotlin/com/ivarna/fluxlinux/core/desktop/DesktopLauncher.kt",
        ).readText()
        val live = src.substringAfter("private fun appendLive(").substringBefore("\n    }\n")
        assertFalse(live, live.contains("_ui.update"))
        assertTrue(live, live.contains("main.postDelayed(flushLive, LIVE_FLUSH_MS)"))
        assertTrue(src.contains("private const val LIVE_FLUSH_MS = 200L"))
        assertFalse(src.contains("logText = GuiDesktopLog.read(app)"))
    }
}
