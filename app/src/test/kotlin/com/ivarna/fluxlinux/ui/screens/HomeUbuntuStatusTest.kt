package com.ivarna.fluxlinux.ui.screens

import com.ivarna.fluxlinux.core.desktop.DesktopSession
import com.ivarna.fluxlinux.core.install.InstallController
import com.ivarna.fluxlinux.core.install.InstallKind
import com.ivarna.fluxlinux.core.utils.UbuntuInstallCheck
import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
import com.ivarna.fluxlinux.ui.components.DesktopAddOnUi
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
    fun installedAndIdle_isStopped() {
        assertEquals(UbuntuStatus.STOPPED, ubuntuStatus(UbuntuInstallCheck.INSTALLED, null, proot))
    }

    @Test
    fun chrootDesktopOfTheActiveInstall_showsRunningEvenWhileProbing() {
        assertEquals(
            UbuntuStatus.RUNNING,
            ubuntuStatus(UbuntuInstallCheck.CHECKING, session(chroot), chroot),
        )
        assertEquals(
            UbuntuStatus.STARTING,
            ubuntuStatus(UbuntuInstallCheck.INSTALLED, session(chroot, DesktopSession.Phase.Starting), chroot),
        )
    }

    @Test
    fun desktopOfTheOtherUbuntu_isNotTheActiveOnes() {
        assertEquals(UbuntuStatus.STOPPED, ubuntuStatus(UbuntuInstallCheck.INSTALLED, session(proot), chroot))
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
    fun quickSwitch_onlyWithTheOtherInstallAndNothingRunning() {
        assertTrue(canSwitchRuntime(true, UbuntuStatus.STOPPED, desktopActive = false))
        assertTrue(canSwitchRuntime(true, UbuntuStatus.NEEDS_ROOT, desktopActive = false))
        assertFalse(canSwitchRuntime(false, UbuntuStatus.STOPPED, desktopActive = false))
        assertFalse(canSwitchRuntime(true, UbuntuStatus.RUNNING, desktopActive = false))
        assertFalse(canSwitchRuntime(true, UbuntuStatus.STOPPED, desktopActive = true))
    }

    private fun run(
        kind: InstallKind,
        status: InstallController.Status,
        id: String = proot,
    ) = InstallController.State(
        runId = 1, distroId = id, kind = kind, status = status, percent = 40,
        phaseLabel = "Installing XFCE…", detail = "apt", errorMessage = "exit 100",
    )

    @Test
    fun desktopAddOn_followsTheAddOnRunOfTheActiveInstallOnly() {
        val idle = InstallController.State()
        assertEquals(DesktopAddOnUi.NotInstalled, desktopAddOnUi(idle, proot, desktopInstalled = false))
        assertEquals(DesktopAddOnUi.Installed, desktopAddOnUi(idle, proot, desktopInstalled = true))
        assertEquals(
            DesktopAddOnUi.Installing(40, "Installing XFCE…", "apt"),
            desktopAddOnUi(run(InstallKind.DESKTOP_ADD_ON, InstallController.Status.Running), proot, false),
        )
        assertEquals(
            DesktopAddOnUi.Failed("exit 100"),
            desktopAddOnUi(run(InstallKind.DESKTOP_ADD_ON, InstallController.Status.Failed), proot, false),
        )
        // A finished add-on is reported through the installed flag, not the run.
        assertEquals(
            DesktopAddOnUi.Installed,
            desktopAddOnUi(run(InstallKind.DESKTOP_ADD_ON, InstallController.Status.Succeeded), proot, true),
        )
        // Another install's add-on run, or a terminal-only run, never shows here.
        assertEquals(
            DesktopAddOnUi.NotInstalled,
            desktopAddOnUi(run(InstallKind.DESKTOP_ADD_ON, InstallController.Status.Running, chroot), proot, false),
        )
        assertEquals(
            DesktopAddOnUi.NotInstalled,
            desktopAddOnUi(run(InstallKind.TERMINAL, InstallController.Status.Failed), proot, false),
        )
        // An unfinished desktop install (failed or cancelled, also after a restart) shows as
        // failed; a live run and the failed run's own message still win.
        assertEquals(DesktopAddOnUi.Failed(null), desktopAddOnUi(idle, proot, false, desktopIncomplete = true))
        assertEquals(
            DesktopAddOnUi.Failed("exit 100"),
            desktopAddOnUi(run(InstallKind.DESKTOP_ADD_ON, InstallController.Status.Failed), proot, false, true),
        )
        assertEquals(
            DesktopAddOnUi.Installing(40, "Installing XFCE…", "apt"),
            desktopAddOnUi(run(InstallKind.DESKTOP_ADD_ON, InstallController.Status.Running), proot, false, true),
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
