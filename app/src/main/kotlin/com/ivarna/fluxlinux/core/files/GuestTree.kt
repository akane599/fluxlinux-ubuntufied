package com.ivarna.fluxlinux.core.files

import com.ivarna.fluxlinux.core.files.HostPaths.child
import com.ivarna.fluxlinux.core.files.HostPaths.components
import com.ivarna.fluxlinux.core.files.HostPaths.isUnder
import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
import com.ivarna.fluxlinux.core.utils.UbuntuRuntimeMode

/**
 * Which Ubuntu install a SAF tree serves. Each layout owns its own root ids, so document ids
 * (`"<rootId>:<relPath>"`) are namespaced per mode: a grant from one mode never resolves into
 * the other mode's tree.
 */
enum class GuestLayout(
    val homeRootId: String,
    val systemRootId: String,
    /** Guest home dirs in order of preference; the "home" root falls back to `/root` when none exists. */
    val homeCandidates: List<String>,
    /** PRoot: the container dir (rootfs parent) is in reach of `.l2s` links; chroot: only the rootfs. */
    internal val containerIsParent: Boolean,
) {
    /** PRoot installs created before the `akane` user keep `/home/flux` until migrated. */
    PROOT(
        GuestDocumentPaths.ROOT_HOME,
        GuestDocumentPaths.ROOT_SYSTEM,
        listOf(UbuntuRuntime.UBUNTU_GUEST_HOME, GuestDocumentPaths.GUEST_HOME),
        true,
    ),
    CHROOT(
        GuestDocumentPaths.ROOT_CHROOT_HOME,
        GuestDocumentPaths.ROOT_CHROOT_SYSTEM,
        listOf(UbuntuRuntime.UBUNTU_GUEST_HOME),
        false,
    ),
    ;

    /** Preferred guest home (shown before the rootfs is inspected). */
    val guestHome: String get() = homeCandidates.first()

    fun owns(rootId: String): Boolean = rootId == homeRootId || rootId == systemRootId

    /** This layout's root id for a home/system root id of any layout (UI passes the PRoot ids). */
    fun mapRootId(rootId: String): String =
        if (forRootId(rootId)?.homeRootId == rootId) homeRootId else systemRootId

    companion object {
        fun forRootId(rootId: String): GuestLayout? = entries.firstOrNull { it.owns(rootId) }

        fun of(mode: UbuntuRuntimeMode): GuestLayout = when (mode) {
            UbuntuRuntimeMode.PROOT -> PROOT
            UbuntuRuntimeMode.CHROOT -> CHROOT
        }
    }
}

/**
 * Guest-aware path resolver for one rootfs over a [GuestFs] backend.
 *
 * Guest symlinks are resolved the way the guest sees them (absolute targets are inside the
 * rootfs, shared-storage targets map to the host's shared storage root), and every resolved
 * path is confined to the container (PRoot: rootfs parent; chroot: the rootfs itself) or the
 * shared storage root. Resolution walks one component at a time and substitutes each link
 * before going on, so the backend is only ever asked about paths with a link-free parent.
 *
 * Operations on an entry itself (delete/rename/move) use [resolveEntry], which resolves only
 * the PARENT, so a symlink entry is acted on as a link and never through its target.
 * Anything reached THROUGH a link ([traversesLink]) is browse-only, and a link whose target
 * leaves the container ([finalLinkLeavesContainer]) is never part of a tree grant and never
 * opened. See [GuestDocumentPaths] for the full contract.
 */
