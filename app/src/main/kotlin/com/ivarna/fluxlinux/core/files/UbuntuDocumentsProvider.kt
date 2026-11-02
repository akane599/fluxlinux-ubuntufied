package com.ivarna.fluxlinux.core.files

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.files.GuestDocumentPaths.DocId
import com.ivarna.fluxlinux.core.files.HostPaths.child
import com.ivarna.fluxlinux.core.root.ChrootPaths
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Exposes the active Ubuntu rootfs to DocumentsUI / SAF file managers as two roots: the guest
 * home and the whole guest filesystem (`/`). PRoot mode serves the app-private rootfs
 * (`/home/flux`) directly; chroot mode serves [ChrootPaths.UBUNTU_CHROOT_PATH] (`/home/akane`)
 * through root ([RootGuestFs]). Root and document ids are per mode ([GuestLayout]); ids of the
 * inactive mode are refused.
 *
 * All path work goes through [GuestTree], shared by both backends: reads/lists/opens follow
 * guest symlinks guest-style (confined to the container dir or shared storage), while
 * delete/rename/move act on the entry itself, never on a link's target. Documents reached
 * through a link are browse-only, and tree grants never cross a link. Chroot only: entries
 * on filesystems mounted inside the rootfs (/proc, /sys, /dev, the /sdcard bind) are list-only,
 * while guest links into `/sdcard` / `/storage/emulated/0` reach host shared storage directly
 * ([SharedStorageRoutingFs]).
 */
class UbuntuDocumentsProvider : DocumentsProvider() {

    private val ctx: Context get() = requireNotNull(context)
    private val prootRootfs: File get() = File(ctx.filesDir, "usr/var/lib/proot-distro/containers/ubuntu/rootfs")

    /** Host shared storage, only when this app can actually read it. */
    private val sharedRoot: File?
        get() = Environment.getExternalStorageDirectory()?.takeIf { it.canRead() }

    override fun onCreate(): Boolean = true

    /** A fresh view of one layout's tree; the chroot backend caches root results for this call only. */
    private fun tree(layout: GuestLayout): GuestTree = when (layout) {
        GuestLayout.PROOT -> GuestTree(
            LocalGuestFs,
            prootRootfs.absoluteFile.toPath().normalize().toString(),
            sharedRoot?.absolutePath,
            GuestLayout.PROOT,
        )
        GuestLayout.CHROOT -> {
            val rootfs = ChrootPaths.UBUNTU_CHROOT_PATH
            val scripts = RootScripts(rootfs, busybox = ChrootFilesBusyBox.path(ctx))
            val root = RootGuestFs(ShellRootOps(SuRootRunner, rootfs, scripts), rootfs)
            // Guest links into /sdcard or /storage/emulated/0 reach host shared storage directly
            // (All files access, as in PRoot mode); without access they stay unresolvable.
            val shared = sharedRoot?.absolutePath
            val fs = if (shared == null) root else SharedStorageRoutingFs(root, LocalGuestFs, shared)
            GuestTree(fs, rootfs, shared, GuestLayout.CHROOT)
        }
    }

    /** Tree for [doc]; refuses ids of the inactive mode so stale grants never cross trees. */
    private fun tree(doc: DocId): GuestTree {
        val layout = UbuntuFiles.activeLayout(ctx)
        if (!layout.owns(doc.rootId)) throw FileNotFoundException("Ubuntu ${doc.rootId} is not the active install")
        return tree(layout)
    }

