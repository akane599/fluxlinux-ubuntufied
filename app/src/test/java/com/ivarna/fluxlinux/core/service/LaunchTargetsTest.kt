package com.ivarna.fluxlinux.core.service

import com.ivarna.fluxlinux.core.service.LaunchTargets.Page
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun callbackDistroId_acceptsOnlyKnownIds() {
        val known = listOf("ubuntu", "debian13_chroot")
        assertEquals("ubuntu", LaunchTargets.callbackDistroId("distro_install_ubuntu", "distro_install_", known))
        assertEquals(
            "debian13_chroot",
            LaunchTargets.callbackDistroId("distro_uninstall_debian13_chroot", "distro_uninstall_", known),
        )
        assertNull(LaunchTargets.callbackDistroId("distro_install_evil", "distro_install_", known))
        assertNull(LaunchTargets.callbackDistroId("distro_install_", "distro_install_", known))
        assertNull(LaunchTargets.callbackDistroId("distro_install_../x", "distro_install_", known))
        assertNull(LaunchTargets.callbackDistroId("base_install", "distro_install_", known))
    }

    /** L15: every keep-alive notification names a target page, so a tap is never a plain launch. */
    @Test
    fun serviceNotifications_carryTargetPage() {
        for (name in listOf("AppTerminalService", "DesktopSessionService", "BaseInstallService")) {
            val src = repoFile("src/main/kotlin/com/ivarna/fluxlinux/core/service/$name.kt").readText()
            assertTrue("$name sets EXTRA_TARGET_PAGE", src.contains("putExtra(LaunchTargets.EXTRA_TARGET_PAGE"))
        }
    }

    /** L8: the script callback deep link must not be reachable from a browser. */
    @Test
    fun callbackFilter_isNotBrowsable() {
        val manifest = repoFile("src/main/AndroidManifest.xml").readText()
        val filter = Regex("""<intent-filter>((?:(?!</intent-filter>).)*android:host="callback"(?:(?!</intent-filter>).)*)</intent-filter>""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)?.groupValues?.get(1) ?: error("callback filter missing")
        assertFalse(filter.contains("android.intent.category.BROWSABLE"))
        assertTrue(filter.contains("android.intent.category.DEFAULT"))
    }
}
