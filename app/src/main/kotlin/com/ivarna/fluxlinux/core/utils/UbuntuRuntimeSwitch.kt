package com.ivarna.fluxlinux.core.utils

import android.content.Context
import com.ivarna.fluxlinux.core.files.UbuntuFiles

/** Outcome of asking to switch the Ubuntu runtime (Settings row, Home chip, onboarding). */
enum class RuntimeSwitchDecision {
    /** Already the active mode: nothing to do. */
    UNCHANGED,

    /** Switch now. */
    SWITCH,

    /** A desktop runs (or starts): it must be stopped first, the mode stays. */
    STOP_DESKTOP_FIRST,

    /** Chroot without root: the mode stays PRoot. */
    NO_ROOT,
}

/** Switching between PRoot and chroot Ubuntu. Neither install is touched, only the preference. */
object UbuntuRuntimeSwitch {

    /**
     * Pure decision. [rootAvailable] is only consulted for a chroot target and must come from
     * an off-main probe ([com.ivarna.fluxlinux.core.root.RootShell.probeRootAvailable]).
     */
    fun decide(
        current: UbuntuRuntimeMode,
        target: UbuntuRuntimeMode,
        desktopActive: Boolean,
        rootAvailable: Boolean,
    ): RuntimeSwitchDecision = when {
        current == target -> RuntimeSwitchDecision.UNCHANGED
        desktopActive -> RuntimeSwitchDecision.STOP_DESKTOP_FIRST
        target == UbuntuRuntimeMode.CHROOT && !rootAvailable -> RuntimeSwitchDecision.NO_ROOT
        else -> RuntimeSwitchDecision.SWITCH
    }

    /**
     * Persists [mode] (observers of [UbuntuRuntime.modeFlow] recompose), re-points the
     * Files roots at the new rootfs and refreshes install-state readers.
     */
    fun apply(ctx: Context, mode: UbuntuRuntimeMode) {
        UbuntuRuntime.setMode(ctx, mode)
        runCatching { UbuntuFiles.notifyRootsChanged(ctx) }
        runCatching { StateManager.triggerRefresh() }
    }
}
