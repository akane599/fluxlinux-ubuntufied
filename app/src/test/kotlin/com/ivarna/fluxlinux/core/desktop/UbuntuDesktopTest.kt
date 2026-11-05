package com.ivarna.fluxlinux.core.desktop

import com.ivarna.fluxlinux.core.system.SafeFiles
import com.ivarna.fluxlinux.core.utils.FakePrefsContext
import com.ivarna.fluxlinux.core.utils.StateManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The optional Ubuntu desktop: flag + PRoot file check, and probe self-healing. */
class UbuntuDesktopTest {

    private val tmp: File = Files.createTempDirectory("flux-ubuntu-desktop").toFile()

    @After
    fun tearDown() {
        SafeFiles.deleteTree(tmp)
    }

    private fun prootRootfs(ctx: FakePrefsContext): File =
        File(ctx.filesDir, "usr/var/lib/proot-distro/containers/ubuntu/rootfs")

    private fun touch(f: File): File = f.apply { parentFile?.mkdirs(); writeText("#!/bin/sh\n") }

    @Test
    fun isInstalled_flagOrStartxfce4InTheProotRootfs() {
        val rootfs = File(tmp, "rootfs").apply { mkdirs() }
        assertTrue(UbuntuDesktop.isInstalled(flag = true, prootRootfs = null))
        assertTrue(UbuntuDesktop.isInstalled(flag = true, prootRootfs = rootfs))
        assertFalse(UbuntuDesktop.isInstalled(flag = false, prootRootfs = rootfs))
        assertFalse("chroot: no app-visible rootfs", UbuntuDesktop.isInstalled(flag = false, prootRootfs = null))

        touch(File(rootfs, "usr/sbin/startxfce4"))
        assertTrue(UbuntuDesktop.isInstalled(flag = false, prootRootfs = rootfs))
    }

    @Test
    fun hasStartxfce4_binSbinAndSymlinks() {
        val bin = File(tmp, "bin-rootfs")
        assertFalse(UbuntuDesktop.hasStartxfce4(bin))
        touch(File(bin, "usr/bin/startxfce4"))
        assertTrue(UbuntuDesktop.hasStartxfce4(bin))

        // PRoot l2s / absolute guest links dangle on the host: a link still counts.
        val linked = File(tmp, "link-rootfs/usr/bin").apply { mkdirs() }
        Files.createSymbolicLink(File(linked, "startxfce4").toPath(), File(tmp, "missing-target").toPath())
        assertTrue(UbuntuDesktop.hasStartxfce4(File(tmp, "link-rootfs")))
    }

    @Test
    fun healedFlag_setsWhenFound_clearsOnlyOnADefiniteNo() {
        assertEquals(true, UbuntuDesktop.healedFlag(flag = false, found = true))
        assertEquals(false, UbuntuDesktop.healedFlag(flag = true, found = false))
        assertNull("unchanged", UbuntuDesktop.healedFlag(flag = true, found = true))
        assertNull("unchanged", UbuntuDesktop.healedFlag(flag = false, found = false))
        assertNull("inconclusive keeps the flag", UbuntuDesktop.healedFlag(flag = true, found = null))
        assertNull("inconclusive keeps the flag", UbuntuDesktop.healedFlag(flag = false, found = null))
    }

    @Test
    fun probeResult_conclusiveWins_inconclusiveKeepsTheFlag() {
        assertTrue(UbuntuDesktop.probeResult(flag = false, found = true))
        assertFalse(UbuntuDesktop.probeResult(flag = true, found = false))
        assertTrue(UbuntuDesktop.probeResult(flag = true, found = null))
        assertFalse(UbuntuDesktop.probeResult(flag = false, found = null))
    }

    @Test
    fun proot_isInstalledAndProbe_followTheRootfsAndHealTheFlag() {
        val ctx = FakePrefsContext(filesDir = tmp)
        assertFalse(UbuntuDesktop.isInstalled(ctx, "ubuntu"))
        assertFalse(UbuntuDesktop.probe(ctx, "ubuntu"))

        // An install that predates the flag: startxfce4 alone is enough, and probe records it.
        val startx = touch(File(prootRootfs(ctx), "usr/bin/startxfce4"))
        assertTrue(UbuntuDesktop.isInstalled(ctx, "ubuntu"))
        assertFalse(StateManager.isComponentInstalled(ctx, "ubuntu", UbuntuDesktop.COMPONENT_ID))
        assertTrue(UbuntuDesktop.probe(ctx, "ubuntu"))
        assertTrue(StateManager.isComponentInstalled(ctx, "ubuntu", UbuntuDesktop.COMPONENT_ID))

        // XFCE removed from the guest: the flag still answers on main, a probe clears it.
        assertTrue(startx.delete())
        assertTrue(UbuntuDesktop.isInstalled(ctx, "ubuntu"))
        assertFalse(UbuntuDesktop.probe(ctx, "ubuntu"))
        assertFalse(StateManager.isComponentInstalled(ctx, "ubuntu", UbuntuDesktop.COMPONENT_ID))
        assertFalse(UbuntuDesktop.isInstalled(ctx, "ubuntu"))
    }

    @Test
    fun chroot_isInstalledReadsOnlyTheFlag() {
        val ctx = FakePrefsContext(filesDir = tmp)
        // A PRoot rootfs with XFCE says nothing about the chroot install.
        touch(File(prootRootfs(ctx), "usr/bin/startxfce4"))
        assertFalse(UbuntuDesktop.isInstalled(ctx, "ubuntu_chroot"))
        StateManager.setComponentInstalled(ctx, "ubuntu_chroot", UbuntuDesktop.COMPONENT_ID, true)
        assertTrue(UbuntuDesktop.isInstalled(ctx, "ubuntu_chroot"))
    }

    @Test
    fun desktopLauncher_refusesUbuntuWithoutTheDesktop_andProbesChrootOffMain() {
        val rel = "src/main/kotlin/com/ivarna/fluxlinux/core/desktop/DesktopLauncher.kt"
        val cwd = File("").absoluteFile
        val src = listOf(File(cwd, rel), File(cwd, "app/$rel")).first { it.isFile }.readText()
        assertTrue(src.contains("val needsDesktopCheck = UbuntuRuntime.isUbuntuId(distroId)"))
        assertTrue(src.contains("R.string.desktop_install_first"))
        // Chroot: the su probe sits inside the background thread, before main.post.
        val thread = src.indexOf("Thread {")
        val probe = src.indexOf("UbuntuDesktop.probe(app, distroId)")
        val post = src.indexOf("main.post {", thread)
        assertTrue("probe must run on the chroot probe thread", probe in (thread + 1) until post)
    }
}