class GuestTree(
    val fs: GuestFs,
    /** Absolute, normalized rootfs path as configured (lexical; used by [traversesLink]). */
    val rootfs: String,
    private val sharedStorageRoot: String?,
    val layout: GuestLayout,
) {
    private val ctx: Ctx? by lazy { Ctx.of(fs, rootfs, sharedStorageRoot, layout) }
    private val ctxNoShared: Ctx? by lazy { if (sharedStorageRoot == null) ctx else Ctx.of(fs, rootfs, null, layout) }

    /** Fully resolved host path (every link followed guest-style), or null when it escapes/loops/is malformed. */
    fun resolve(guestPath: String): String? = ctx?.walk(guestPath, followLast = true)

    /** Host path of the entry itself (parent resolved, final component not followed, never an anchor). */
    fun resolveEntry(guestPath: String): String? = ctx?.walk(guestPath, followLast = false)

    /**
     * True when the final component of [guestPath] is a symlink whose guest-style target is
     * outside the container (shared storage, an escape) or cannot be resolved. Resolution runs
     * without a shared storage root, so any link into shared storage counts.
     */
    fun finalLinkLeavesContainer(guestPath: String): Boolean {
        val c = ctxNoShared ?: return false
        val entry = c.walk(guestPath, followLast = false) ?: return false
        if (fs.lstat(entry)?.type != GuestFs.Type.LINK) return false
        return c.walk(guestPath, followLast = true) == null
    }

    /**
     * True when any NON-final component of [guestPath] is a symlink, walking lexically from the
     * rootfs without following anything. Fails closed (true) on `..` or NUL components.
     */
    fun traversesLink(guestPath: String): Boolean {
        val names = guestPath.split('/').filter { it.isNotEmpty() && it != "." }
        if (names.any { it == ".." || '\u0000' in it }) return true
        val parents = names.dropLast(1)
        fs.hint(rootfs, parents)
        var cur = rootfs
        for (name in parents) {
            cur = child(cur, name)
            if (fs.lstat(cur)?.type == GuestFs.Type.LINK) return true
        }
        return false
    }

    /** [GuestDocumentPaths.isChild] for tree grants: false when [doc] is reached through, or is, an escaping link. */
    fun isChildWithoutLink(parent: GuestDocumentPaths.DocId, doc: GuestDocumentPaths.DocId): Boolean {
        if (!GuestDocumentPaths.isChild(parent, doc)) return false
        val path = guestPath(doc)
        return !traversesLink(path) && !finalLinkLeavesContainer(path)
    }

    /** Guest base path of a root: the first existing layout home, falling back to `/root`; `/` otherwise. */
    fun rootBase(rootId: String): String {
        if (rootId != layout.homeRootId) return "/"
        return layout.homeCandidates.firstOrNull { candidate ->
            val home = ctxNoShared?.walk(candidate, followLast = true)
            home != null && fs.lstat(home)?.type == GuestFs.Type.DIR
        } ?: GuestDocumentPaths.GUEST_ROOT_HOME
    }

    fun guestPath(doc: GuestDocumentPaths.DocId): String {
        if (doc.rootId == layout.homeRootId) {
            // One batched lookup for the home dir and the document below it.
            fs.hint(rootfs, components(layout.guestHome) + components(doc.relPath))
        }
        return GuestDocumentPaths.guestPath(rootBase(doc.rootId), doc)
    }

    /**
     * Deletes the entry at [guestPath] (never a link's target; recursion never traverses links).
     * Refuses guest `/`, anchors, and anything reached through a link ([traversesLink]).
     */
    fun deleteEntry(guestPath: String): Boolean {
        if (traversesLink(guestPath)) return false
        val entry = resolveEntry(guestPath) ?: return false
        return fs.deleteTree(entry)
    }

    private class Ctx(
        val fs: GuestFs,
        val rootfs: String,
        val container: String,
        val shared: String?,
        val containerAliases: List<String>,
    ) {
        fun confined(p: String): Boolean = isUnder(p, container) || (shared != null && isUnder(p, shared))

        fun isAnchor(p: String): Boolean = p == rootfs || p == container || p == shared

        fun walk(path: String, followLast: Boolean): String? = walk(rootfs, rootfs, path, followLast)

        /**
         * Walks [path] (guest-absolute or relative to [start]) component by component.
         * [anchorIn] is the floor `..` may not climb above; reaching it and climbing is an escape.
         */
        private fun walk(anchorIn: String, start: String, path: String, followLast: Boolean): String? {
            if ('\u0000' in path) return null
            var anchor = anchorIn
            var cur = start
            val pending = ArrayDeque(components(path))
            // An entry must end in a plain name: "/" or a trailing ".." would name an ancestor.
            if (!followLast && (pending.isEmpty() || pending.last() == "..")) return null
            var hops = 0
            hint(cur, pending)
            while (pending.isNotEmpty()) {
                val name = pending.removeFirst()
                if (name == "..") {
                    if (cur == anchor) return null
                    cur = HostPaths.parent(cur) ?: return null
                    hint(cur, pending)
                    continue
                }
                val next = child(cur, name)
                val isLast = pending.isEmpty()
                if ((isLast && !followLast) || fs.lstat(next)?.type != GuestFs.Type.LINK) {
                    cur = next
                    continue
                }
                if (++hops > GuestDocumentPaths.MAX_HOPS) return null
                val target = fs.readLink(next) ?: return null
                if (target.isEmpty() || '\u0000' in target) return null
                if (target.startsWith("/")) {
                    val jump = absoluteJump(target) ?: return null
                    anchor = jump.first
                    cur = jump.first
                    jump.second.asReversed().forEach(pending::addFirst)
                } else {
                    components(target).asReversed().forEach(pending::addFirst)
                }
                hint(cur, pending)
            }
            if (!confined(cur)) return null
            // An entry is acted on itself (rename/move/delete): never the rootfs, container or shared root.
            if (!followLast && isAnchor(cur)) return null
            return cur
        }

        private fun hint(cur: String, pending: Collection<String>) {
            val run = pending.takeWhile { it != ".." }
            if (run.isNotEmpty()) fs.hint(cur, run)
        }

        /** Maps an absolute link target to (new anchor, remaining components), or null if unavailable. */
        private fun absoluteJump(target: String): Pair<String, List<String>>? {
            GuestDocumentPaths.SHARED_STORAGE_PREFIXES.firstOrNull { under(target, it) }?.let { prefix ->
                val root = shared ?: return null
                return root to components(target.substring(prefix.length))
            }
            containerAliases.firstOrNull { under(target, it) }?.let { prefix ->
                return container to components(target.substring(prefix.length))
            }
            return rootfs to components(target)
        }

        companion object {
            fun of(fs: GuestFs, rootfs: String, sharedStorageRoot: String?, layout: GuestLayout): Ctx? {
                val realRootfs = fs.realPath(rootfs) ?: return null
                val container = if (layout.containerIsParent) HostPaths.parent(realRootfs) ?: return null else realRootfs
                val shared = sharedStorageRoot?.let(fs::realPath)
                // proot stores host-absolute .l2s links, possibly via /data/data aliases of the real path.
                val aliases = if (!layout.containerIsParent) emptyList() else listOfNotNull(
                    container,
                    HostPaths.parent(rootfs),
                ).filter { it != "/" }.distinct()
                return Ctx(fs, realRootfs, container, shared, aliases)
            }

            private fun under(path: String, prefix: String): Boolean =
                path == prefix || path.startsWith("$prefix/")
        }
    }
}
