package com.ivarna.fluxlinux.ui.screens

import com.ivarna.fluxlinux.core.desktop.DesktopSession
import com.ivarna.fluxlinux.core.utils.UbuntuInstallCheck
import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
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
    fun installedAndIdle_isStopped_pendingAutoStartIsStarting() {
        assertEquals(UbuntuStatus.STOPPED, ubuntuStatus(UbuntuInstallCheck.INSTALLED, false, null, proot))
        assertEquals(UbuntuStatus.STARTING, ubuntuStatus(UbuntuInstallCheck.INSTALLED, true, null, proot))
    }

    @Test
    fun chrootDesktopOfTheActiveInstall_showsRunningEvenWhileProbing() {
        assertEquals(
            UbuntuStatus.RUNNING,
            ubuntuStatus(UbuntuInstallCheck.CHECKING, false, session(chroot), chroot),
        )
        assertEquals(
            UbuntuStatus.STARTING,
            ubuntuStatus(UbuntuInstallCheck.INSTALLED, false, session(chroot, DesktopSession.Phase.Starting), chroot),
        )
    }

    @Test
    fun desktopOfTheOtherUbuntu_isNotTheActiveOnes() {
        assertEquals(UbuntuStatus.STOPPED, ubuntuStatus(UbuntuInstallCheck.INSTALLED, false, session(proot), chroot))
        assertEquals(proot, otherDesktopId(session(proot), chroot))
        assertNull(otherDesktopId(session(chroot), chroot))
        assertNull(otherDesktopId(null, chroot))
    }

    @Test
    fun probeStates_mapToCheckingNeedsRootAndNotInstalled() {
        assertEquals(UbuntuStatus.CHECKING, ubuntuStatus(UbuntuInstallCheck.CHECKING, true, null, chroot))
        assertEquals(UbuntuStatus.NEEDS_ROOT, ubuntuStatus(UbuntuInstallCheck.NO_ROOT, true, null, chroot))
        assertEquals(UbuntuStatus.NOT_INSTALLED, ubuntuStatus(UbuntuInstallCheck.NOT_INSTALLED, true, null, chroot))
    }

    @Test
    fun quickSwitch_onlyWithTheOtherInstallAndNothingRunning() {
        assertTrue(canSwitchRuntime(true, UbuntuStatus.STOPPED, desktopActive = false, autoStartPending = false))
        assertTrue(canSwitchRuntime(true, UbuntuStatus.NEEDS_ROOT, desktopActive = false, autoStartPending = false))
        assertFalse(canSwitchRuntime(false, UbuntuStatus.STOPPED, desktopActive = false, autoStartPending = false))
        assertFalse(canSwitchRuntime(true, UbuntuStatus.RUNNING, desktopActive = false, autoStartPending = false))
        assertFalse(canSwitchRuntime(true, UbuntuStatus.STOPPED, desktopActive = true, autoStartPending = false))
        assertFalse(canSwitchRuntime(true, UbuntuStatus.STOPPED, desktopActive = false, autoStartPending = true))
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
