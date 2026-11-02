package com.ivarna.fluxlinux.core.utils

import android.content.Context

/**
 * Ubuntu desktop launch preferences: auto-start on app open plus a crash-loop guard.
 *
 * The guard records, per distro id, an "attempt pending" mark. [markAutoStartAttempt] sets it
 * right before an automatic start of that install; [clearAutoStartAttempt] drops it once the
 * desktop reports ready or the user stops it. If the mark is still set on the next cold launch,
 * the previous attempt never finished (crash, kill, or failure), so [lastAutoStartFailed] is
 * true for that install and auto-start is skipped. Per id, so a failing chroot auto-start never
 * suppresses PRoot (and the reverse) after a runtime switch.
 */
object UbuntuLaunchPreferences {

    private const val PREFS_NAME = "flux_ubuntu_launch"
    private const val KEY_AUTO_START = "auto_start_desktop"

    /** Pre-chroot builds: one boolean, which could only mean the PRoot install. */
    private const val KEY_LEGACY_ATTEMPT_PENDING = "auto_start_attempt_pending"
    private const val KEY_ATTEMPTS_PENDING = "auto_start_attempts_pending"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoStartDesktop(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START, true)

    fun setAutoStartDesktop(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }

    private fun pending(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_ATTEMPTS_PENDING, null).orEmpty().toSet()

    /** Synchronous write: the mark must reach disk before a start that may crash the process. */
    fun markAutoStartAttempt(context: Context, distroId: String) {
        prefs(context).edit().putStringSet(KEY_ATTEMPTS_PENDING, pending(context) + distroId).commit()
    }

    /** Clears [distroId]'s mark, or every mark when [distroId] is null (the user stopped Ubuntu). */
    fun clearAutoStartAttempt(context: Context, distroId: String? = null) {
        val editor = prefs(context).edit()
        if (distroId == null) {
            editor.remove(KEY_ATTEMPTS_PENDING).remove(KEY_LEGACY_ATTEMPT_PENDING)
        } else {
            editor.putStringSet(KEY_ATTEMPTS_PENDING, pending(context) - distroId)
            if (distroId == UbuntuRuntime.PROOT_ID) editor.remove(KEY_LEGACY_ATTEMPT_PENDING)
        }
        editor.apply()
    }

    fun lastAutoStartFailed(context: Context, distroId: String): Boolean =
        distroId in pending(context) ||
            (distroId == UbuntuRuntime.PROOT_ID && prefs(context).getBoolean(KEY_LEGACY_ATTEMPT_PENDING, false))

    /**
     * The install a restored or finished auto-start countdown may launch: the one the
     * preconditions were checked for ([checkedId]), and only while it is still the active
     * install ([activeIdNow]). A runtime switch during the countdown cancels it (null).
     */
    fun autoStartTarget(checkedId: String?, activeIdNow: String): String? =
        checkedId?.takeIf { it == activeIdNow }
}
