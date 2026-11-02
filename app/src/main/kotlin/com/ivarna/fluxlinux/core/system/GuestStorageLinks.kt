package com.ivarna.fluxlinux.core.system

import android.content.Context
import android.os.Looper
import android.util.Log
import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.core.root.GuestUsers
import com.ivarna.fluxlinux.core.root.RootShell
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Termux-style `~/storage/{shared,downloads,...}` links inside a proot guest.
 *
 * Link targets are GUEST absolute paths that resolve inside proot through
 * `ProotCommandBuilder.storageBinds()`. On the host they are dangling or point at
 * real shared storage, so nothing here ever follows or traverses a link: every
 * existence check uses [LinkOption.NOFOLLOW_LINKS], and nothing that already
 * exists is deleted, renamed or overwritten. IO failures are logged, never thrown.
 *
 * Chroot distros (`ubuntu_chroot`, …) live under root-only `/data/local/tmp`: [ensure] and
 * [linksPresent] delegate to `fluxlinux_chroot.sh links` / `links-status`, which apply the
 * same rules as root inside the chroot for the guest user's home (akane on Ubuntu) and
 * `/root`, with [CHROOT_LINKS] targets (the chroot binds only `/sdcard`) and links owned by
 * the user. Those calls block on su: background threads only (main thread → unknown/null).
 */
object GuestStorageLinks {
    private const val TAG = "GuestStorageLinks"
    private const val STORAGE_DIR = "storage"
    private val NOFOLLOW = LinkOption.NOFOLLOW_LINKS

    /**
     * Guest home directories of [distroId], relative to the rootfs: the distro's user
     * ([UbuntuRuntime.guestHomeFor], akane on Ubuntu), the legacy `home/flux` (a real dir
     * only on an Ubuntu install whose migration has not run; once migrated it is a compat
     * symlink and skipped) and `root`. Only real directories that already exist are touched.
     */
    internal fun homesFor(distroId: String?): List<String> =
        listOf(
            UbuntuRuntime.guestHomeFor(distroId).removePrefix("/"),
            UbuntuRuntime.LEGACY_GUEST_HOME.removePrefix("/"),
            "root",
        ).distinct()

    data class Link(val name: String, val target: String)

    val LINKS: List<Link> = listOf(
        Link("shared", "/storage/emulated/0"),
        Link("downloads", "/storage/emulated/0/Download"),
        Link("documents", "/storage/emulated/0/Documents"),
        Link("dcim", "/storage/emulated/0/DCIM"),
        Link("pictures", "/storage/emulated/0/Pictures"),
        Link("music", "/storage/emulated/0/Music"),
        Link("movies", "/storage/emulated/0/Movies"),
    )

    /** Chroot link targets: `/sdcard` is the only shared-storage bind inside a chroot. */
    val CHROOT_LINKS: List<Link> = listOf(
        Link("shared", "/sdcard"),
        Link("downloads", "/sdcard/Download"),
        Link("documents", "/sdcard/Documents"),
        Link("dcim", "/sdcard/DCIM"),
        Link("pictures", "/sdcard/Pictures"),
        Link("music", "/sdcard/Music"),
        Link("movies", "/sdcard/Movies"),
    )

    data class Report(val created: Int, val existing: Int, val skipped: List<String>)

    /**
     * For each existing guest home under [rootfs]: create `storage` (real dir) if missing,
     * then every missing link. Anything already present at a link name that is not the
     * expected link is left alone and listed in [Report.skipped]; a `storage` that is a
     * symlink or a file skips that whole home.
     */
    fun ensureIn(rootfs: File, distroId: String? = null): Report {
        var created = 0
        var existing = 0
        val skipped = mutableListOf<String>()
        for (rel in homesFor(distroId)) {
            val home = realDirOrNull(rootfs.toPath(), rel) ?: continue
            val storage = home.resolve(STORAGE_DIR)
            val storageRel = "$rel/$STORAGE_DIR"
            try {
                if (Files.exists(storage, NOFOLLOW)) {
                    if (!Files.isDirectory(storage, NOFOLLOW)) {
                        skipped += storageRel
                        continue
                    }
                } else {
                    Files.createDirectory(storage)
                }
            } catch (e: Exception) {
                Log.w(TAG, "cannot prepare $storageRel: ${e.message}")
                skipped += storageRel
                continue
            }
            for (link in LINKS) {
                when (ensureLink(storage.resolve(link.name), link.target)) {
                    LinkResult.CREATED -> created++
                    LinkResult.EXISTING -> existing++
                    LinkResult.SKIPPED -> skipped += "$storageRel/${link.name}"
                }
            }
        }
        return Report(created, existing, skipped)
    }

