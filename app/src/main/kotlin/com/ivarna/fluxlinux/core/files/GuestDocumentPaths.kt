package com.ivarna.fluxlinux.core.files

import com.ivarna.fluxlinux.core.system.SafeFiles
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Pure (no `android.*`) document-id codec and guest-aware path resolver for
 * [UbuntuDocumentsProvider].
 *
 * Document ids are `"<rootId>:<relPath>"`; `relPath` is "" for the root document and
 * otherwise a `/`-joined list of plain names relative to the root's guest base path.
 *
 * Guest symlinks are resolved the way the guest sees them (absolute targets are inside
 * the rootfs, shared-storage targets map to the host's shared storage root), and every
 * resolved path is confined to the container dir or the shared storage root. Operations
 * on an entry itself (delete/rename/move) use [resolveEntry], which resolves only the
 * PARENT, so a symlink entry is acted on as a link and never through its target.
 */
object GuestDocumentPaths {
    const val ROOT_HOME = "ubuntu_home"
    const val ROOT_SYSTEM = "ubuntu_root"

    const val GUEST_HOME = "/home/flux"
    const val GUEST_ROOT_HOME = "/root"

    /** Max symlinks followed during one resolution (Linux uses 40 too). */
    const val MAX_HOPS = 40

    /** Guest paths that are the device's primary shared storage. */
    val SHARED_STORAGE_PREFIXES = listOf("/storage/emulated/0", "/sdcard", "/storage/self/primary", "/mnt/sdcard")

    private val ROOT_IDS = setOf(ROOT_HOME, ROOT_SYSTEM)
    private val NOFOLLOW = arrayOf(LinkOption.NOFOLLOW_LINKS)

    data class DocId(val rootId: String, val relPath: String) {
        val isRoot: Boolean get() = relPath.isEmpty()
        override fun toString(): String = encode(rootId, relPath)
    }

    fun encode(rootId: String, relPath: String): String = "$rootId:$relPath"

    /** Parses and validates a document id; null when malformed or unsafe. */
    fun parse(docId: String): DocId? {
        val sep = docId.indexOf(':')
        if (sep <= 0) return null
        val rootId = docId.substring(0, sep)
        val rel = docId.substring(sep + 1)
        if (rootId !in ROOT_IDS || !isValidRelPath(rel)) return null
        return DocId(rootId, rel)
    }

    fun isValidRelPath(rel: String): Boolean =
        rel.isEmpty() || (!rel.startsWith("/") && rel.split('/').all(::isValidName))

    /** A single path component that cannot climb, nest or truncate. */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name

    fun childId(parent: DocId, name: String): String? {
        if (!isValidName(name)) return null
        val rel = if (parent.isRoot) name else "${parent.relPath}/$name"
        return encode(parent.rootId, rel)
    }

    fun parentOf(doc: DocId): DocId? =
        if (doc.isRoot) null else DocId(doc.rootId, doc.relPath.substringBeforeLast('/', ""))

    /** Last segment of the document's path; for a root document, the last segment of [rootBase]. */
    fun displayName(doc: DocId, rootBase: String): String =
        if (doc.isRoot) rootBase.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
        else doc.relPath.substringAfterLast('/')

    fun isChild(parent: DocId, doc: DocId): Boolean =
        parent.rootId == doc.rootId && doc.relPath != parent.relPath &&
            (parent.isRoot || doc.relPath.startsWith(parent.relPath + "/"))

    /** Guest base path of a root: home is `/home/flux`, falling back to `/root` when missing. */
    fun rootBase(rootfs: File, rootId: String): String = when (rootId) {
        ROOT_HOME -> if (resolve(rootfs, GUEST_HOME, null)?.isDirectory == true) GUEST_HOME else GUEST_ROOT_HOME
        else -> "/"
    }

    fun guestPath(rootBase: String, doc: DocId): String = when {
        doc.isRoot -> rootBase
        rootBase.endsWith("/") -> rootBase + doc.relPath
        else -> "$rootBase/${doc.relPath}"
    }

    /** `name (1).ext`, `name (2).ext`, … (directories get no extension split). */
    fun uniqueName(name: String, isDir: Boolean, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = if (isDir) -1 else name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val candidate = "$stem ($i)$ext"
            if (!exists(candidate)) return candidate
            i++
        }
    }

    /**
     * Resolves [guestPath] to a host file, following every symlink guest-style.
     * Returns null when the walk escapes the container/shared root, loops, needs
     * shared storage that is unavailable, or the path is malformed.
     */
    fun resolve(rootfs: File, guestPath: String, sharedStorageRoot: File?): File? {
        val ctx = Ctx.of(rootfs, sharedStorageRoot) ?: return null
        return ctx.walk(ctx.rootfs, ctx.rootfs, guestPath, followLast = true)
    }

    /**
     * Host path of the entry itself: the parent is resolved guest-style, the final
     * component is NOT followed. Null for a guest root ("/") or unresolvable parent.
     */
    fun resolveEntry(rootfs: File, guestPath: String, sharedStorageRoot: File?): File? {
        val ctx = Ctx.of(rootfs, sharedStorageRoot) ?: return null
        return ctx.walk(ctx.rootfs, ctx.rootfs, guestPath, followLast = false)
    }

    /**
     * Deletes the entry at [guestPath] (never a link's target; recursion via
     * [SafeFiles.deleteTree], which never traverses links). Refuses guest `/`.
     */
    fun deleteEntry(rootfs: File, guestPath: String, sharedStorageRoot: File?): Boolean {
        val ctx = Ctx.of(rootfs, sharedStorageRoot) ?: return false
        val entry = ctx.walk(ctx.rootfs, ctx.rootfs, guestPath, followLast = false) ?: return false
        if (ctx.isAnchor(entry.toPath())) return false
        return SafeFiles.deleteTree(entry)
    }

    private class Ctx(val rootfs: Path, val container: Path, val shared: Path?, val containerAliases: List<String>) {
        fun confined(p: Path): Boolean = p.startsWith(container) || (shared != null && p.startsWith(shared))

        fun isAnchor(p: Path): Boolean = p == rootfs || p == container || p == shared

        /**
         * Walks [path] (guest-absolute or relative to [start]) component by component.
         * [anchor] is the floor `..` may not climb above; reaching it and climbing is an escape.
         */
        fun walk(anchorIn: Path, start: Path, path: String, followLast: Boolean): File? {
            if ('\u0000' in path) return null
            var anchor = anchorIn
            var cur = start
            val pending = ArrayDeque(components(path))
            // An entry must end in a plain name: "/" or a trailing ".." would name an ancestor.
            if (!followLast && (pending.isEmpty() || pending.last() == "..")) return null
            var hops = 0
            while (pending.isNotEmpty()) {
                val name = pending.removeFirst()
                if (name == "..") {
                    if (cur == anchor) return null
                    cur = cur.parent ?: return null
                    continue
                }
                val next = cur.resolve(name)
                val isLast = pending.isEmpty()
                if ((isLast && !followLast) || !Files.isSymbolicLink(next)) {
                    cur = next
                    continue
                }
                if (++hops > MAX_HOPS) return null
                val target = try {
                    Files.readSymbolicLink(next).toString()
                } catch (_: IOException) {
                    return null
                } catch (_: UnsupportedOperationException) {
                    return null
                }
                if ('\u0000' in target) return null
                if (target.startsWith("/")) {
                    val jump = absoluteJump(target) ?: return null
                    anchor = jump.first
                    cur = jump.first
                    jump.second.asReversed().forEach(pending::addFirst)
                } else {
                    components(target).asReversed().forEach(pending::addFirst)
                }
            }
            val result = cur.normalize()
            return if (confined(result)) result.toFile() else null
        }

        /** Maps an absolute link target to (new anchor, remaining components), or null if unavailable. */
        private fun absoluteJump(target: String): Pair<Path, List<String>>? {
            SHARED_STORAGE_PREFIXES.firstOrNull { under(target, it) }?.let { prefix ->
                val root = shared ?: return null
                return root to components(target.substring(prefix.length))
            }
            containerAliases.firstOrNull { under(target, it) }?.let { prefix ->
                return container to components(target.substring(prefix.length))
            }
            return rootfs to components(target)
        }

        companion object {
            fun of(rootfs: File, sharedStorageRoot: File?): Ctx? {
                val realRootfs = realPath(rootfs) ?: return null
                val container = realRootfs.parent ?: return null
                val shared = sharedStorageRoot?.let(::realPath)
                // proot stores host-absolute .l2s links, possibly via /data/data aliases of the real path.
                val aliases = listOfNotNull(
                    container.toString(),
                    rootfs.absoluteFile.toPath().normalize().parent?.toString(),
                ).filter { it != "/" }.distinct()
                return Ctx(realRootfs, container, shared, aliases)
            }

            private fun realPath(f: File): Path? = try {
                f.toPath().toRealPath()
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            }

            private fun under(path: String, prefix: String): Boolean =
                path == prefix || path.startsWith("$prefix/")

            /** Path components without empty and `.` segments (`..` is kept for the walk). */
            private fun components(p: String): List<String> = p.split('/').filter { it.isNotEmpty() && it != "." }
        }
    }
}
