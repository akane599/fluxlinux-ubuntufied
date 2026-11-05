package com.ivarna.fluxlinux.core.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Hold wakelock" for terminal sessions, like Termux's notification action: a partial wake
 * lock keeps the CPU running with the screen off, so long jobs (apt, builds, an agent CLI)
 * aren't paused by Doze. It is opt-in and only lives while terminal sessions do:
 * `AppTerminalService` releases it when the last session closes or the service stops.
 * Toggled from the terminal notification and the terminal drawer.
 */
object TerminalWakeLock {

    /** The platform lock, behind a seam so the hold/release rules are JVM-tested. */
    internal interface Lock {
        val isHeld: Boolean
        fun acquire()
        fun release()
    }

    private val _held = MutableStateFlow(false)

    /** Whether the wake lock is held; drives the drawer switch and the notification action. */
    val held: StateFlow<Boolean> = _held.asStateFlow()

    private var lock: Lock? = null

    @VisibleForTesting
    internal var lockFactory: (Context) -> Lock = ::platformLock

    @Synchronized
    fun acquire(ctx: Context) {
        val l = lock ?: lockFactory(ctx).also { lock = it }
        if (!l.isHeld) runCatching { l.acquire() }
        _held.value = l.isHeld
    }

    @Synchronized
    fun release() {
        lock?.let { if (it.isHeld) runCatching { it.release() } }
        _held.value = false
    }

    fun toggle(ctx: Context) {
        if (_held.value) release() else acquire(ctx)
    }

    @VisibleForTesting
    @Synchronized
    internal fun resetForTest() {
        lock = null
        _held.value = false
    }

    // No timeout on purpose (as in Termux): the user holds it for a job of unknown length and
    // releases it from the notification, and it is dropped with the last session anyway.
    @SuppressLint("WakelockTimeout")
    private fun platformLock(ctx: Context): Lock {
        val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FluxLinux:terminal").apply {
            setReferenceCounted(false)
        }
        return object : Lock {
            override val isHeld: Boolean get() = wl.isHeld
            override fun acquire() = wl.acquire()
            override fun release() = wl.release()
        }
    }
}
