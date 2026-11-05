package com.ivarna.fluxlinux.core.desktop

import android.content.Context
import androidx.annotation.WorkerThread
import com.ivarna.fluxlinux.core.chroot.ChrootDetection
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import com.ivarna.fluxlinux.core.install.ProotXfceAssetInstaller
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.core.utils.StateManager
import java.io.File
import java.nio.file.Files

/** What [UbuntuDesktop.detect] found out about the optional XFCE desktop of an install. */
enum class DesktopDetection {
    /** Installed: a verified run recorded it, or the guest has `startxfce4` and no run is unfinished. */
    INSTALLED,
    NOT_INSTALLED,

    /**
     * A run that installs the desktop started and never reached its verified end (it failed,
     * was cancelled or the process died). dpkg unpacks `startxfce4` early, so the file proves
     * nothing here; screens show this as a failed desktop install with a retry.
     */
    INCOMPLETE,
    ;

    val isInstalled: Boolean get() = this == INSTALLED
}

/**
 * Whether the optional XFCE desktop is installed in an Ubuntu guest. [detect] is the one
 * entry point; the rest is the state it reads and the install runner writes.
 *
 * State: the `xfce4_desktop` component flag (set once a run verified XFCE) and a per-distro
 * "desktop install incomplete" marker (set right before the family step of every run that
 * installs the desktop, cleared only on that run's verified success and on uninstall).
 * Older installs that predate the flag are recognised by `startxfce4` in the rootfs; a chroot
 * rootfs is not app-readable, so that check goes through root there.
 */
object UbuntuDesktop {

    /** StateManager component id the install runner and Distro Settings use for XFCE. */
    const val COMPONENT_ID = "xfce4_desktop"

    private const val PREFS = "fluxlinux_state"

    private fun incompleteKey(distroId: String) = "distro_${distroId}_desktop_install_incomplete"

    /**
     * Blocking (@WorkerThread): the desktop state of [distroId], verified when it can be.
     * - PRoot: the `startxfce4` file check, conclusive both ways.
     * - Chroot: the su probe when [mayAskRoot] (pass false until root was granted, so a
     *   denied prompt is not shown again); otherwise the stored state.
     * A conclusive check heals the stored flag, both ways, except that it is never set while
     * a desktop run is unfinished ([DesktopDetection.INCOMPLETE]).
     */
    @WorkerThread
    fun detect(ctx: Context, distroId: String, mayAskRoot: Boolean): DesktopDetection {
        val flag = StateManager.isComponentInstalled(ctx, distroId, COMPONENT_ID)
        val decision = decide(flag, isIncomplete(ctx, distroId), probe(ctx, distroId, mayAskRoot))
        decision.newFlag?.let { StateManager.setComponentInstalled(ctx, distroId, COMPONENT_ID, it) }
        return decision.detection
    }

    /** Prefs only, main-thread safe: the stored state, for a first frame before [detect] answers. */
    fun storedDetection(ctx: Context, distroId: String): DesktopDetection =
        decide(
            flag = StateManager.isComponentInstalled(ctx, distroId, COMPONENT_ID),
            incomplete = isIncomplete(ctx, distroId),
            probe = Probe.UNKNOWN,
        ).detection

    /** Prefs only, main-thread safe: a desktop run of [distroId] started and has not finished. */
    fun isIncomplete(ctx: Context, distroId: String): Boolean =
        prefs(ctx).getBoolean(incompleteKey(distroId), false)

    /**
     * Install runner, right before the family step of a run that installs the desktop. Written
     * synchronously (the runner's worker thread), so process death mid-run keeps it.
     */
    @WorkerThread
    internal fun markIncomplete(ctx: Context, distroId: String) {
        prefs(ctx).edit().putBoolean(incompleteKey(distroId), true).commit()
    }

    /** Install runner, verified success (startxfce4 found after the family step): installed. */
    @WorkerThread
    internal fun markInstalled(ctx: Context, distroId: String) {
        StateManager.setComponentInstalled(ctx, distroId, COMPONENT_ID, true)
        prefs(ctx).edit().remove(incompleteKey(distroId)).commit()
    }

    /** Uninstall ([StateManager.clearDistroState]): forget an unfinished desktop run. */
    internal fun clearIncomplete(ctx: Context, distroId: String) {
        prefs(ctx).edit().remove(incompleteKey(distroId)).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Blocking: `startxfce4` in the guest of [distroId] (file check or su), or UNKNOWN. */
    private fun probe(ctx: Context, distroId: String, mayAskRoot: Boolean): Probe {
        val profile = DistroInstallProfile.forId(distroId) ?: return Probe.UNKNOWN
        if (profile.method == "chroot") {
            if (!mayAskRoot) return Probe.UNKNOWN
            return when (ChrootDetection.probeXfce(profile.chrootPath ?: ChrootPaths.CHROOT_PATH)) {
                true -> Probe.FOUND
                false -> Probe.MISSING
                null -> Probe.UNKNOWN
            }
        }
        if (profile.prootName.isBlank()) return Probe.UNKNOWN
        val rootfs = ProotXfceAssetInstaller.prootRootfs(ctx, profile.prootName)
        return if (hasStartxfce4(rootfs)) Probe.FOUND else Probe.MISSING
    }

    // ── pure decisions (unit-tested) ─────────────────────────────────────────

    /** `startxfce4` under usr/bin or usr/sbin of [rootfs] (a symlink counts). */
    internal fun hasStartxfce4(rootfs: File): Boolean =
        listOf("usr/bin/startxfce4", "usr/sbin/startxfce4").any { rel ->
            val f = File(rootfs, rel)
            f.exists() || runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
        }

    /** A `startxfce4` check: found, missing, or no conclusive answer (no root, su failed). */
    internal enum class Probe { FOUND, MISSING, UNKNOWN }

    /** [detection] plus the flag value to store ([newFlag] null = leave it as it is). */
    internal data class Decision(val detection: DesktopDetection, val newFlag: Boolean?)

    /**
     * installed = flag || (!incomplete && startxfce4 found). A MISSING probe clears a stale
     * flag; a FOUND one sets the flag only when no desktop run is unfinished.
     */
    internal fun decide(flag: Boolean, incomplete: Boolean, probe: Probe): Decision = when {
        probe == Probe.MISSING -> Decision(
            detection = if (incomplete) DesktopDetection.INCOMPLETE else DesktopDetection.NOT_INSTALLED,
            newFlag = if (flag) false else null,
        )
        flag -> Decision(DesktopDetection.INSTALLED, newFlag = null)
        incomplete -> Decision(DesktopDetection.INCOMPLETE, newFlag = null)
        probe == Probe.FOUND -> Decision(DesktopDetection.INSTALLED, newFlag = true)
        else -> Decision(DesktopDetection.NOT_INSTALLED, newFlag = null)
    }
}
