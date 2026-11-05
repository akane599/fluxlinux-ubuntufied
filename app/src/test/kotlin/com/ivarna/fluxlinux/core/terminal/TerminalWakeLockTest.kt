package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** "Hold wakelock": one lock, toggled from the notification/drawer, never held twice. */
class TerminalWakeLockTest {

    private class FakeLock(private val failAcquire: Boolean = false) : TerminalWakeLock.Lock {
        override var isHeld = false
        var acquires = 0
        var releases = 0
        override fun acquire() {
            acquires++
            if (failAcquire) throw SecurityException("no WAKE_LOCK")
            isHeld = true
        }
        override fun release() {
            releases++
            isHeld = false
        }
    }

    // The factory ignores the context, so the test never touches PowerManager.
    private val ctx: Context = ContextWrapper(null)

    private var created = 0
    private lateinit var fake: FakeLock

    private fun install(lock: FakeLock) {
        fake = lock
        TerminalWakeLock.lockFactory = { created++; fake }
    }

    @Before
    fun setUp() {
        TerminalWakeLock.resetForTest()
        install(FakeLock())
    }

    @After
    fun tearDown() = TerminalWakeLock.resetForTest()

    @Test
    fun toggle_holdsThenReleases_andPublishesState() {
        assertFalse(TerminalWakeLock.held.value)
        TerminalWakeLock.toggle(ctx)
        assertTrue(TerminalWakeLock.held.value)
        assertTrue(fake.isHeld)
        TerminalWakeLock.toggle(ctx)
        assertFalse(TerminalWakeLock.held.value)
        assertFalse(fake.isHeld)
    }

    @Test
    fun repeatedAcquire_reusesOneLockAndAcquiresOnce() {
        TerminalWakeLock.acquire(ctx)
        TerminalWakeLock.acquire(ctx)
        assertEquals(1, created)
        assertEquals(1, fake.acquires)
    }

    @Test
    fun release_withoutAHeldLock_isANoOp() {
        TerminalWakeLock.release()
        assertEquals(0, created)
        TerminalWakeLock.acquire(ctx)
        TerminalWakeLock.release()
        TerminalWakeLock.release()
        assertEquals(1, fake.releases)
        assertFalse(TerminalWakeLock.held.value)
    }

    @Test
    fun failedAcquire_reportsNotHeld() {
        install(FakeLock(failAcquire = true))
        TerminalWakeLock.acquire(ctx)
        assertFalse(TerminalWakeLock.held.value)
    }
}