    // ---- roots -------------------------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        cursor.setNotificationUri(ctx.contentResolver, DocumentsContract.buildRootsUri(UbuntuFiles.authority(ctx)))
        val layout = UbuntuFiles.activeLayout(ctx)
        if (!UbuntuFiles.isAvailable(ctx)) return cursor
        val flags = Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY
        // PRoot: cheap local lookups. Chroot: no su call per roots query (DocumentsUI polls this).
        val proot = if (layout == GuestLayout.PROOT) tree(layout) else null
        for ((rootId, title) in listOf(
            layout.homeRootId to R.string.ubuntu_files_root_home,
            layout.systemRootId to R.string.ubuntu_files_root_system,
        )) {
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, rootId)
                add(Root.COLUMN_DOCUMENT_ID, GuestDocumentPaths.encode(rootId, ""))
                add(Root.COLUMN_TITLE, ctx.getString(title))
                add(
                    Root.COLUMN_SUMMARY,
                    proot?.rootBase(rootId) ?: if (rootId == layout.homeRootId) layout.guestHome else "/",
                )
                add(Root.COLUMN_FLAGS, flags)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_MIME_TYPES, "*/*")
                proot?.let { add(Root.COLUMN_AVAILABLE_BYTES, it.fs.usableSpace(it.rootfs)) }
            }
        }
        return cursor
    }

    // ---- queries -----------------------------------------------------------------------

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val doc = parse(documentId)
        val t = tree(doc)
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addRow(t, cursor, doc)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = parse(parentDocumentId)
        val t = tree(parent)
        val dir = resolve(t, parent)
        if (!isDir(t, dir)) throw FileNotFoundException("Not a directory: $parentDocumentId")
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val names = try {
            t.fs.list(dir)
        } catch (e: IOException) {
            throw FileNotFoundException("Cannot list $parentDocumentId: ${e.message}")
        }
        val parentWritable = t.fs.canWrite(dir)
        for (name in names) {
            val childId = GuestDocumentPaths.childId(parent, name) ?: continue
            GuestDocumentPaths.parse(childId)?.let { runCatching { addRow(t, cursor, it, parentWritable) } }
        }
        cursor.setNotificationUri(ctx.contentResolver, childrenUri(parentDocumentId))
        return cursor
    }

    override fun getDocumentType(documentId: String): String {
        val doc = parse(documentId)
        val t = tree(doc)
        val file = t.resolve(t.guestPath(doc))
        return if (file != null && isDir(t, file)) Document.MIME_TYPE_DIR else mimeOf(displayName(t, doc))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = GuestDocumentPaths.parse(parentDocumentId) ?: return false
        val doc = GuestDocumentPaths.parse(documentId) ?: return false
        // A tree grant never crosses a link (e.g. "Ubuntu home" must not reach shared storage).
        return try {
            GuestDocumentPaths.isChild(parent, doc) && tree(doc).isChildWithoutLink(parent, doc)
        } catch (_: IOException) {
            false
        }
    }

    // ---- content -----------------------------------------------------------------------

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val doc = parse(documentId)
        val t = tree(doc)
        // A file link into shared storage (or out of the container) is never opened through
        // this provider: it would read/write outside the Ubuntu files with this app's access.
        if (t.finalLinkLeavesContainer(t.guestPath(doc))) {
            throw FileNotFoundException("Link outside the Ubuntu container: $documentId")
        }
        val file = resolve(t, doc)
        val st = t.fs.lstat(file) ?: throw FileNotFoundException("Missing: $documentId")
        // FIFOs, sockets and devices could block a binder thread forever.
        if (st.type != GuestFs.Type.FILE) throw FileNotFoundException("Not a regular file: $documentId")
        if (t.fs.isForeignMount(file)) throw FileNotFoundException("On a mounted filesystem: $documentId")
        return t.fs.open(file, mode)
    }

    // ---- mutations ---------------------------------------------------------------------

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = parse(parentDocumentId)
        val t = tree(parent)
        val dir = resolve(t, parent)
        if (!isDir(t, dir)) throw FileNotFoundException("Not a directory: $parentDocumentId")
        val isDir = mimeType == Document.MIME_TYPE_DIR
        val name = GuestDocumentPaths.uniqueName(withExtension(sanitize(displayName), mimeType, isDir), isDir) {
            t.fs.lstat(child(dir, it)) != null
        }
        val target = child(dir, name)
        val created = try {
            if (isDir) t.fs.mkdir(target) else t.fs.createFile(target)
        } catch (e: IOException) {
            throw FileNotFoundException("Cannot create $name: ${e.message}")
        }
        if (!created) throw FileNotFoundException("Cannot create $name")
        notifyChildren(parent)
        return GuestDocumentPaths.childId(parent, name) ?: throw FileNotFoundException(name)
    }

    override fun deleteDocument(documentId: String) {
        val doc = parse(documentId)
        if (doc.isRoot) throw UnsupportedOperationException("Cannot delete a root")
        val t = tree(doc)
        requireNotThroughLink(t, doc, "delete")
        if (!t.deleteEntry(t.guestPath(doc))) {
            throw FileNotFoundException("Cannot delete $documentId")
        }
        GuestDocumentPaths.parentOf(doc)?.let(::notifyChildren)
    }

    override fun removeDocument(documentId: String, parentDocumentId: String) {
        deleteDocument(documentId)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val doc = parse(documentId)
        if (doc.isRoot) throw UnsupportedOperationException("Cannot rename a root")
        val t = tree(doc)
        requireNotThroughLink(t, doc, "rename")
        val name = sanitize(displayName)
        val entry = entry(t, doc)
        val parent = GuestDocumentPaths.parentOf(doc) ?: throw FileNotFoundException(documentId)
        val dir = HostPaths.parent(entry) ?: throw FileNotFoundException(documentId)
        moveEntry(t, entry, child(dir, name))
        notifyChildren(parent)
        return GuestDocumentPaths.childId(parent, name) ?: throw FileNotFoundException(name)
    }

    override fun moveDocument(
        sourceDocumentId: String,
        sourceParentDocumentId: String,
        targetParentDocumentId: String,
    ): String {
        val src = parse(sourceDocumentId)
        val target = parse(targetParentDocumentId)
        if (src.isRoot) throw UnsupportedOperationException("Cannot move a root")
        if (src.rootId != target.rootId) throw UnsupportedOperationException("Move only within one root")
        val t = tree(src)
        requireNotThroughLink(t, src, "move")
        val entry = entry(t, src)
        val dir = targetDirOutside(t, src, target)
        val name = uniqueIn(t, dir, entry)
        moveEntry(t, entry, child(dir, name))
        GuestDocumentPaths.parentOf(src)?.let(::notifyChildren)
        notifyChildren(target)
        return GuestDocumentPaths.childId(target, name) ?: throw FileNotFoundException(name)
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        val src = parse(sourceDocumentId)
        val target = parse(targetParentDocumentId)
        if (src.isRoot) throw UnsupportedOperationException("Cannot copy a root")
        val t = tree(src)
        if (!t.layout.owns(target.rootId)) throw FileNotFoundException("Ubuntu ${target.rootId} is not the active install")
        val entry = entry(t, src)
        val dir = targetDirOutside(t, src, target)
        val name = uniqueIn(t, dir, entry)
        if (!t.fs.copyTree(entry, child(dir, name))) {
            throw FileNotFoundException("Cannot copy $sourceDocumentId")
        }
        notifyChildren(target)
        return GuestDocumentPaths.childId(target, name) ?: throw FileNotFoundException(name)
    }

    // ---- helpers -----------------------------------------------------------------------

    private fun parse(documentId: String): DocId =
        GuestDocumentPaths.parse(documentId) ?: throw FileNotFoundException("Invalid document id: $documentId")

    private fun isDir(t: GuestTree, path: String): Boolean = t.fs.lstat(path)?.type == GuestFs.Type.DIR

    /** Fully resolved (links followed guest-style) host path for reading/listing/opening. */
    private fun resolve(t: GuestTree, doc: DocId): String =
        t.resolve(t.guestPath(doc)) ?: throw FileNotFoundException("Unresolvable: $doc")

    /**
     * Refuses a destructive [op] on a document reached through a link: its data belongs to
     * the link's target (e.g. shared storage via `~/storage/shared`), so it is browse-only.
     */
    private fun requireNotThroughLink(t: GuestTree, doc: DocId, op: String) {
        val path = t.guestPath(doc)
        if (t.traversesLink(path)) {
            throw UnsupportedOperationException("Cannot $op $path: reached through a link (browse-only)")
        }
    }

    /** Host path of the entry itself (parent resolved, final component never followed; never an anchor). */
    private fun entry(t: GuestTree, doc: DocId): String {
        val path = t.resolveEntry(t.guestPath(doc))
        if (path == null || t.fs.lstat(path) == null) throw FileNotFoundException("Missing: $doc")
        return path
    }

    /** Resolved target directory, refused when it is [src] itself or lies inside it (by host path). */
    private fun targetDirOutside(t: GuestTree, src: DocId, target: DocId): String {
        val dir = resolve(t, target)
        if (!isDir(t, dir)) throw FileNotFoundException("Not a directory: $target")
        val srcReal = t.resolve(t.guestPath(src))
        if (srcReal != null && HostPaths.isUnder(dir, srcReal)) {
            throw UnsupportedOperationException("Cannot place $src inside itself")
        }
        return dir
    }

    private fun uniqueIn(t: GuestTree, dir: String, entry: String): String {
        val isDir = t.fs.lstat(entry)?.type == GuestFs.Type.DIR
        return GuestDocumentPaths.uniqueName(HostPaths.name(entry), isDir) { t.fs.lstat(child(dir, it)) != null }
    }

    /** Renames/moves the entry itself (a link moves as a link). */
    private fun moveEntry(t: GuestTree, from: String, to: String) {
        if (t.fs.lstat(to) != null) throw IllegalStateException("${HostPaths.name(to)} already exists")
        t.fs.move(from, to)
    }

    private fun displayName(t: GuestTree, doc: DocId): String =
        GuestDocumentPaths.displayName(doc, t.rootBase(doc.rootId))

    private fun addRow(t: GuestTree, cursor: MatrixCursor, doc: DocId, parentWritable: Boolean? = null) {
        val path = t.guestPath(doc)
        val entryPath = (if (doc.isRoot) t.resolve(path) else t.resolveEntry(path))
            ?: throw FileNotFoundException("Unresolvable: $doc")
        val st = t.fs.lstat(entryPath) ?: throw FileNotFoundException("Missing: $doc")
        val isLink = st.type == GuestFs.Type.LINK
        // Fully resolved target; null for a dangling or unresolvable link.
        val targetPath = if (isLink) t.resolve(path) else entryPath
        val targetStat = targetPath?.let { if (it == entryPath) st else t.fs.lstat(it) }
        val target = targetPath?.takeIf { targetStat != null }
        val isDir = targetStat?.type == GuestFs.Type.DIR
        val writable = target != null && t.fs.canWrite(target)
        val name = displayName(t, doc)
        val writableParent = !doc.isRoot &&
            (parentWritable ?: HostPaths.parent(entryPath)?.let(t.fs::canWrite) == true)
        // Reached through a link: browse-only (no delete/rename/move), so a client-driven
        // recursive move or delete can never empty a link's target such as shared storage.
        // Chroot: an entry on a mounted filesystem (/proc, /sdcard bind, …) is list-only.
        val removable = writableParent && !t.traversesLink(path) && !t.fs.isForeignMount(entryPath)

        var flags = 0
        if (removable) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        if (target != null && !doc.isRoot) {
            flags = flags or Document.FLAG_SUPPORTS_COPY
            if (removable) flags = flags or Document.FLAG_SUPPORTS_MOVE
            val escapingLink = isLink && t.finalLinkLeavesContainer(path)
            if (writableParent && !isDir && writable && !escapingLink) flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (isDir && writable) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE

        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, doc.toString())
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mimeOf(name))
            add(Document.COLUMN_SIZE, if (targetStat == null || isDir) 0L else targetStat.size)
            add(Document.COLUMN_LAST_MODIFIED, st.mtimeMillis)
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun notifyChildren(parent: DocId) {
        ctx.contentResolver.notifyChange(childrenUri(parent.toString()), null)
    }

    private fun childrenUri(parentId: String): Uri =
        DocumentsContract.buildChildDocumentsUri(UbuntuFiles.authority(ctx), parentId)

    private fun sanitize(displayName: String): String {
        val name = displayName.replace('/', '_').replace('\u0000', '_').trim()
        if (!GuestDocumentPaths.isValidName(name)) throw IllegalArgumentException("Invalid name: $displayName")
        return name
    }

    private fun withExtension(name: String, mimeType: String, isDir: Boolean): String {
        if (isDir || mimeType == "application/octet-stream") return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: return name
        if (MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) == mimeType) {
            return name
        }
        return "$name.$ext"
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private companion object {
        val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES,
        )
        val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_MIME_TYPE, Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SIZE,
        )
    }
}