    /**
     * Ensures links in the distro's rootfs; null when that rootfs does not exist (or, for a
     * chroot distro, when root is unavailable, the call timed out or runs on the main thread).
     */
    fun ensure(ctx: Context, distroId: String = "ubuntu"): Report? = try {
        if (isChroot(distroId)) {
            ensureChroot(ctx, distroId)
        } else {
            rootfsFor(ctx, distroId)?.let { ensureIn(it, distroId) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "ensure($distroId) failed: ${e.message}")
        null
    }

    /**
     * True when at least one guest home exists and every existing home has all expected links.
     * Chroot distros: false whenever that cannot be checked ([chrootLinksStatus] null).
     */
    fun linksPresent(ctx: Context, distroId: String = "ubuntu"): Boolean =
        if (isChroot(distroId)) chrootLinksStatus(ctx, distroId) == true else prootLinksPresent(ctx, distroId)

    /**
     * Chroot link state via a root probe: true/false, or null when unknown (no root, su
     * timeout, helper not verified, main thread). Blocking: call off the main thread.
     */
    fun chrootLinksStatus(ctx: Context, distroId: String): Boolean? =
        runChrootLinks(ctx, distroId, ensure = false)?.present

    /** Chroot `ensure`: null when it could not run (see [chrootLinksStatus]). Blocking. */
    fun ensureChroot(ctx: Context, distroId: String): Report? =
        runChrootLinks(ctx, distroId, ensure = true)?.report

    private fun isChroot(distroId: String): Boolean =
        DistroInstallProfile.forId(distroId)?.method == "chroot"

    private fun runChrootLinks(ctx: Context, distroId: String, ensure: Boolean): ChrootLinksLine? {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "chroot links($distroId) skipped on the main thread")
            return null
        }
        return try {
            val path = DistroInstallProfile.forId(distroId)?.chrootPath ?: ChrootPaths.pathForDistro(distroId)
            val res = RootShell.chrootStorageLinks(
                ctx.applicationContext ?: ctx, ensure, GuestUsers.chrootUser(distroId), path,
            )
            parseChrootLinksLine(res.exitCode, res.stdout).also {
                if (it == null) Log.w(TAG, "chroot links($distroId) failed exit=${res.exitCode}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "chroot links($distroId) failed: ${e.message}")
            null
        }
    }

    /** Parsed `FLUX_LINKS mode=… created=N existing=N present=0|1 skipped=a,b` helper line. */
    internal data class ChrootLinksLine(val report: Report, val present: Boolean)

    internal fun parseChrootLinksLine(exitCode: Int, stdout: String): ChrootLinksLine? {
        if (exitCode != 0) return null
        val line = stdout.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("FLUX_LINKS ") }
            ?: return null
        val fields = line.removePrefix("FLUX_LINKS ").split(' ')
            .mapNotNull { tok -> tok.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
        val created = fields["created"]?.toIntOrNull() ?: return null
        val existing = fields["existing"]?.toIntOrNull() ?: return null
        val present = when (fields["present"]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val skipped = fields["skipped"].orEmpty().split(',').filter { it.isNotBlank() }
        return ChrootLinksLine(Report(created, existing, skipped), present)
    }

    private fun prootLinksPresent(ctx: Context, distroId: String): Boolean = try {
        val rootfs = rootfsFor(ctx, distroId)?.toPath()
        val homes = rootfs?.let { r -> homesFor(distroId).mapNotNull { realDirOrNull(r, it) } }.orEmpty()
        homes.isNotEmpty() && homes.all { home ->
            LINKS.all { isExpectedLink(home.resolve(STORAGE_DIR).resolve(it.name), it.target) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "linksPresent($distroId) failed: ${e.message}")
        false
    }

    private enum class LinkResult { CREATED, EXISTING, SKIPPED }

    private fun ensureLink(path: Path, target: String): LinkResult = try {
        when {
            isExpectedLink(path, target) -> LinkResult.EXISTING
            Files.exists(path, NOFOLLOW) -> LinkResult.SKIPPED
            else -> {
                Files.createSymbolicLink(path, Paths.get(target))
                LinkResult.CREATED
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "cannot link $path -> $target: ${e.message}")
        LinkResult.SKIPPED
    }

    private fun isExpectedLink(path: Path, target: String): Boolean =
        Files.isSymbolicLink(path) && Files.readSymbolicLink(path).toString() == target

    private fun rootfsFor(ctx: Context, distroId: String): File? {
        val prootName = DistroInstallProfile.forId(distroId)?.prootName ?: return null
        val rootfs = File(ctx.filesDir, "usr/var/lib/proot-distro/containers/$prootName/rootfs")
        return rootfs.takeIf { Files.isDirectory(it.toPath(), NOFOLLOW) }
    }

    /**
     * [root]/[rel] when every component below [root] is a real directory (no symlink),
     * so a guest home symlinked to an absolute path never escapes onto the host.
     */
    private fun realDirOrNull(root: Path, rel: String): Path? {
        var p = root
        for (part in rel.split('/')) {
            p = p.resolve(part)
            if (!Files.isDirectory(p, NOFOLLOW)) return null
        }
        return p
    }
}
