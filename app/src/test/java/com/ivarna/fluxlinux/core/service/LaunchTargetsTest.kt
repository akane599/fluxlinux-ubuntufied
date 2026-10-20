package com.ivarna.fluxlinux.core.service

import com.ivarna.fluxlinux.core.service.LaunchTargets.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LaunchTargetsTest {

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.isFile } ?: error("missing $rel (cwd=$cwd)")
    }

    @Test
    fun pageFor_mapsKnownPages() {
        assertEquals(Page.TERMINAL, LaunchTargets.pageFor("terminal", null))
        assertEquals(Page.HOME, LaunchTargets.pageFor("home", null))
        assertEquals(Page.HOME, LaunchTargets.pageFor(null, "home"))
        assertEquals(Page.NONE, LaunchTargets.pageFor(LaunchTargets.PAGE_INSTALL, null))
        assertEquals(Page.NONE, LaunchTargets.pageFor(null, null))
        assertEquals(Page.NONE, LaunchTargets.pageFor("settings", "x"))
    }

    /** L15: every keep-alive notification names a target page, so a tap is never a plain launch. */
    @Test
    fun serviceNotifications_carryTargetPage() {
        for (name in listOf("AppTerminalService", "DesktopSessionService", "BaseInstallService")) {
            val src = repoFile("src/main/kotlin/com/ivarna/fluxlinux/core/service/$name.kt").readText()
            assertTrue("$name sets EXTRA_TARGET_PAGE", src.contains("putExtra(LaunchTargets.EXTRA_TARGET_PAGE"))
        }
    }
}
