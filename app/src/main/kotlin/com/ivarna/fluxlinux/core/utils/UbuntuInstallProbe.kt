package com.ivarna.fluxlinux.core.utils

import android.content.Context
import androidx.annotation.WorkerThread
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher

/** Install state of one Ubuntu install as the Ubuntu-first surfaces (Home, auto-start) see it. */
enum class UbuntuInstallCheck {
    /** Chroot mode: the root probe or the chroot detection has not answered yet. */
    CHECKING,
    INSTALLED,
    NOT_INSTALLED,

    /** Chroot mode without root: whether the chroot exists cannot be known. */
    NO_ROOT,
}

/**
 * Method-aware install checks for the two Ubuntu installs ([UbuntuRuntime.PROOT_ID],
 * [UbuntuRuntime.CHROOT_ID]). PRoot is a plain file check; chroot needs `su` (the rootfs lives
 * outside app storage), so [probe] must run off the main thread, where
 * [com.ivarna.fluxlinux.core.chroot.ChrootDetection] would only answer from its cache.
 */
object UbuntuInstallProbe {

    /**
     * Pure mapping. [rootAvailable] and [chrootInstalled] are null while still unknown, which
     * reads as [UbuntuInstallCheck.CHECKING] rather than "not installed".
     */
    fun check(
        mode: UbuntuRuntimeMode,
        prootInstalled: Boolean,
        rootAvailable: Boolean?,
        chrootInstalled: Boolean?,
    ): UbuntuInstallCheck = when (mode) {
        UbuntuRuntimeMode.PROOT ->
            if (prootInstalled) UbuntuInstallCheck.INSTALLED else UbuntuInstallCheck.NOT_INSTALLED
        UbuntuRuntimeMode.CHROOT -> when (rootAvailable) {
            null -> UbuntuInstallCheck.CHECKING
            false -> UbuntuInstallCheck.NO_ROOT
            true -> when (chrootInstalled) {
                null -> UbuntuInstallCheck.CHECKING
                true -> UbuntuInstallCheck.INSTALLED
                false -> UbuntuInstallCheck.NOT_INSTALLED
            }
        }
    }

    /** File check only; safe on the main thread. */
    fun prootInstalled(ctx: Context): Boolean =
        runCatching { TerminalLauncher.isProotInstalled(ctx, UbuntuRuntime.PROOT_ID) }.getOrDefault(false)

    /**
     * Install state of [mode]'s Ubuntu. Blocking: for chroot it probes `su` and the rootfs
     * markers through root. Never call on the main thread.
     */
    @WorkerThread
    fun probe(ctx: Context, mode: UbuntuRuntimeMode): UbuntuInstallCheck = when (mode) {
        UbuntuRuntimeMode.PROOT -> check(mode, prootInstalled(ctx), null, null)
        UbuntuRuntimeMode.CHROOT -> {
            val root = runCatching { RootShell.isRootAvailable() }.getOrDefault(false)
            val installed = root && runCatching {
                TerminalLauncher.isDistroInstalledOnFs(ctx, UbuntuRuntime.CHROOT_ID)
            }.getOrDefault(false)
            check(mode, prootInstalled = false, rootAvailable = root, chrootInstalled = installed)
        }
    }

    /**
     * Whether the chroot install is worth probing while PRoot is active, without a `su`
     * prompt for people who never set chroot up: only once it was installed through the app.
     */
    fun chrootEverInstalled(ctx: Context): Boolean =
        runCatching { StateManager.isDistroInstalled(ctx, UbuntuRuntime.CHROOT_ID) }.getOrDefault(false)
}
