package com.ivarna.fluxlinux.ui.onboarding

import com.ivarna.fluxlinux.core.install.InstallController
import com.ivarna.fluxlinux.core.install.InstallKind
import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Onboarding reopened mid-install must keep the run's desktop choice (Retry, Done page). */
class OnboardingDesktopChoiceTest {

    private fun run(
        kind: InstallKind,
        status: InstallController.Status = InstallController.Status.Running,
        distroId: String = UbuntuRuntime.PROOT_ID,
        theme: String = "light",
    ) = InstallController.State(runId = 1, distroId = distroId, theme = theme, kind = kind, status = status)

    @Test
    fun freshOpen_desktopOffAndDarkTheme() {
        val idle = InstallController.State()
        assertFalse(onboardingInstallsDesktop(idle))
        assertEquals("dark", onboardingTheme(idle))
    }

    @Test
    fun reopenedDuringADesktopRun_keepsTheDesktopAndItsTheme() {
        for (status in listOf(InstallController.Status.Running, InstallController.Status.Failed)) {
            val state = run(InstallKind.TERMINAL_AND_DESKTOP, status)
            assertTrue(onboardingInstallsDesktop(state))
            assertEquals("light", onboardingTheme(state))
        }
        assertTrue(onboardingInstallsDesktop(run(InstallKind.TERMINAL_AND_DESKTOP, distroId = UbuntuRuntime.CHROOT_ID)))
    }

    @Test
    fun reopenedDuringATerminalOnlyRun_staysOff() {
        assertFalse(onboardingInstallsDesktop(run(InstallKind.TERMINAL)))
    }

    @Test
    fun anotherDistrosRun_isIgnored() {
        val other = run(InstallKind.TERMINAL_AND_DESKTOP, distroId = "debian")
        assertFalse(onboardingInstallsDesktop(other))
        assertEquals("dark", onboardingTheme(other))
    }
}
