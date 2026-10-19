package com.ivarna.fluxlinux.core.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuLaunchPreferencesTest {

    private val ctx = FakePrefsContext()

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
        assertFalse(UbuntuLaunchPreferences.lastAutoStartFailed(ctx))
    }

    @Test
    fun attemptThatNeverCleared_isReportedAsFailed() {
        UbuntuLaunchPreferences.markAutoStartAttempt(ctx)
        // Next cold launch reads the same prefs: the flag survived.
        assertTrue(UbuntuLaunchPreferences.lastAutoStartFailed(ctx))
    }

    @Test
    fun attemptClearedOnSuccess_isNotFailed() {
        UbuntuLaunchPreferences.markAutoStartAttempt(ctx)
        UbuntuLaunchPreferences.clearAutoStartAttempt(ctx)
        assertFalse(UbuntuLaunchPreferences.lastAutoStartFailed(ctx))
    }

    @Test
    fun guard_isIndependentOfAutoStartSwitch() {
        UbuntuLaunchPreferences.markAutoStartAttempt(ctx)
        UbuntuLaunchPreferences.setAutoStartDesktop(ctx, false)
        assertTrue(UbuntuLaunchPreferences.lastAutoStartFailed(ctx))
        assertFalse(UbuntuLaunchPreferences.isAutoStartDesktop(ctx))
    }
}
