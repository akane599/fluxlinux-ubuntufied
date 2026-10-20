package com.ivarna.fluxlinux.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * L17: X11SettingsScreen must re-read every pref it shows on ON_RESUME, because
 * termux-x11's LoriePreferences edits the same keys while the screen is backgrounded.
 */
class X11SettingsResumeContractTest {

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.isFile } ?: error("missing $rel (cwd=$cwd)")
    }

    @Test
    fun everyDisplayedPref_isReReadOnResume() {
        val src = repoFile("src/main/kotlin/com/ivarna/fluxlinux/ui/screens/X11SettingsScreen.kt").readText()
        val resumeAt = src.indexOf("Lifecycle.Event.ON_RESUME")
        assertTrue("X11SettingsScreen observes ON_RESUME", resumeAt > 0)
        val initial = src.substring(0, resumeAt)
        val resumeBlock = src.substring(resumeAt, src.indexOf("onDispose", resumeAt))
        val getters = Regex("""TermuxX11Preferences\.(get\w+)\(context\)""").findAll(initial)
            .map { it.groupValues[1] }.toSet()
        assertTrue("found the initial pref reads", getters.size >= 9)
        for (g in getters) {
            assertTrue("$g is re-read on resume", resumeBlock.contains("TermuxX11Preferences.$g(context)"))
        }
    }
}
