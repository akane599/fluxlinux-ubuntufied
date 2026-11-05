package com.ivarna.fluxlinux.core.terminal

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The vendored Termux emulator (v0.118.0) has no kitty keyboard protocol. Programs that use it
 * (codex, via crossterm) pop and reset its flags on exit with `CSI < Ps u` / `CSI < u`, which the
 * emulator didn't parse: it dropped `CSI <` and printed the rest, so "1uu" was left on the screen
 * after codex quit. Those sequences are now consumed silently. The `CSI ? u` query still gets no
 * reply, so programs keep the legacy key encoding this emulator actually sends.
 */
class EmulatorKeyboardProtocolTest {

    private class Output : TerminalOutput() {
        val written = StringBuilder()
        override fun write(data: ByteArray, offset: Int, count: Int) {
            written.append(String(data, offset, count, Charsets.UTF_8))
        }
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

    private val output = Output()
    private val emulator = TerminalEmulator(output, COLUMNS, 4, 100, Client)

    private fun feed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        emulator.append(bytes, bytes.size)
    }

    private fun firstLine(): String = emulator.screen.getSelectedText(0, 0, COLUMNS - 1, 0).trim()

    @Test
    fun codexExitSequences_leaveNoText() {
        // crossterm PopKeyboardEnhancementFlags, codex's reset, DisableModifyOtherKeys, then the
        // DA1 query codex waits on before it returns to the shell.
        feed("\u001b[<1u\u001b[<u\u001b[>4;0m\u001b[c")
        assertEquals("", firstLine())
        assertEquals(DA1_REPLY, output.written.toString())
    }

    @Test
    fun kittyPushSetAndQuery_consumedWithoutReply() {
        feed("\u001b[>11u\u001b[=5;1u\u001b[?u")
        assertEquals("", firstLine())
        assertEquals("", output.written.toString())
    }

    @Test
    fun textAroundTheSequencesStillPrints() {
        feed("a\u001b[<1ub\u001b[=1;2uc\u001b[<ud")
        assertEquals("abcd", firstLine())
    }

    @Test
    fun deviceAttributesAfterUnsupportedSequencesStillAnswered() {
        // DA3 (CSI = c) is unsupported too; the DA1 right after it must still be answered.
        feed("\u001b[=c\u001b[c")
        assertEquals("", firstLine())
        assertEquals(DA1_REPLY, output.written.toString())
    }

    private companion object {
        const val COLUMNS = 40
        const val DA1_REPLY = "\u001b[?64;1;2;6;9;15;18;21;22c"
    }
}
