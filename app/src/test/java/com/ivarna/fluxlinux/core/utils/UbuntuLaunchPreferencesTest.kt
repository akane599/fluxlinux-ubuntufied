package com.ivarna.fluxlinux.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuLaunchPreferencesTest {

    private val ctx = FakePrefsContext()
    private val P = UbuntuRuntime.PROOT_ID
    private val C = UbuntuRuntime.CHROOT_ID

    @Test
    fun autoStart_defaultsToOn() {
        assertTrue(UbuntuLaunchPreferences.isAutoStartDesktop(ctx))
    }

    @Test
    fun autoStart_roundTrips() {
        UbuntuLaunchPreferences.setAutoStartDesktop(ctx, false)
        assertFalse(UbuntuLaunchPreferences.isAutoStartDesktop(ctx))
        UbuntuLaunchPreferences.setAutoStartDesktop(ctx, true)
        assertTrue(UbuntuLaunchPreferences.isAutoStartDesktop(ctx))
    }

    @Test
    fun freshInstall_hasNoFailedAttempt() {
        assertFalse(UbuntuLaunchPreferences.lastAutoStartFailed(ctx, P))
    }

    @Test
    fun attemptThatNeverCleared_isReportedAsFailed() {
        UbuntuLaunchPreferences.markAutoStartAttempt(ctx, P)
        // Next cold launch reads the same prefs: the flag survived.
        assertTrue(UbuntuLaunchPreferences.lastAutoStartFailed(ctx, P))
    }

    @Test
    fun attemptClearedOnSuccess_isNotFailed() {
        UbuntuLaunchPreferences.markAutoStartAttempt(ctx, P)
        UbuntuLaunchPreferences.clearAutoStartAttempt(ctx, P)
        assertFalse(UbuntuLaunchPreferences.lastAutoStartFailed(ctx, P))
    }

    @Test
    fun guard_isIndependentOfAutoStartSwitch() {
        UbuntuLaunchPreferences.markAutoStartAttempt(ctx, P)
        UbuntuLaunchPreferences.setAutoStartDesktop(ctx, false)
        assertTrue(UbuntuLaunchPreferences.lastAutoStartFailed(ctx, P))
        assertFalse(UbuntuLaunchPreferences.isAutoStartDesktop(ctx))
    }

    @Test
    fun guard_isPerDistro_failedChrootDoesNotSuppressProot() {
        UbuntuLaunchPreferences.markAutoStartAttempt(ctx, C)
        assertTrue(UbuntuLaunchPreferences.lastAutoStartFailed(ctx, C))
        assertFalse(UbuntuLaunchPreferences.lastAutoStartFailed(ctx, P))
        UbuntuLaunchPreferences.markAutoStartAttempt(ctx, P)
        UbuntuLaunchPreferences.clearAutoStartAttempt(ctx, P)
        // Clearing PRoot keeps the chroot mark.
        assertTrue(UbuntuLaunchPreferences.lastAutoStartFailed(ctx, C))
    }

    @Test
    fun clearWithoutId_clearsEveryInstall() {
        UbuntuLaunchPreferences.markAutoStartAttempt(ctx, C)
        UbuntuLaunchPreferences.markAutoStartAttempt(ctx, P)
        UbuntuLaunchPreferences.clearAutoStartAttempt(ctx)
        assertFalse(UbuntuLaunchPreferences.lastAutoStartFailed(ctx, C))
        assertFalse(UbuntuLaunchPreferences.lastAutoStartFailed(ctx, P))
    }

    @Test
    fun legacyBooleanFlag_meansProotOnly() {
        ctx.getSharedPreferences("flux_ubuntu_launch", 0).edit()
            .putBoolean("auto_start_attempt_pending", true).commit()
        assertTrue(UbuntuLaunchPreferences.lastAutoStartFailed(ctx, P))
        assertFalse(UbuntuLaunchPreferences.lastAutoStartFailed(ctx, C))
        UbuntuLaunchPreferences.clearAutoStartAttempt(ctx, P)
        assertFalse(UbuntuLaunchPreferences.lastAutoStartFailed(ctx, P))
    }

    @Test
    fun autoStartTarget_cancelsWhenTheActiveInstallChanged() {
        assertEquals(C, UbuntuLaunchPreferences.autoStartTarget(C, C))
        assertNull(UbuntuLaunchPreferences.autoStartTarget(C, P))
        assertNull(UbuntuLaunchPreferences.autoStartTarget(null, P))
    }
}
