package com.ivarna.fluxlinux.ui.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tap opens the link under it ([TerminalClipboard.linkAt]); a URL longer than the screen is
 * wide is soft-wrapped across rows and must open whole from any of them, also when part or all
 * of it has scrolled into the scrollback (TerminalView reports those rows as negative). The
 * vendored `TerminalBuffer.getWordAtLocation` stopped its upward walk at screen row 0, so a tap
 * there opened only the tail, which isn't a link, and raised the keyboard instead.
 */
class TerminalLinkAtTest {

    private class Output : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {}
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    private object Client : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    private val emulator = TerminalEmulator(Output(), COLUMNS, ROWS, 100, Client)
    private val screen get() = emulator.screen

    private fun feed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
    }

    private fun linkAt(column: Int, row: Int): String? = TerminalClipboard.linkAt(screen, column, row)

    @Test
    fun wrappedUrlOnScreen_opensWholeFromEitherRow() {
        feed("$URL\r\n")
        assertEquals(URL, linkAt(3, 0))
        assertEquals(URL, linkAt(3, 1))
    }

    @Test
    fun wrappedUrlWhoseStartScrolledIntoTheScrollback_opensWholeFromItsTail() {
        // "$ cat" (row -2), the URL's head (row -1) and tail (row 0), then two short lines.
        feed("$ cat\r\n$URL\r\nx\r\ny\r\n")
        assertEquals(2, screen.activeTranscriptRows)
        assertEquals(URL, linkAt(3, 0))
        assertEquals(URL, linkAt(3, -1))
    }

    @Test
    fun wrappedUrlInTheScrollback_opensWholeFromATapOnAScrolledBackRow() {
        // The URL's head is the oldest row of the scrollback, so the upward walk ends there.
        feed("$URL\r\na\r\nb\r\nc\r\nd\r\n")
        assertEquals(3, screen.activeTranscriptRows)
        assertEquals(URL, linkAt(3, -3))
        assertEquals(URL, linkAt(3, -2))
        assertNull(linkAt(0, -1))
    }

    @Test
    fun wrappedUrlEndingOnTheBottomRow_opensWholeFromEitherRow() {
        // No newline after the URL: its tail is the screen's last row, with the cursor on it.
        feed("a\r\nb\r\n$URL")
        assertEquals(0, screen.activeTranscriptRows)
        assertEquals(URL, linkAt(3, ROWS - 2))
        assertEquals(URL, linkAt(3, ROWS - 1))
    }

    @Test
    fun urlBesideOtherWords_opensOnlyTheUrl() {
        feed("$ open $URL now\r\n")
        // The URL starts at column 7 of row 0 and ends at column 19 of row 1.
        assertEquals(URL, linkAt(10, 0))
        assertEquals(URL, linkAt(3, 1))
        assertNull(linkAt(2, 0))
    }

    /**
     * A tap runs on the main thread. Minified JSON or `cat` of a one-line file wraps across the
     * whole scrollback; rebuilding the span from the walk's row to the tap on every step made one
     * tap quadratic in the rows walked (seconds for a long scrollback, an ANR on a phone).
     */
    @Test
    fun tapOnALineWrappedAcrossTheWholeScrollback_staysFast() {
        val columns = 50
        val rows = 24
        val transcript = 20_000
        val big = TerminalEmulator(Output(), columns, rows, transcript, Client)
        // Exactly fills the buffer (its row count includes the screen): one wrapped line, no spaces.
        val chars = columns * transcript
        val line = "x".repeat(chars).toByteArray(Charsets.UTF_8)
        big.append(line, line.size)
        assertEquals(transcript - rows, big.screen.activeTranscriptRows)

        val started = System.nanoTime()
        val word = big.screen.getWordAtLocation(0, rows / 2)
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(chars, word.length)
        assertTrue("one tap took $tookMs ms", tookMs < 1_000)
    }

    private companion object {
        const val COLUMNS = 20
        const val ROWS = 4

        /** 33 characters: a full 20-column row, then 13 on the next. */
        const val URL = "https://aka.dev/long/path/to/page"
    }
}
