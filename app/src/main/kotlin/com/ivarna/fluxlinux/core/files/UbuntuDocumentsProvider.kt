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
import com.ivarna.fluxlinux.core.system.SafeFiles
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Exposes the Ubuntu proot rootfs to DocumentsUI / SAF file managers as two roots:
 * the guest home (`/home/flux`, or `/root`) and the whole guest filesystem (`/`).
 *
 * All path work goes through [GuestDocumentPaths]: reads/lists/opens follow guest
 * symlinks guest-style (confined to the container dir or shared storage), while
 * delete/rename/move act on the entry itself, never on a link's target. Documents reached
 * through a link are browse-only, and tree grants never cross a link.
 */
class UbuntuDocumentsProvider : DocumentsProvider() {

    private val ctx: Context get() = requireNotNull(context)
    private val rootfs: File get() = File(ctx.filesDir, "usr/var/lib/proot-distro/containers/ubuntu/rootfs")

    /** Host shared storage, only when this app can actually read it. */
    private val sharedRoot: File?
        get() = Environment.getExternalStorageDirectory()?.takeIf { it.canRead() }

    override fun onCreate(): Boolean = true

    // ---- roots -------------------------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        cursor.setNotificationUri(ctx.contentResolver, DocumentsContract.buildRootsUri(UbuntuFiles.authority(ctx)))
        if (!UbuntuFiles.isAvailable(ctx)) return cursor
        val fs = rootfs
        val flags = Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY
        for ((rootId, title) in listOf(
            UbuntuFiles.ROOT_HOME to R.string.ubuntu_files_root_home,
            UbuntuFiles.ROOT_SYSTEM to R.string.ubuntu_files_root_system,
        )) {
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, rootId)
                add(Root.COLUMN_DOCUMENT_ID, GuestDocumentPaths.encode(rootId, ""))
                add(Root.COLUMN_TITLE, ctx.getString(title))
                add(Root.COLUMN_SUMMARY, GuestDocumentPaths.rootBase(fs, rootId))
                add(Root.COLUMN_FLAGS, flags)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_MIME_TYPES, "*/*")
                add(Root.COLUMN_AVAILABLE_BYTES, fs.usableSpace)
            }
        }
        return cursor
    }

    // ---- queries -----------------------------------------------------------------------

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val doc = parse(documentId)
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        addRow(cursor, doc)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = parse(parentDocumentId)
        val dir = resolve(parent)
        if (!dir.isDirectory) throw FileNotFoundException("Not a directory: $parentDocumentId")
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val names = try {
            Files.newDirectoryStream(dir.toPath()).use { stream -> stream.map { it.fileName.toString() } }
        } catch (e: IOException) {
            throw FileNotFoundException("Cannot list $parentDocumentId: ${e.message}")
        }
        val parentWritable = dir.canWrite()
        for (name in names) {
            val childId = GuestDocumentPaths.childId(parent, name) ?: continue
            GuestDocumentPaths.parse(childId)?.let { runCatching { addRow(cursor, it, parentWritable) } }
        }
        cursor.setNotificationUri(ctx.contentResolver, childrenUri(parentDocumentId))
        return cursor
    }

    override fun getDocumentType(documentId: String): String {
        val doc = parse(documentId)
        val file = GuestDocumentPaths.resolve(rootfs, guestPath(doc), sharedRoot)
        return if (file?.isDirectory == true) Document.MIME_TYPE_DIR else mimeOf(displayName(doc))
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val parent = GuestDocumentPaths.parse(parentDocumentId) ?: return false
        val doc = GuestDocumentPaths.parse(documentId) ?: return false
        // A tree grant never crosses a link (e.g. "Ubuntu home" must not reach shared storage).
        return GuestDocumentPaths.isChildWithoutLink(rootfs, parent, doc)
    }

    // ---- content -----------------------------------------------------------------------

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = resolve(parse(documentId))
        val attrs = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            throw FileNotFoundException("Missing: $documentId")
        }
        // FIFOs, sockets and devices could block a binder thread forever.
        if (!attrs.isRegularFile) throw FileNotFoundException("Not a regular file: $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    // ---- mutations ---------------------------------------------------------------------

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = parse(parentDocumentId)
        val dir = resolve(parent)
        if (!dir.isDirectory) throw FileNotFoundException("Not a directory: $parentDocumentId")
        val isDir = mimeType == Document.MIME_TYPE_DIR
        val name = GuestDocumentPaths.uniqueName(withExtension(sanitize(displayName), mimeType, isDir), isDir) {
            Files.exists(File(dir, it).toPath(), LinkOption.NOFOLLOW_LINKS)
        }
        val target = File(dir, name)
        val created = try {
            if (isDir) target.mkdir() else target.createNewFile()
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
        requireNotThroughLink(doc, "delete")
        if (!GuestDocumentPaths.deleteEntry(rootfs, guestPath(doc), sharedRoot)) {
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
        requireNotThroughLink(doc, "rename")
        val name = sanitize(displayName)
        val entry = entry(doc)
        val parent = GuestDocumentPaths.parentOf(doc) ?: throw FileNotFoundException(documentId)
        moveEntry(entry, File(entry.parentFile, name))
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
        requireNotThroughLink(src, "move")
        val entry = entry(src)
        val dir = targetDirOutside(src, target)
        val name = uniqueIn(dir, entry)
        moveEntry(entry, File(dir, name))
        GuestDocumentPaths.parentOf(src)?.let(::notifyChildren)
        notifyChildren(target)
        return GuestDocumentPaths.childId(target, name) ?: throw FileNotFoundException(name)
    }

    override fun copyDocument(sourceDocumentId: String, targetParentDocumentId: String): String {
        val src = parse(sourceDocumentId)
        val target = parse(targetParentDocumentId)
        if (src.isRoot) throw UnsupportedOperationException("Cannot copy a root")
        val entry = entry(src)
        val dir = targetDirOutside(src, target)
        val name = uniqueIn(dir, entry)
        if (!SafeFiles.copyTree(entry, File(dir, name), overwrite = false)) {
            throw FileNotFoundException("Cannot copy $sourceDocumentId")
        }
        notifyChildren(target)
        return GuestDocumentPaths.childId(target, name) ?: throw FileNotFoundException(name)
    }

    // ---- helpers -----------------------------------------------------------------------

    private fun parse(documentId: String): GuestDocumentPaths.DocId =
        GuestDocumentPaths.parse(documentId) ?: throw FileNotFoundException("Invalid document id: $documentId")

    private fun guestPath(doc: GuestDocumentPaths.DocId): String =
        GuestDocumentPaths.guestPath(GuestDocumentPaths.rootBase(rootfs, doc.rootId), doc)

    /** Fully resolved (links followed guest-style) host file for reading/listing/opening. */
    private fun resolve(doc: GuestDocumentPaths.DocId): File =
        GuestDocumentPaths.resolve(rootfs, guestPath(doc), sharedRoot)
            ?: throw FileNotFoundException("Unresolvable: $doc")

    /**
     * Refuses a destructive [op] on a document reached through a link: its data belongs to
     * the link's target (e.g. shared storage via `~/storage/shared`), so it is browse-only.
     */
    private fun requireNotThroughLink(doc: GuestDocumentPaths.DocId, op: String) {
        val path = guestPath(doc)
        if (GuestDocumentPaths.traversesLink(rootfs, path)) {
            throw UnsupportedOperationException("Cannot $op $path: reached through a link (browse-only)")
        }
    }

    /** Host file of the entry itself (parent resolved, final component never followed; never an anchor). */
    private fun entry(doc: GuestDocumentPaths.DocId): File {
        val file = GuestDocumentPaths.resolveEntry(rootfs, guestPath(doc), sharedRoot)
        if (file == null || !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw FileNotFoundException("Missing: $doc")
        }
        return file
    }

    /** Resolved target directory, refused when it is [src] itself or lies inside it (by host path). */
    private fun targetDirOutside(src: GuestDocumentPaths.DocId, target: GuestDocumentPaths.DocId): File {
        val dir = resolve(target)
        if (!dir.isDirectory) throw FileNotFoundException("Not a directory: $target")
        val srcReal = GuestDocumentPaths.resolve(rootfs, guestPath(src), sharedRoot)
        if (srcReal != null && dir.toPath().normalize().startsWith(srcReal.toPath().normalize())) {
            throw UnsupportedOperationException("Cannot place $src inside itself")
        }
        return dir
    }

    private fun uniqueIn(dir: File, entry: File): String {
        val isDir = Files.isDirectory(entry.toPath(), LinkOption.NOFOLLOW_LINKS)
        return GuestDocumentPaths.uniqueName(entry.name, isDir) {
            Files.exists(File(dir, it).toPath(), LinkOption.NOFOLLOW_LINKS)
        }
    }

    /** Renames/moves the entry itself (a link moves as a link); falls back to safe copy+delete across filesystems. */
    private fun moveEntry(from: File, to: File) {
        if (Files.exists(to.toPath(), LinkOption.NOFOLLOW_LINKS)) throw IllegalStateException("${to.name} already exists")
        try {
            Files.move(from.toPath(), to.toPath(), LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            val sameFs = runCatching { Files.getFileStore(from.parentFile.toPath()) == Files.getFileStore(to.parentFile.toPath()) }
                .getOrDefault(true)
            if (sameFs || !SafeFiles.copyTree(from, to, overwrite = false) || !SafeFiles.deleteTree(from)) {
                throw FileNotFoundException("Cannot move ${from.name}: ${e.message}")
            }
        }
    }

    private fun displayName(doc: GuestDocumentPaths.DocId): String =
        GuestDocumentPaths.displayName(doc, GuestDocumentPaths.rootBase(rootfs, doc.rootId))

    private fun addRow(cursor: MatrixCursor, doc: GuestDocumentPaths.DocId, parentWritable: Boolean? = null) {
        val path = guestPath(doc)
        val shared = sharedRoot
        val entryFile = if (doc.isRoot) GuestDocumentPaths.resolve(rootfs, path, shared)
        else GuestDocumentPaths.resolveEntry(rootfs, path, shared)
        entryFile ?: throw FileNotFoundException("Unresolvable: $doc")
        val attrs = try {
            Files.readAttributes(entryFile.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            throw FileNotFoundException("Missing: $doc")
        }
        // Fully resolved target; null for a dangling or unresolvable link.
        val target = (if (attrs.isSymbolicLink) GuestDocumentPaths.resolve(rootfs, path, shared) else entryFile)
            ?.takeIf { it.exists() }
        val isDir = target?.isDirectory == true
        val writable = target?.canWrite() == true
        val name = displayName(doc)
        val writableParent = !doc.isRoot && (parentWritable ?: entryFile.parentFile?.canWrite() == true)
        // Reached through a link: browse-only (no delete/rename/move), so a client-driven
        // recursive move or delete can never empty a link's target such as shared storage.
        val removable = writableParent && !GuestDocumentPaths.traversesLink(rootfs, path)

        var flags = 0
        if (removable) flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        if (target != null && !doc.isRoot) {
            flags = flags or Document.FLAG_SUPPORTS_COPY
            if (removable) flags = flags or Document.FLAG_SUPPORTS_MOVE
            if (writableParent && !isDir && writable) flags = flags or Document.FLAG_SUPPORTS_WRITE
        }
        if (isDir && writable) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE

        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, doc.toString())
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, if (isDir) Document.MIME_TYPE_DIR else mimeOf(name))
            add(Document.COLUMN_SIZE, if (target == null || isDir) 0L else target.length())
            add(Document.COLUMN_LAST_MODIFIED, attrs.lastModifiedTime().toMillis())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun childrenUri(parentId: String): Uri =
        DocumentsContract.buildChildDocumentsUri(UbuntuFiles.authority(ctx), parentId)

    private fun notifyChildren(parent: GuestDocumentPaths.DocId) {
        ctx.contentResolver.notifyChange(childrenUri(parent.toString()), null)
    }

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
