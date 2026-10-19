package com.ivarna.fluxlinux.core.system

import android.content.Context
import android.util.Log
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
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
 */
object GuestStorageLinks {
    private const val TAG = "GuestStorageLinks"
    private const val STORAGE_DIR = "storage"
    private val NOFOLLOW = LinkOption.NOFOLLOW_LINKS

    /** Guest home directories, relative to the rootfs. Only ones that already exist are touched. */
    private val HOMES = listOf("home/flux", "root")

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

    data class Report(val created: Int, val existing: Int, val skipped: List<String>)

    /**
     * For each existing guest home under [rootfs]: create `storage` (real dir) if missing,
     * then every missing link. Anything already present at a link name that is not the
     * expected link is left alone and listed in [Report.skipped]; a `storage` that is a
     * symlink or a file skips that whole home.
     */
    fun ensureIn(rootfs: File): Report {
        var created = 0
        var existing = 0
        val skipped = mutableListOf<String>()
        for (rel in HOMES) {
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

    /** Ensures links in the distro's proot rootfs; null when that rootfs does not exist. */
    fun ensure(ctx: Context, distroId: String = "ubuntu"): Report? = try {
        rootfsFor(ctx, distroId)?.let { ensureIn(it) }
    } catch (e: Exception) {
        Log.w(TAG, "ensure($distroId) failed: ${e.message}")
        null
    }

    /** True when at least one guest home exists and every existing home has all expected links. */
    fun linksPresent(ctx: Context, distroId: String = "ubuntu"): Boolean = try {
        val rootfs = rootfsFor(ctx, distroId)?.toPath()
        val homes = rootfs?.let { r -> HOMES.mapNotNull { realDirOrNull(r, it) } }.orEmpty()
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
