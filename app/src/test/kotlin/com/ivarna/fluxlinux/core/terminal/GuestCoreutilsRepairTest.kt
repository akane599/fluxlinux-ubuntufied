package com.ivarna.fluxlinux.core.terminal

import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class GuestCoreutilsRepairTest {

    private lateinit var tmp: File
    private lateinit var rootfs: File
    private lateinit var cargo: File
    private lateinit var bin: File
    private lateinit var sbin: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("guest-coreutils").toFile()
        rootfs = File(tmp, "rootfs")
        cargo = File(rootfs, "usr/lib/cargo/bin/coreutils").also { it.mkdirs() }
        bin = File(rootfs, "usr/bin").also { it.mkdirs() }
        sbin = File(rootfs, "usr/sbin").also { it.mkdirs() }
        File(rootfs, ".l2s").mkdirs()
    }

    @After
    fun tearDown() {
        SafeFiles.deleteTree(tmp)
    }

    private fun link(at: File, target: String) {
        Files.createSymbolicLink(at.toPath(), Paths.get(target))
    }

    private fun target(f: File): Path = Files.readSymbolicLink(f.toPath())

    /** uutils entry as link2symlink leaves it: a link into the rootfs `.l2s` store. */
    private fun l2sUtil(name: String) =
        link(File(cargo, name), "${rootfs.path}/.l2s/.l2s.coreutils.dpkg-new0001.0116")

    private fun gnu(dir: File, name: String) = File(dir, "gnu$name").writeText("#gnu $name")

    @Test
    fun repointsBrokenUutilsLinksToGnuTwins() {
        l2sUtil("wc")
        link(File(bin, "wc"), "../lib/cargo/bin/coreutils/wc")
        gnu(bin, "wc")
        l2sUtil("env")
        link(File(bin, "env"), "/usr/lib/cargo/bin/coreutils/env") // absolute form
        gnu(bin, "env")
        // Device layout: uutils links bin/chroot, gnu-coreutils ships sbin/gnuchroot.
        l2sUtil("chroot")
        link(File(bin, "chroot"), "../lib/cargo/bin/coreutils/chroot")
        gnu(sbin, "chroot")
        l2sUtil("mknod")
        link(File(sbin, "mknod"), "../lib/cargo/bin/coreutils/mknod")
        gnu(sbin, "mknod")

        assertEquals(4, GuestCoreutilsRepair.relinkToGnu(rootfs))

        assertEquals(Paths.get("gnuwc"), target(File(bin, "wc")))
        assertEquals(Paths.get("gnuenv"), target(File(bin, "env")))
        assertEquals(Paths.get("../sbin/gnuchroot"), target(File(bin, "chroot")))
        assertEquals("#gnu chroot", File(bin, "chroot").readText())
        assertEquals(Paths.get("gnumknod"), target(File(sbin, "mknod")))
        assertEquals("#gnu wc", File(bin, "wc").readText())
        assertFalse(File(bin, ".wc.flux-gnu").exists())
        // Already GNU: a second pass changes nothing.
        assertEquals(0, GuestCoreutilsRepair.relinkToGnu(rootfs))
    }

    @Test
    fun leavesWorkingUutilsAlone() {
        // chroot / no l2s: the multicall's names are real files, so uutils works.
        File(cargo, "ls").writeText("#multicall")
        link(File(bin, "ls"), "../lib/cargo/bin/coreutils/ls")
        gnu(bin, "ls")

        assertEquals(0, GuestCoreutilsRepair.relinkToGnu(rootfs))
        assertEquals(Paths.get("../lib/cargo/bin/coreutils/ls"), target(File(bin, "ls")))
    }

    @Test
    fun keepsBrokenLinkWithoutGnuTwinOrForeignTarget() {
        l2sUtil("wc")
        link(File(bin, "wc"), "../lib/cargo/bin/coreutils/wc") // no gnuwc
        l2sUtil("ls")
        link(File(bin, "ls"), "/opt/other/ls") // not a uutils link
        gnu(bin, "ls")

        assertEquals(0, GuestCoreutilsRepair.relinkToGnu(rootfs))
        assertEquals(Paths.get("../lib/cargo/bin/coreutils/wc"), target(File(bin, "wc")))
        assertEquals(Paths.get("/opt/other/ls"), target(File(bin, "ls")))
    }

    @Test
    fun noUutilsDirIsANoOp() {
        SafeFiles.deleteTree(File(rootfs, "usr/lib"))
        assertEquals(0, GuestCoreutilsRepair.relinkToGnu(rootfs))
        assertEquals(0, GuestCoreutilsRepair.relinkToGnu(File(tmp, "missing")))
    }
}
