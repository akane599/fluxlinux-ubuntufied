package com.ivarna.fluxlinux.ui.screens

import com.ivarna.fluxlinux.core.desktop.DesktopDetection
import com.ivarna.fluxlinux.core.desktop.DesktopSession
import com.ivarna.fluxlinux.core.install.InstallController
import com.ivarna.fluxlinux.core.install.InstallKind
import com.ivarna.fluxlinux.core.utils.UbuntuInstallCheck
import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
import com.ivarna.fluxlinux.core.utils.UbuntuRuntimeMode
import com.ivarna.fluxlinux.ui.components.DesktopAddOnUi
import com.ivarna.fluxlinux.ui.components.DesktopRun
import com.ivarna.fluxlinux.ui.components.UbuntuStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeUbuntuStatusTest {

    private val proot = UbuntuRuntime.PROOT_ID
    private val chroot = UbuntuRuntime.CHROOT_ID

    private fun session(id: String, phase: DesktopSession.Phase = DesktopSession.Phase.Running) =
        DesktopSession(distroId = id, distroName = "Ubuntu", type = DesktopSession.Type.XFCE4, phase = phase)

    @Test
    fun installedAndIdle_isReady() {
        assertEquals(UbuntuStatus.READY, ubuntuStatus(UbuntuInstallCheck.INSTALLED, null, proot))
    }

    @Test
    fun desktopOfTheActiveInstall_makesUbuntuReadyEvenWhileProbing_andIsTheDesktopsRunState() {
        // The header says Ubuntu is ready; the desktop's own run state is the Desktop section's.
        assertEquals(UbuntuStatus.READY, ubuntuStatus(UbuntuInstallCheck.CHECKING, session(chroot), chroot))
        assertEquals(
            UbuntuStatus.READY,
            ubuntuStatus(UbuntuInstallCheck.INSTALLED, session(chroot, DesktopSession.Phase.Starting), chroot),
        )
        assertEquals(DesktopRun.RUNNING, desktopRun(session(chroot), chroot))
        assertEquals(DesktopRun.STARTING, desktopRun(session(chroot, DesktopSession.Phase.Starting), chroot))
        assertEquals(DesktopRun.STOPPED, desktopRun(null, chroot))
    }

    @Test
    fun desktopOfTheOtherUbuntu_isNotTheActiveOnes() {
        assertEquals(UbuntuStatus.READY, ubuntuStatus(UbuntuInstallCheck.INSTALLED, session(proot), chroot))
        assertEquals(UbuntuStatus.CHECKING, ubuntuStatus(UbuntuInstallCheck.CHECKING, session(proot), chroot))
        assertEquals(DesktopRun.STOPPED, desktopRun(session(proot), chroot))
        assertEquals(proot, otherDesktopId(session(proot), chroot))
        assertNull(otherDesktopId(session(chroot), chroot))
        assertNull(otherDesktopId(null, chroot))
    }

    @Test
    fun probeStates_mapToCheckingNeedsRootAndNotInstalled() {
        assertEquals(UbuntuStatus.CHECKING, ubuntuStatus(UbuntuInstallCheck.CHECKING, null, chroot))
        assertEquals(UbuntuStatus.NEEDS_ROOT, ubuntuStatus(UbuntuInstallCheck.NO_ROOT, null, chroot))
        assertEquals(UbuntuStatus.NOT_INSTALLED, ubuntuStatus(UbuntuInstallCheck.NOT_INSTALLED, null, chroot))
    }

    @Test
    fun quickSwitch_onlyWithTheOtherInstallAndNoDesktopRunningOrStarting() {
        assertTrue(canSwitchRuntime(otherInstalled = true, desktopActive = false))
        assertFalse(canSwitchRuntime(otherInstalled = false, desktopActive = false))
        assertFalse(canSwitchRuntime(otherInstalled = true, desktopActive = true))
    }

    private fun run(
        kind: InstallKind,
        status: InstallController.Status,
        id: String = proot,
        percent: Int = 40,
    ) = InstallController.State(
        runId = 1, distroId = id, kind = kind, status = status, percent = percent,
        phaseLabel = "Installing XFCE…", detail = "apt", errorMessage = "exit 100",
    )

    private val idle = InstallController.State()
    private val stopped = DesktopAddOnUi.Installed(DesktopRun.STOPPED)

    @Test
    fun desktopAddOn_followsDetectionWhenNoAddOnRunIsShowing() {
        assertEquals(DesktopAddOnUi.NotInstalled, desktopAddOnUi(idle, proot, DesktopDetection.NOT_INSTALLED))
        assertEquals(stopped, desktopAddOnUi(idle, proot, DesktopDetection.INSTALLED))
        // An unfinished desktop install (failed or cancelled, also after a restart) shows as failed.
        assertEquals(DesktopAddOnUi.Failed(null), desktopAddOnUi(idle, proot, DesktopDetection.INCOMPLETE))
    }

    @Test
    fun desktopAddOn_isCheckingUntilDetectionAnswers() {
        assertEquals(DesktopAddOnUi.Checking, desktopAddOnUi(idle, chroot, detection = null))
        // A session of the active install proves the desktop is there, answer or not.
        assertEquals(
            DesktopAddOnUi.Installed(DesktopRun.RUNNING),
            desktopAddOnUi(idle, chroot, detection = null, run = DesktopRun.RUNNING),
        )
    }

    @Test
    fun provisionalDesktop_onlyAChrootsStoredNotInstalledIsUnknown() {
        val chrootMode = UbuntuRuntimeMode.CHROOT
        // Chroot: the flag may not be set yet; only the su probe can tell.
        assertNull(provisionalDesktop(DesktopDetection.NOT_INSTALLED, chrootMode))
        assertEquals(DesktopDetection.INSTALLED, provisionalDesktop(DesktopDetection.INSTALLED, chrootMode))
        assertEquals(DesktopDetection.INCOMPLETE, provisionalDesktop(DesktopDetection.INCOMPLETE, chrootMode))
        // PRoot: a quick file check follows, so the stored state stands.
        assertEquals(
            DesktopDetection.NOT_INSTALLED,
            provisionalDesktop(DesktopDetection.NOT_INSTALLED, UbuntuRuntimeMode.PROOT),
        )
    }

    @Test
    fun desktopAddOn_runningAddOnShowsProgress_clamped() {
        val running = run(InstallKind.DESKTOP_ADD_ON, InstallController.Status.Running)
        assertEquals(DesktopAddOnUi.Installing(40, "Installing XFCE…", "apt"), desktopAddOnUi(running, proot, null))
        // A live run wins over an unfinished earlier one.
        assertEquals(
            DesktopAddOnUi.Installing(40, "Installing XFCE…", "apt"),
            desktopAddOnUi(running, proot, DesktopDetection.INCOMPLETE),
        )
        fun percentOf(p: Int) =
            (desktopAddOnUi(running.copy(percent = p), proot, null) as DesktopAddOnUi.Installing).percent
        assertEquals(100, percentOf(140))
        assertEquals(0, percentOf(-5))
    }

    @Test
    fun desktopAddOn_failedRunShowsFailed_evenWithStartxfce4Present() {
        val failed = run(InstallKind.DESKTOP_ADD_ON, InstallController.Status.Failed)
        assertEquals(DesktopAddOnUi.Failed("exit 100"), desktopAddOnUi(failed, proot, DesktopDetection.NOT_INSTALLED))
        assertEquals(DesktopAddOnUi.Failed("exit 100"), desktopAddOnUi(failed, proot, DesktopDetection.INCOMPLETE))
        // dpkg unpacks startxfce4 early, so a detection that finds it can't overrule the run.
        assertEquals(DesktopAddOnUi.Failed("exit 100"), desktopAddOnUi(failed, proot, DesktopDetection.INSTALLED))
    }

    @Test
    fun desktopAddOn_succeededRunIsInstalledBeforeTheReProbeCatchesUp() {
        val succeeded = run(InstallKind.DESKTOP_ADD_ON, InstallController.Status.Succeeded)
        assertEquals(stopped, desktopAddOnUi(succeeded, proot, DesktopDetection.NOT_INSTALLED))
        assertEquals(stopped, desktopAddOnUi(succeeded, proot, detection = null))
        assertEquals(stopped, desktopAddOnUi(succeeded, proot, DesktopDetection.INSTALLED))
    }

    @Test
    fun desktopAddOn_succeededWithAWarning_isInstalledAndSaysSo() {
        val warned = run(InstallKind.DESKTOP_ADD_ON, InstallController.Status.Succeeded)
            .copy(warning = "Desktop theme didn't finish")
        assertEquals(
            DesktopAddOnUi.Installed(DesktopRun.STOPPED, warning = "Desktop theme didn't finish"),
            desktopAddOnUi(warned, proot, DesktopDetection.INSTALLED),
        )
        // Started right away: still the same run's warning.
        assertEquals(
            DesktopAddOnUi.Installed(DesktopRun.RUNNING, warning = "Desktop theme didn't finish"),
            desktopAddOnUi(warned, proot, DesktopDetection.INSTALLED, run = DesktopRun.RUNNING),
        )
        // Another install's run, or no run (cleared), carries no warning.
        assertEquals(stopped, desktopAddOnUi(warned.copy(distroId = chroot), proot, DesktopDetection.INSTALLED))
        assertEquals(stopped, desktopAddOnUi(idle, proot, DesktopDetection.INSTALLED))
    }

    @Test
    fun desktopAddOn_otherInstallsRunsAndTerminalOnlyRunsNeverShow() {
        val otherRun = run(InstallKind.DESKTOP_ADD_ON, InstallController.Status.Running, chroot)
        assertEquals(DesktopAddOnUi.NotInstalled, desktopAddOnUi(otherRun, proot, DesktopDetection.NOT_INSTALLED))
        val terminalRun = run(InstallKind.TERMINAL, InstallController.Status.Failed)
        assertEquals(DesktopAddOnUi.NotInstalled, desktopAddOnUi(terminalRun, proot, DesktopDetection.NOT_INSTALLED))
    }

    @Test
    fun desktopAddOn_installedCarriesItsRunAndTheOtherDesktopOnlyWhileStopped() {
        assertEquals(
            DesktopAddOnUi.Installed(DesktopRun.STOPPED, otherRunning = "Ubuntu (PRoot)"),
            desktopAddOnUi(idle, chroot, DesktopDetection.INSTALLED, otherRunning = "Ubuntu (PRoot)"),
        )
        assertEquals(
            DesktopAddOnUi.Installed(DesktopRun.STARTING),
            desktopAddOnUi(
                idle, chroot, DesktopDetection.INSTALLED, run = DesktopRun.STARTING, otherRunning = "Ubuntu (PRoot)",
            ),
        )
    }

    @Test
    fun rootProbe_afterDenial_onlyOnRetry() {
        // Root managers prompt in their own activity: resume after a deny must not re-prompt.
        assertFalse(homeRootProbeAllowed(RootProbeReason.RESUME, lastRoot = false))
        assertFalse(homeRootProbeAllowed(RootProbeReason.REFRESH, lastRoot = false))
        assertFalse(homeRootProbeAllowed(RootProbeReason.INITIAL, lastRoot = false))
        assertTrue(homeRootProbeAllowed(RootProbeReason.RETRY, lastRoot = false))
    }

    @Test
    fun rootProbe_whileProbing_doesNotStackOnResume() {
        // null = in flight (or never asked): the prompt's own resume must not start another.
        assertFalse(homeRootProbeAllowed(RootProbeReason.RESUME, lastRoot = null))
        assertFalse(homeRootProbeAllowed(RootProbeReason.REFRESH, lastRoot = null))
        assertTrue(homeRootProbeAllowed(RootProbeReason.INITIAL, lastRoot = null))
    }

    @Test
    fun rootProbe_afterGrant_reReadsOnResumeAndRefresh() {
        assertTrue(homeRootProbeAllowed(RootProbeReason.RESUME, lastRoot = true))
        assertTrue(homeRootProbeAllowed(RootProbeReason.REFRESH, lastRoot = true))
        assertFalse(homeRootProbeAllowed(RootProbeReason.INITIAL, lastRoot = true))
    }
}
