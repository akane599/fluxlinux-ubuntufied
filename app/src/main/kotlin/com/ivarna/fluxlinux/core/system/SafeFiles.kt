package com.ivarna.fluxlinux.core.system

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Recursive file-tree operations that NEVER follow symbolic links.
 *
 * Why this exists: guest homes inside a proot rootfs carry links such as
 * `~/storage/shared -> /storage/emulated/0`. Kotlin's [File.deleteRecursively],
 * [File.copyRecursively] and `File.walk*` follow symlinked directories, so a
 * "delete the container" or "measure the container" would descend into the
 * user's whole shared storage and delete, copy or count it. Every recursive
 * operation over app-owned trees must go through this object instead.
 *
 * Rules, at every level (including the target/root itself):
 *  - a symlink is an entry of its own: deleted as a link, copied as a link
 *    (same target text), visited once and never traversed;
 *  - failures on individual entries are best-effort: the operation continues
 *    and reports overall success through its return value.
 *
 * Uses java.nio ([Files.walkFileTree] without `FOLLOW_LINKS`,
 * [LinkOption.NOFOLLOW_LINKS]); available from API 26 (our minSdk).
 * Only the LAST path component is checked for link-ness; parent components such
 * as `/data/user/0 -> /data/data` resolve normally.
 */
object SafeFiles {

    private val NOFOLLOW = arrayOf(LinkOption.NOFOLLOW_LINKS)

    /** True when [f] itself is a symbolic link (dangling or not). */
    fun isSymlink(f: File): Boolean =
        try {
            Files.isSymbolicLink(f.toPath())
        } catch (_: Exception) {
            false
        }

    /**
     * Deletes [target] and, when it is a real directory, everything below it,
     * without following any symlink: a link at any level (including [target]
     * itself) is unlinked, its target is never touched. Entries that vanish
     * concurrently are tolerated.
     *
     * @return true when nothing remains at [target] (also true when it never existed)
     */
    fun deleteTree(target: File): Boolean {
        val root = target.toPath()
        val attrs = readAttrs(root) ?: return true
        if (attrs.isDirectory) {
            Files.walkFileTree(root, emptySet(), Int.MAX_VALUE, DeleteVisitor)
        } else {
            deleteQuietly(root)
        }
        return !existsNoFollow(root)
    }

    /**
     * Copies [src] to [dst] without following links: directories are recreated,
     * regular files are copied with their attributes (permission bits), symlinks
     * are recreated as symlinks with the same target text. Special files
     * (FIFOs, sockets, devices) are skipped. An existing real directory at a
     * destination directory is merged into; any other existing entry is replaced
     * only when [overwrite] is true.
     *
     * @return true when every entry was copied
     */
    fun copyTree(src: File, dst: File, overwrite: Boolean = true): Boolean {
        val srcRoot = src.toPath()
        if (readAttrs(srcRoot) == null) return false
        dst.parentFile?.mkdirs()
        val visitor = CopyVisitor(srcRoot, dst.toPath(), overwrite)
        Files.walkFileTree(srcRoot, emptySet(), Int.MAX_VALUE, visitor)
        return visitor.ok
    }

    /**
     * Visits every entry under (and including) [root] once, never following a
     * link: a symlink is reported with its own attributes
     * ([BasicFileAttributes.isSymbolicLink]) and not traversed. Unreadable or
     * vanished entries are skipped. An exception thrown by [visit] aborts the
     * walk and propagates to the caller.
     */
    fun walkNoFollow(root: File, visit: (file: File, attrs: BasicFileAttributes) -> Unit) {
        Files.walkFileTree(root.toPath(), emptySet(), Int.MAX_VALUE, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                visit(dir.toFile(), attrs)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                visit(file.toFile(), attrs)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                FileVisitResult.CONTINUE

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult =
                FileVisitResult.CONTINUE
        })
    }

    private fun readAttrs(p: Path): BasicFileAttributes? =
        try {
            Files.readAttributes(p, BasicFileAttributes::class.java, *NOFOLLOW)
        } catch (_: IOException) {
            null
        }

    private fun existsNoFollow(p: Path): Boolean = Files.exists(p, *NOFOLLOW)

    private fun deleteQuietly(p: Path): Boolean =
        try {
            Files.deleteIfExists(p)
            true
        } catch (_: IOException) {
            false
        }

    /** Bottom-up delete; links arrive in visitFile (never pre/postVisitDirectory). */
    private object DeleteVisitor : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            deleteQuietly(file)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            // Vanished, or a directory we could not open: try a plain unlink/rmdir
            // (fails harmlessly when non-empty); never descend.
            deleteQuietly(file)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            deleteQuietly(dir)
            return FileVisitResult.CONTINUE
        }
    }

    private class CopyVisitor(
        private val srcRoot: Path,
        private val dstRoot: Path,
        private val overwrite: Boolean
    ) : SimpleFileVisitor<Path>() {
        var ok = true
            private set

        private fun dest(p: Path): Path = dstRoot.resolve(srcRoot.relativize(p).toString())

        /** Clears whatever sits at [target] so a new entry can be created there. */
        private fun makeRoom(target: Path): Boolean {
            if (!existsNoFollow(target)) return true
            return overwrite && deleteTree(target.toFile())
        }

        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            val target = dest(dir)
            if (readAttrs(target)?.isDirectory == true) return FileVisitResult.CONTINUE
            return try {
                if (!makeRoom(target)) throw IOException("exists: $target")
                Files.createDirectories(target)
                FileVisitResult.CONTINUE
            } catch (_: IOException) {
                ok = false
                FileVisitResult.SKIP_SUBTREE
            }
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (!attrs.isSymbolicLink && !attrs.isRegularFile) return FileVisitResult.CONTINUE
            val target = dest(file)
            try {
                if (!makeRoom(target)) throw IOException("exists: $target")
                if (attrs.isSymbolicLink) {
                    Files.createSymbolicLink(target, Files.readSymbolicLink(file))
                } else {
                    Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
                }
            } catch (_: IOException) {
                ok = false
            }
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
            ok = false
            return FileVisitResult.CONTINUE
        }
    }
}
