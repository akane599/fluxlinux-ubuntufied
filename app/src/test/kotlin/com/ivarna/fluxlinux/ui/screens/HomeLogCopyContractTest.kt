package com.ivarna.fluxlinux.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Ubuntu page's log dialogs copy through [com.ivarna.fluxlinux.ui.terminal.TerminalClipboard]:
 * capped below the Binder limit (the desktop log can be 512 KiB) and with a toast when the copy
 * failed, instead of an unconditional "Log copied".
 */
class HomeLogCopyContractTest {

    @Test
    fun copyLog_isCappedAndReportsFailures() {
        val src = homeScreen().readText()
        val fn = src.substringAfter("private fun copyLog(").substringBefore("\n}\n")
        assertTrue(
            fn,
            fn.contains("TerminalClipboard.copy(context, TerminalClipboard.tailForShare(text), R.string.home_log_copied)"),
        )
        assertFalse("no second clipboard path", src.contains("setPrimaryClip("))
    }

    private fun homeScreen(): File {
        val rel = "src/main/kotlin/com/ivarna/fluxlinux/ui/screens/HomeScreen.kt"
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel")).firstOrNull { it.isFile }
            ?: error("missing $rel (cwd=$cwd)")
    }
}
