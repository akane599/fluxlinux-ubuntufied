package com.ivarna.fluxlinux.core.files

import android.os.ParcelFileDescriptor
import com.ivarna.fluxlinux.core.system.SafeFiles
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/** [GuestFs] over java.nio as this app's uid: the PRoot rootfs lives in the app's own files dir. */
object LocalGuestFs : GuestFs {

    private val NOFOLLOW = arrayOf(LinkOption.NOFOLLOW_LINKS)

    override fun realPath(path: String): String? = try {
        Paths.get(path).toRealPath().toString()
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    override fun lstat(path: String): GuestFs.Stat? = try {
        val a = Files.readAttributes(Paths.get(path), BasicFileAttributes::class.java, *NOFOLLOW)
        val type = when {
            a.isSymbolicLink -> GuestFs.Type.LINK
            a.isDirectory -> GuestFs.Type.DIR
            a.isRegularFile -> GuestFs.Type.FILE
            else -> GuestFs.Type.OTHER
        }
        GuestFs.Stat(type, a.size(), a.lastModifiedTime().toMillis())
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    override fun readLink(path: String): String? = try {
        Files.readSymbolicLink(Paths.get(path)).toString()
    } catch (_: IOException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: SecurityException) {
        null
    }

    override fun list(dir: String): List<String> =
        Files.newDirectoryStream(Paths.get(dir)).use { stream -> stream.map { it.fileName.toString() } }

    override fun canWrite(path: String): Boolean = File(path).canWrite()

    override fun usableSpace(path: String): Long? = File(path).usableSpace

    override fun createFile(path: String): Boolean = File(path).createNewFile()

    override fun mkdir(path: String): Boolean = File(path).mkdir()

    override fun deleteTree(path: String): Boolean = SafeFiles.deleteTree(File(path))

    /** Renames the entry itself; falls back to safe copy+delete across filesystems. */
    override fun move(from: String, to: String) {
        val src = File(from)
        val dst = File(to)
        try {
            Files.move(src.toPath(), dst.toPath(), *NOFOLLOW)
        } catch (e: IOException) {
            val sameFs = runCatching {
                Files.getFileStore(src.parentFile.toPath()) == Files.getFileStore(dst.parentFile.toPath())
            }.getOrDefault(true)
            if (sameFs || !SafeFiles.copyTree(src, dst, overwrite = false) || !SafeFiles.deleteTree(src)) {
                throw FileNotFoundException("Cannot move ${src.name}: ${e.message}")
            }
        }
    }

    override fun copyTree(from: String, to: String): Boolean = SafeFiles.copyTree(File(from), File(to), overwrite = false)

    override fun open(path: String, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.parseMode(mode))
}
