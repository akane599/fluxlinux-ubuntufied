package com.ivarna.fluxlinux.core.desktop

/**
 * Start/stop ordering for [DesktopLauncher], free of Android types so it can be unit-tested.
 *
 * Every start and every stop opens a new generation. Callbacks that belong to an older
 * generation (a stop script's exit, a superseded start's output, exit or early failure) are
 * stale and must not touch the session state.
 *
 * A start that reaches the launch step while a stop script is still running waits for it
 * instead of cancelling it: cancelling killed only the host bash, so the stop's guest
 * `killall` survived and could hit the new session.
 */
internal class DesktopRunState {

    private var generation = 0L
    private var stopsRunning = 0
    private var deferredStart: (() -> Unit)? = null

    /** Opens a start generation; earlier start/stop callbacks become stale. */
    @Synchronized
    fun beginStart(): Long = ++generation

    /** Opens a stop generation and drops a start still waiting for an earlier stop. */
    @Synchronized
    fun beginStop(): Long {
        deferredStart = null
        stopsRunning++
        return ++generation
    }

    @Synchronized
    fun isCurrent(gen: Long): Boolean = gen == generation

    @Synchronized
    fun isStopRunning(): Boolean = stopsRunning > 0

    /**
     * Runs [action] now when no stop script is running, or once the last running stop ends.
     * A stale [gen] is dropped.
     */
    fun runAfterStops(gen: Long, action: () -> Unit) {
        val runNow = synchronized(this) {
            if (gen != generation) return
            if (stopsRunning == 0) {
                true
            } else {
                deferredStart = action
                false
            }
        }
        if (runNow) action()
    }

    /**
     * A stop script finished.
     *
     * @property isLatest no start or stop began since, so the caller may write Idle.
     * @property resume a start that was waiting for the stops; run it after handling the stop.
     */
    class StopEnd(val isLatest: Boolean, val resume: (() -> Unit)?)

    @Synchronized
    fun endStop(gen: Long): StopEnd {
        if (stopsRunning > 0) stopsRunning--
        val resume = if (stopsRunning == 0) {
            deferredStart.also { deferredStart = null }
        } else {
            null
        }
        return StopEnd(isLatest = gen == generation, resume = resume)
    }
}
