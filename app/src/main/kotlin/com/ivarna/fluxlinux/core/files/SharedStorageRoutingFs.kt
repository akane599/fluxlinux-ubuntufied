package com.ivarna.fluxlinux.core.files

import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Chroot-mode [GuestFs]: paths under host shared storage go to [shared] (direct access with
 * this app's All files access, exactly as in PRoot mode), everything else to [rootfs] (root).
 *
 * [GuestTree] only reaches shared storage by following a guest link whose target is a shared
 * storage path (`/sdcard/...`, `/storage/emulated/0/...`), so every such document is reached
 * through a link: browse/create only, never part of a tree grant, never opened via a file link
 * ([GuestTree.finalLinkLeavesContainer]). Moves and copies between the two backends are refused.
 */
internal class SharedStorageRoutingFs(
    private val rootfs: GuestFs,
    private val shared: GuestFs,
    private val sharedRoot: String,
) : GuestFs {

    private val realShared: String? by lazy { shared.realPath(sharedRoot) }

    private fun isShared(path: String): Boolean {
        val r = realShared ?: return false
        return HostPaths.isUnder(path, r)
    }

    private fun fsFor(path: String): GuestFs = if (isShared(path)) shared else rootfs

    override fun realPath(path: String): String? = if (path == sharedRoot) realShared else rootfs.realPath(path)

    override fun hint(base: String, names: List<String>) = fsFor(base).hint(base, names)

    override fun lstat(path: String): GuestFs.Stat? = fsFor(path).lstat(path)

    override fun readLink(path: String): String? = fsFor(path).readLink(path)

    override fun list(dir: String): List<String> = fsFor(dir).list(dir)

    override fun canWrite(path: String): Boolean = fsFor(path).canWrite(path)

    override fun isForeignMount(path: String): Boolean = fsFor(path).isForeignMount(path)

    override fun containsForeignMount(path: String): Boolean = fsFor(path).containsForeignMount(path)

    override fun usableSpace(path: String): Long? = fsFor(path).usableSpace(path)

    override fun createFile(path: String): Boolean = fsFor(path).createFile(path)

    override fun mkdir(path: String): Boolean = fsFor(path).mkdir(path)

    override fun deleteTree(path: String): Boolean = fsFor(path).deleteTree(path)

    override fun move(from: String, to: String) {
        if (isShared(from) != isShared(to)) throw FileNotFoundException("Cannot move between Ubuntu and shared storage")
        fsFor(from).move(from, to)
    }

    override fun copyTree(from: String, to: String): Boolean =
        isShared(from) == isShared(to) && fsFor(from).copyTree(from, to)

    override fun open(path: String, mode: String): ParcelFileDescriptor = fsFor(path).open(path, mode)
}
