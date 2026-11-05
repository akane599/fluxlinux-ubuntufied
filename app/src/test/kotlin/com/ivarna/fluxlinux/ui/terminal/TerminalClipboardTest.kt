package com.ivarna.fluxlinux.ui.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalClipboardTest {

    @Test
    fun pasteShortcut_ctrlShiftVAndPasteKeyOnly() {
        assertTrue(TerminalClipboard.isPasteShortcut(KeyEvent.KEYCODE_V, ctrl = true, shift = true))
        assertTrue(TerminalClipboard.isPasteShortcut(KeyEvent.KEYCODE_PASTE, ctrl = false, shift = false))
        // Ctrl+V is the terminal's literal-next (^V); plain V is typing.
        assertFalse(TerminalClipboard.isPasteShortcut(KeyEvent.KEYCODE_V, ctrl = true, shift = false))
        assertFalse(TerminalClipboard.isPasteShortcut(KeyEvent.KEYCODE_V, ctrl = false, shift = true))
        assertFalse(TerminalClipboard.isPasteShortcut(KeyEvent.KEYCODE_C, ctrl = true, shift = true))
    }

    @Test
    fun copyToast_onlyBelowAndroid13() {
        assertFalse(TerminalClipboard.systemConfirmsCopy(32))
        assertTrue(TerminalClipboard.systemConfirmsCopy(33))
        assertTrue(TerminalClipboard.systemConfirmsCopy(36))
    }

    @Test
    fun share_keepsNewestOutputWithinLimit() {
        val short = "ls\nREADME.md"
        assertSame(short, TerminalClipboard.tailForShare(short, limit = 100))
        assertEquals("6789", TerminalClipboard.tailForShare("0123456789", limit = 4))
        assertEquals(
            TerminalClipboard.SHARE_LIMIT_CHARS,
            TerminalClipboard.tailForShare("x".repeat(TerminalClipboard.SHARE_LIMIT_CHARS + 10)).length
        )
    }
}
