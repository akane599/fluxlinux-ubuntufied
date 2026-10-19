package com.ivarna.fluxlinux.core.utils

import android.content.Context

/**
 * Ubuntu desktop launch preferences: auto-start on app open plus a crash-loop guard.
 *
 * The guard is a single "attempt pending" flag. [markAutoStartAttempt] sets it right before an
 * automatic start; [clearAutoStartAttempt] drops it once the desktop reports ready or the user
 * stops it. If the flag is still set on the next cold launch, the previous attempt never finished
 * (crash, kill, or failure), so [lastAutoStartFailed] is true and auto-start is skipped.
 */
object UbuntuLaunchPreferences {

    private const val PREFS_NAME = "flux_ubuntu_launch"
    private const val KEY_AUTO_START = "auto_start_desktop"
    private const val KEY_ATTEMPT_PENDING = "auto_start_attempt_pending"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoStartDesktop(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START, true)

    fun setAutoStartDesktop(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    /** Synchronous write: the flag must reach disk before a start that may crash the process. */
    fun markAutoStartAttempt(context: Context) {
        prefs(context).edit().putBoolean(KEY_ATTEMPT_PENDING, true).commit()
    }

    fun clearAutoStartAttempt(context: Context) {
        prefs(context).edit().remove(KEY_ATTEMPT_PENDING).apply()
    }

    fun lastAutoStartFailed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ATTEMPT_PENDING, false)
}
