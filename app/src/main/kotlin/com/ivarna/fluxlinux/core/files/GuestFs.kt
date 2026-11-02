package com.ivarna.fluxlinux.core.files

import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Host-filesystem seam under [GuestTree] and [UbuntuDocumentsProvider]: [LocalGuestFs] reads
 * the PRoot rootfs directly (app uid), [RootGuestFs] reaches the chroot rootfs through su.
 *
 * Paths are absolute host paths. Callers ([GuestTree]) only pass paths whose PARENT is free of
 * symlinks (resolved component by component), so no primitive here ever traverses a link;
 * [lstat]/[readLink]/[move]/[deleteTree] act on the final component itself.
 */
interface GuestFs {

    enum class Type { FILE, DIR, LINK, OTHER }

    data class Stat(
        val type: Type,
        val size: Long,
        val mtimeMillis: Long,
        val mode: Int = 0,
        val uid: Int = -1,
        val gid: Int = -1,
    )

    /** Canonical path of an anchor (rootfs, shared storage root); null when unavailable. */
    fun realPath(path: String): String?

    /**
     * Optional batching hint: [GuestTree] is about to look up `base/names[0]`, then
     * `base/names[0]/names[1]`, … ([base] is a link-free directory). No-op by default.
     */
    fun hint(base: String, names: List<String>) {}

    /** Metadata of the entry itself (never follows the final component); null when missing. */
    fun lstat(path: String): Stat?

    /** Raw target of a symlink; null when [path] is not a link or cannot be read. */
    fun readLink(path: String): String?

    /** Child names of a directory. Throws [java.io.IOException] when it cannot be listed. */
    fun list(dir: String): List<String>

    /** Whether this app may modify [path] (an existing, resolved entry). */
    fun canWrite(path: String): Boolean

    /** True when [path] is on (or is) a filesystem mounted inside the rootfs (/proc, /sdcard bind, …). */
    fun isForeignMount(path: String): Boolean = false

    /** True when a filesystem is mounted at or below [path] (a recursive op would cross into it). */
    fun containsForeignMount(path: String): Boolean = false

    /** Free bytes for the filesystem holding [path]; null when unknown. */
    fun usableSpace(path: String): Long?

    fun createFile(path: String): Boolean

    fun mkdir(path: String): Boolean

    /** Deletes the entry itself; recursion never follows links (a link is unlinked, never its target). */
    fun deleteTree(path: String): Boolean

    /** Renames/moves the entry itself (a link moves as a link). Throws [FileNotFoundException] on failure. */
    fun move(from: String, to: String)

    /** Copies without following links and without overwriting. */
    fun copyTree(from: String, to: String): Boolean

    /** Opens a resolved regular file for SAF. Throws [FileNotFoundException] when unsupported or failing. */
    fun open(path: String, mode: String): ParcelFileDescriptor
}

/** A root-backed operation could not run (no root, timeout, chroot missing). Surfaces as a SAF error, never a crash. */
class RootFsException(message: String) : FileNotFoundException(message)

/** String path helpers for absolute, normalized host paths. */
internal object HostPaths {
    fun child(dir: String, name: String): String = if (dir.endsWith("/")) dir + name else "$dir/$name"

    fun parent(path: String): String? =
        if (path == "/" || !path.startsWith("/")) null else path.substringBeforeLast('/').ifEmpty { "/" }

    fun name(path: String): String = path.substringAfterLast('/')

    /** Component-wise "starts with" ([path] equals [prefix] or lies below it). */
    fun isUnder(path: String, prefix: String): Boolean =
        prefix == "/" || path == prefix || path.startsWith("$prefix/")

    /** Path components without empty and `.` segments (`..` is kept). */
    fun components(p: String): List<String> = p.split('/').filter { it.isNotEmpty() && it != "." }

    /** [root], then every directory from [root] down to [dir] (inclusive); empty when [dir] is not under [root]. */
    fun prefixes(root: String, dir: String): List<String> {
        if (!isUnder(dir, root)) return emptyList()
        val out = mutableListOf(root)
        var cur = root
        for (name in components(dir.removePrefix(root))) {
            cur = child(cur, name)
            out += cur
        }
        return out
    }
}
