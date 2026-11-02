package com.ivarna.fluxlinux.core.utils

import android.content.Context

/** How the Ubuntu guest runs: PRoot (no root) or chroot (rooted device). */
enum class UbuntuRuntimeMode(val prefValue: String, val distroId: String) {
    PROOT("proot", UbuntuRuntime.PROOT_ID),
    CHROOT("chroot", UbuntuRuntime.CHROOT_ID),
    ;

    companion object {
        fun fromPref(value: String?): UbuntuRuntimeMode? = entries.firstOrNull { it.prefValue == value }
    }
}

/**
 * Single source of truth for which Ubuntu install the app's Ubuntu-first surfaces (Home,
 * auto-start, onboarding, terminal default tab, Files) act on.
 *
 * PRoot (`ubuntu`) and chroot (`ubuntu_chroot`) are separate rootfs trees that can coexist;
 * switching the mode never touches either install. Both log in as [UBUNTU_GUEST_USER]; older
 * PRoot installs created as `flux` are migrated in place ([LEGACY_GUEST_USER]). Other (legacy)
 * distros keep `flux`.
 * The stored mode is only a preference: callers that need root must still check root
 * availability and chroot install state off the main thread.
 */
object UbuntuRuntime {

    const val PROOT_ID = "ubuntu"
    const val CHROOT_ID = "ubuntu_chroot"

    /** Guest login user of both Ubuntu installs (PRoot and chroot). */
    const val UBUNTU_GUEST_USER = "akane"
    const val UBUNTU_GUEST_HOME = "/home/$UBUNTU_GUEST_USER"

    /** User of pre-akane Ubuntu PRoot installs and of every other (legacy) distro. */
    const val LEGACY_GUEST_USER = "flux"
    const val LEGACY_GUEST_HOME = "/home/$LEGACY_GUEST_USER"

    /** Non-root guest user for [distroId]: akane for Ubuntu (both modes), flux otherwise. */
    fun guestUserFor(distroId: String?): String =
        if (isUbuntuId(distroId)) UBUNTU_GUEST_USER else LEGACY_GUEST_USER

    fun guestHomeFor(distroId: String?): String = "/home/" + guestUserFor(distroId)

    private const val PREFS_NAME = "flux_ubuntu_runtime"
    private const val KEY_MODE = "runtime_mode"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The chosen mode, or [UbuntuRuntimeMode.PROOT] when nothing was chosen yet. */
    fun mode(context: Context): UbuntuRuntimeMode =
        UbuntuRuntimeMode.fromPref(prefs(context).getString(KEY_MODE, null)) ?: UbuntuRuntimeMode.PROOT

    /** True once the user (or onboarding) picked a mode explicitly. */
    fun hasExplicitMode(context: Context): Boolean = prefs(context).contains(KEY_MODE)

    fun setMode(context: Context, mode: UbuntuRuntimeMode) {
        prefs(context).edit().putString(KEY_MODE, mode.prefValue).apply()
    }

    /** Distro id of the active Ubuntu install (`ubuntu` or `ubuntu_chroot`). */
    fun activeDistroId(context: Context): String = mode(context).distroId

    fun isUbuntuId(distroId: String?): Boolean = distroId == PROOT_ID || distroId == CHROOT_ID

    fun isChrootId(distroId: String?): Boolean = distroId == CHROOT_ID
}
