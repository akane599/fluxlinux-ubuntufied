package com.ivarna.fluxlinux.core.terminal

import android.util.Log
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Host-side repair for Ubuntu guests whose uutils coreutils stopped working under PRoot.
 *
 * rust-coreutils ships `/usr/lib/cargo/bin/coreutils/<util>` as hard links to one multicall
 * binary. PRoot's `--link2symlink` (needed: Android forbids hard links in app data) turns them
 * into symlinks to `/.l2s/.l2s.coreutils…`, and Ubuntu's uutils then refuses every utility
 * because argv[0] no longer matches the executable name (uutils/coreutils#10135). `env` breaks
 * with the rest, so no guest command, the launcher's `env -i` included, can run a fix.
 *
 * This repoints each broken `/usr/bin/<util>` (and `/usr/sbin`) link at the GNU build
 * `gnu<util>` from gnu-coreutils, which is what coreutils-from-gnu installs. The Ubuntu family
 * setup then switches the package for good. It only touches links whose uutils target is an
 * l2s link and whose GNU twin exists; a chroot rootfs (real hard links) is left alone.
 */
internal object GuestCoreutilsRepair {

    private const val TAG = "GuestCoreutilsRepair"
    private const val CARGO_DIR = "usr/lib/cargo/bin/coreutils"
    private val BIN_DIRS = listOf("usr/bin", "usr/sbin")
    private val NOFOLLOW = arrayOf(LinkOption.NOFOLLOW_LINKS)

    /** Repairs the proot container behind [distroId] (same fallback as LinuxCommandBuilder). */
    fun repairIfNeeded(distroId: String?) {
        val prootName = distroId?.let { DistroInstallProfile.forId(it)?.prootName }
            ?.ifBlank { null } ?: "debian"
        val rootfs = File("${TermuxHostPaths.PREFIX}/var/lib/proot-distro/containers/$prootName/rootfs")
        val fixed = runCatching { relinkToGnu(rootfs) }.getOrElse {
            Log.w(TAG, "coreutils repair failed for $prootName: ${it.message}")
            return
        }
        if (fixed > 0) {
            Log.i(TAG, "$prootName: repointed $fixed uutils links to GNU coreutils")
        }
    }

    /** Returns the number of links repointed; 0 when nothing is broken. */
    fun relinkToGnu(rootfs: File): Int {
        val names = File(rootfs, CARGO_DIR).list() ?: return 0
        var fixed = 0
        for (name in names) {
            if (!isL2sLink(File(rootfs, "$CARGO_DIR/$name").toPath())) continue
            for (dir in BIN_DIRS) {
                if (relink(File(rootfs, dir).toPath(), name)) fixed++
            }
        }
        return fixed
    }

    private fun isL2sLink(p: Path): Boolean =
        Files.isSymbolicLink(p) &&
            readLink(p)?.fileName?.toString()?.startsWith(".l2s.") == true

    private fun relink(dir: Path, name: String): Boolean {
        val link = dir.resolve(name)
        val target = if (Files.isSymbolicLink(link)) readLink(link) else null
        if (target == null || !target.endsWith(Paths.get("lib/cargo/bin/coreutils", name))) {
            return false
        }
        // Same dir first; gnu-coreutils keeps gnuchroot in sbin while uutils links bin/chroot.
        val gnu = (listOf("gnu$name") + BIN_DIRS.map { "../${File(it).name}/gnu$name" })
            .map { Paths.get(it) }
            .firstOrNull { Files.exists(dir.resolve(it), *NOFOLLOW) }
            ?: return false
        // Swap via rename so the utility never disappears, even if the app dies mid-repair.
        val tmp = dir.resolve(".$name.flux-gnu")
        return try {
            Files.deleteIfExists(tmp)
            Files.createSymbolicLink(tmp, gnu)
            Files.move(tmp, link, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(tmp) }
            Log.w(TAG, "cannot relink $link: ${e.message}")
            false
        }
    }

    private fun readLink(p: Path): Path? = try {
        Files.readSymbolicLink(p)
    } catch (_: IOException) {
        null
    }
}
