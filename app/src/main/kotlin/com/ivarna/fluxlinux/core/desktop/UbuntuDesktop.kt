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

/**
 * Whether the optional XFCE desktop is installed in an Ubuntu guest.
 *
 * Source of truth is the `xfce4_desktop` component flag the install runner sets once XFCE
 * is verified; PRoot installs (and older ones that predate the flag) are also recognised by
 * `startxfce4` in the rootfs. A chroot rootfs is not app-readable, so [probe] asks root.
 */
object UbuntuDesktop {

    /** StateManager component id the install runner and Distro Settings use for XFCE. */
    const val COMPONENT_ID = "xfce4_desktop"

    /** Main-thread safe: StateManager component flag "xfce4_desktop" || (PRoot: rootfs usr/bin|usr/sbin/startxfce4 exists). */
    fun isInstalled(ctx: Context, distroId: String): Boolean =
        isInstalled(
            flag = StateManager.isComponentInstalled(ctx, distroId, COMPONENT_ID),
            prootRootfs = prootRootfs(ctx, distroId),
        )

    /** Blocking (@WorkerThread): PRoot file check; chroot via ChrootDetection.isXfceInstalled (su). Self-heals the flag (true when found; false only on a definite "root answered, not found"). */
    @WorkerThread
    fun probe(ctx: Context, distroId: String): Boolean {
        val flag = StateManager.isComponentInstalled(ctx, distroId, COMPONENT_ID)
        val profile = DistroInstallProfile.forId(distroId)
        val found: Boolean? = when {
            profile == null -> null
            profile.method == "chroot" ->
                ChrootDetection.probeXfce(profile.chrootPath ?: ChrootPaths.CHROOT_PATH)
            else -> prootRootfs(ctx, distroId)?.let { hasStartxfce4(it) }
        }
        healedFlag(flag, found)?.let { StateManager.setComponentInstalled(ctx, distroId, COMPONENT_ID, it) }
        return probeResult(flag, found)
    }

    /** PRoot rootfs of [distroId]; null for chroot or unknown ids (no app-visible rootfs). */
    private fun prootRootfs(ctx: Context, distroId: String): File? {
        val profile = DistroInstallProfile.forId(distroId) ?: return null
        if (profile.method == "chroot" || profile.prootName.isBlank()) return null
        return ProotXfceAssetInstaller.prootRootfs(ctx, profile.prootName)
    }

    // ── pure decisions (unit-tested) ─────────────────────────────────────────

    /** The flag, else (PRoot only) `startxfce4` in [prootRootfs]. */
    internal fun isInstalled(flag: Boolean, prootRootfs: File?): Boolean =
        flag || (prootRootfs != null && hasStartxfce4(prootRootfs))

    /** `startxfce4` under usr/bin or usr/sbin of [rootfs] (a symlink counts). */
    internal fun hasStartxfce4(rootfs: File): Boolean =
        listOf("usr/bin/startxfce4", "usr/sbin/startxfce4").any { rel ->
            val f = File(rootfs, rel)
            f.exists() || runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
        }

    /**
     * New flag value to store after a probe that [found] (null = inconclusive), or null to
     * leave [flag] as it is: set when found, cleared only on a definite "not found".
     */
    internal fun healedFlag(flag: Boolean, found: Boolean?): Boolean? = when (found) {
        null -> null
        flag -> null
        else -> found
    }

    /** A conclusive probe wins; an inconclusive one keeps the stored [flag]. */
    internal fun probeResult(flag: Boolean, found: Boolean?): Boolean = found ?: flag
}
