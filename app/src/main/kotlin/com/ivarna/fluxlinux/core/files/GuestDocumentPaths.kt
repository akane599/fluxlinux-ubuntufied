package com.ivarna.fluxlinux.core.files

import java.io.File

/**
 * Pure (no `android.*`) document-id codec for [UbuntuDocumentsProvider], plus File-based
 * PRoot wrappers over [GuestTree] (the resolver shared with the root-backed chroot tree).
 *
 * Document ids are `"<rootId>:<relPath>"`; `relPath` is "" for the root document and
 * otherwise a `/`-joined list of plain names relative to the root's guest base path. Root
 * ids are per mode ([GuestLayout]), so PRoot and chroot ids never resolve into each other.
 *
 * Guest symlinks are resolved the way the guest sees them (absolute targets are inside
 * the rootfs, shared-storage targets map to the host's shared storage root), and every
 * resolved path is confined to the container dir or the shared storage root. Operations
 * on an entry itself (delete/rename/move) use [resolveEntry], which resolves only the
 * PARENT, so a symlink entry is acted on as a link and never through its target.
 *
 * Anything reached THROUGH a link ([traversesLink]) is browse-only: it may be read,
 * opened, written and created in, but never deleted, renamed or moved, and a tree grant
 * never extends across a link. Otherwise a client-driven recursive move/delete of a
 * folder holding `~/storage/shared` would empty the device's shared storage.
 *
 * A link whose target leaves the container ([finalLinkLeavesContainer], e.g.
 * `~/storage/shared` itself, or a file link into shared storage) is never part of a tree
 * grant and is never opened, so a grant on "Ubuntu home" cannot reach shared storage.
 */
object GuestDocumentPaths {
    const val ROOT_HOME = "ubuntu_home"
    const val ROOT_SYSTEM = "ubuntu_root"

    /** Chroot-mode roots ([GuestLayout.CHROOT]): distinct ids so grants never cross between modes. */
    const val ROOT_CHROOT_HOME = "ubuntu_chroot_home"
    const val ROOT_CHROOT_SYSTEM = "ubuntu_chroot_root"

    /** Legacy PRoot guest home (installs from before the `akane` user); see [GuestLayout.homeCandidates]. */
    const val GUEST_HOME = "/home/flux"
    const val GUEST_ROOT_HOME = "/root"

    /** Max symlinks followed during one resolution (Linux uses 40 too). */
    const val MAX_HOPS = 40

    /** Guest paths that are the device's primary shared storage. */
    val SHARED_STORAGE_PREFIXES = listOf("/storage/emulated/0", "/sdcard", "/storage/self/primary", "/mnt/sdcard")

    private val ROOT_IDS = setOf(ROOT_HOME, ROOT_SYSTEM, ROOT_CHROOT_HOME, ROOT_CHROOT_SYSTEM)

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

    // ---- PRoot (direct file access) wrappers over [GuestTree] + [LocalGuestFs] ----------

    private fun proot(rootfs: File, sharedStorageRoot: File?): GuestTree =
        GuestTree(LocalGuestFs, abs(rootfs), sharedStorageRoot?.let(::abs), GuestLayout.PROOT)

    private fun abs(f: File): String = f.absoluteFile.toPath().normalize().toString()

    /**
     * [isChild] for tree grants: false when [doc] is reached through a link, so a grant on a
     * Ubuntu root never extends to a link's target (e.g. shared storage via `storage/shared`).
     */
    fun isChildWithoutLink(rootfs: File, parent: DocId, doc: DocId): Boolean =
        proot(rootfs, null).isChildWithoutLink(parent, doc)

    /** See [GuestTree.finalLinkLeavesContainer]. */
    fun finalLinkLeavesContainer(rootfs: File, guestPath: String): Boolean =
        proot(rootfs, null).finalLinkLeavesContainer(guestPath)

    /** See [GuestTree.traversesLink]. */
    fun traversesLink(rootfs: File, guestPath: String): Boolean = proot(rootfs, null).traversesLink(guestPath)

    /** Guest base path of a PRoot root: `/home/akane`, else legacy `/home/flux`, else `/root`. */
    fun rootBase(rootfs: File, rootId: String): String = proot(rootfs, null).rootBase(rootId)

    /**
     * Resolves [guestPath] to a host file, following every symlink guest-style.
     * Returns null when the walk escapes the container/shared root, loops, needs
     * shared storage that is unavailable, or the path is malformed.
     */
    fun resolve(rootfs: File, guestPath: String, sharedStorageRoot: File?): File? =
        proot(rootfs, sharedStorageRoot).resolve(guestPath)?.let(::File)

    /**
     * Host path of the entry itself: the parent is resolved guest-style, the final
     * component is NOT followed. Null for a guest root ("/"), an unresolvable parent, or
     * when the entry would be an anchor (the rootfs, its container, or the shared root).
     */
    fun resolveEntry(rootfs: File, guestPath: String, sharedStorageRoot: File?): File? =
        proot(rootfs, sharedStorageRoot).resolveEntry(guestPath)?.let(::File)

    /**
     * Deletes the entry at [guestPath] (never a link's target; recursion via
     * [com.ivarna.fluxlinux.core.system.SafeFiles.deleteTree], which never traverses links).
     * Refuses guest `/`, anchors, and anything reached through a link ([traversesLink]).
     */
    fun deleteEntry(rootfs: File, guestPath: String, sharedStorageRoot: File?): Boolean =
        proot(rootfs, sharedStorageRoot).deleteEntry(guestPath)
}
