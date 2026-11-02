package com.ivarna.fluxlinux.core.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class UbuntuInstallProbeTest {

    private fun check(mode: UbuntuRuntimeMode, proot: Boolean = false, root: Boolean? = null, chroot: Boolean? = null) =
        UbuntuInstallProbe.check(mode, proot, root, chroot)

    @Test
    fun proot_followsTheFileCheckAndIgnoresRoot() {
        assertEquals(UbuntuInstallCheck.INSTALLED, check(UbuntuRuntimeMode.PROOT, proot = true))
        assertEquals(UbuntuInstallCheck.NOT_INSTALLED, check(UbuntuRuntimeMode.PROOT, proot = false, root = true, chroot = true))
    }

    @Test
    fun chroot_unknownRootIsCheckingNotMissing() {
        assertEquals(UbuntuInstallCheck.CHECKING, check(UbuntuRuntimeMode.CHROOT, proot = true))
    }

    @Test
    fun chroot_rootWithoutDetectionYetIsChecking() {
        assertEquals(UbuntuInstallCheck.CHECKING, check(UbuntuRuntimeMode.CHROOT, root = true, chroot = null))
    }

    @Test
    fun chroot_deniedRootIsNoRootEvenIfThePRootInstallExists() {
        assertEquals(UbuntuInstallCheck.NO_ROOT, check(UbuntuRuntimeMode.CHROOT, proot = true, root = false))
    }

    @Test
    fun chroot_rootAndDetectionDecide() {
        assertEquals(UbuntuInstallCheck.INSTALLED, check(UbuntuRuntimeMode.CHROOT, root = true, chroot = true))
        assertEquals(UbuntuInstallCheck.NOT_INSTALLED, check(UbuntuRuntimeMode.CHROOT, proot = true, root = true, chroot = false))
    }
}
