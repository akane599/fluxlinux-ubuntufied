package com.ivarna.fluxlinux.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UbuntuRuntimeSwitchTest {

    private val proot = UbuntuRuntimeMode.PROOT
    private val chroot = UbuntuRuntimeMode.CHROOT

    @Test
    fun sameMode_isUnchangedEvenWithADesktopRunning() {
        assertEquals(
            RuntimeSwitchDecision.UNCHANGED,
            UbuntuRuntimeSwitch.decide(chroot, chroot, desktopActive = true, rootAvailable = false),
        )
    }

    @Test
    fun runningDesktop_mustBeStoppedFirstInBothDirections() {
        assertEquals(
            RuntimeSwitchDecision.STOP_DESKTOP_FIRST,
            UbuntuRuntimeSwitch.decide(proot, chroot, desktopActive = true, rootAvailable = true),
        )
        assertEquals(
            RuntimeSwitchDecision.STOP_DESKTOP_FIRST,
            UbuntuRuntimeSwitch.decide(chroot, proot, desktopActive = true, rootAvailable = true),
        )
    }

    @Test
    fun chrootTarget_needsRoot() {
        assertEquals(
            RuntimeSwitchDecision.NO_ROOT,
            UbuntuRuntimeSwitch.decide(proot, chroot, desktopActive = false, rootAvailable = false),
        )
        assertEquals(
            RuntimeSwitchDecision.SWITCH,
            UbuntuRuntimeSwitch.decide(proot, chroot, desktopActive = false, rootAvailable = true),
        )
    }

    @Test
    fun prootTarget_neverNeedsRoot() {
        assertEquals(
            RuntimeSwitchDecision.SWITCH,
            UbuntuRuntimeSwitch.decide(chroot, proot, desktopActive = false, rootAvailable = false),
        )
    }

    @Test
    fun modeFlow_followsSetModeAndActiveId() {
        val ctx = FakePrefsContext()
        UbuntuRuntime.setMode(ctx, proot)
        val flow = UbuntuRuntime.modeFlow(ctx)
        assertEquals(proot, flow.value)
        UbuntuRuntime.setMode(ctx, chroot)
        assertSame(flow, UbuntuRuntime.modeFlow(ctx))
        assertEquals(chroot, flow.value)
        assertEquals(UbuntuRuntime.CHROOT_ID, UbuntuRuntime.activeDistroId(ctx))
        UbuntuRuntime.setMode(ctx, proot)
        assertEquals(proot, flow.value)
    }
}
