package com.ivarna.fluxlinux.ui.onboarding

import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
import com.ivarna.fluxlinux.core.utils.UbuntuRuntimeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class OnboardingInstallRuntimeTest {

    @Test
    fun chroot_withUnknownRoot_staysChroot() {
        // Recreation (rotation / process death) forgets nothing: unknown is not "no root".
        val mode = onboardingInstallRuntime(UbuntuRuntimeMode.CHROOT, rootAvailable = null)
        assertEquals(UbuntuRuntime.CHROOT_ID, mode.distroId)
    }

    @Test
    fun chroot_withRootDenied_fallsBackToProot() {
        assertEquals(UbuntuRuntimeMode.PROOT, onboardingInstallRuntime(UbuntuRuntimeMode.CHROOT, rootAvailable = false))
    }

    @Test
    fun chroot_withRoot_staysChroot() {
        assertEquals(UbuntuRuntimeMode.CHROOT, onboardingInstallRuntime(UbuntuRuntimeMode.CHROOT, rootAvailable = true))
    }

    @Test
    fun proot_isNeverChanged() {
        for (root in listOf(null, false, true)) {
            assertEquals(UbuntuRuntimeMode.PROOT, onboardingInstallRuntime(UbuntuRuntimeMode.PROOT, root))
        }
    }

    @Test
    fun retry_reusesTheFailedInstallsId() {
        // A failed chroot install retries chroot even if the selection/root state was lost.
        assertEquals(
            UbuntuRuntimeMode.CHROOT,
            onboardingInstallRuntime(UbuntuRuntimeMode.PROOT, rootAvailable = false, retryDistroId = UbuntuRuntime.CHROOT_ID),
        )
        assertEquals(
            UbuntuRuntimeMode.PROOT,
            onboardingInstallRuntime(UbuntuRuntimeMode.CHROOT, rootAvailable = true, retryDistroId = UbuntuRuntime.PROOT_ID),
        )
    }

    @Test
    fun retry_withNonUbuntuId_usesTheSelection() {
        assertEquals(
            UbuntuRuntimeMode.CHROOT,
            onboardingInstallRuntime(UbuntuRuntimeMode.CHROOT, rootAvailable = null, retryDistroId = "debian"),
        )
    }
}
