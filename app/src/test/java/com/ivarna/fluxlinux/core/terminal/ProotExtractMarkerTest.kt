package com.ivarna.fluxlinux.core.terminal

import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * [M2] A proot rootfs counts as installed only after a completed extraction
 * (`.flux_extract_ok`), or for pre-marker installs that provably finished.
 */
class ProotExtractMarkerTest {

    private lateinit var files: File
    private lateinit var ctx: FakeContext
    private val name = "ubuntu"
    private val container get() = File(files, "usr/var/lib/proot-distro/containers/$name")
    private val rootfs get() = File(container, "rootfs")
    private val home get() = File(files, "home")

    @Before
    fun setUp() {
        files = Files.createTempDirectory("flux-extract-marker").toFile()
        ctx = FakeContext(files, "$files/lib")
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/bash").writeText("elf")
        home.mkdirs()
    }

    @After
    fun tearDown() {
        SafeFiles.deleteTree(files)
    }

    @Test
    fun partialExtraction_withShellButNoMarker_isNotInstalled() {
        assertTrue(TerminalLauncher.guestRootfsHasShell(rootfs))
        assertFalse(TerminalLauncher.isProotInstalled(ctx, name))
    }

    @Test
    fun marker_makesItInstalled() {
        File(container, TerminalLauncher.EXTRACT_MARKER).writeText("abc\n")
        assertTrue(TerminalLauncher.isProotInstalled(ctx, name))
    }

    @Test
    fun legacyInstall_withXfceOrPostInstallMarker_isStillInstalled() {
        File(rootfs, "usr/bin/startxfce4").writeText("#!/bin/sh")
        assertTrue(TerminalLauncher.isProotInstalled(ctx, name))
        File(rootfs, "usr/bin/startxfce4").delete()
        File(home, ".fluxlinux_distro_${name}_installed").writeText("")
        assertTrue(TerminalLauncher.isProotInstalled(ctx, name))
    }

    private fun stageDebianUserland(dpkgStatus: String = "Package: bash\nStatus: install ok installed\n") {
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/os-release").writeText("ID=ubuntu\nVERSION_ID=\"26.04\"\n")
        File(rootfs, "var/lib/dpkg").mkdirs()
        File(rootfs, "var/lib/dpkg/status").writeText(dpkgStatus)
    }

    @Test
    fun legacyExtractedButSetupFailed_completeUserland_isInstalled() {
        // Extraction finished, family setup failed: no XFCE, no post-install marker.
        stageDebianUserland()
        assertTrue(TerminalLauncher.isProotInstalled(ctx, name))
    }

    @Test
    fun legacyRpmUserland_isInstalled() {
        File(rootfs, "usr/lib").mkdirs()
        File(rootfs, "usr/lib/os-release").writeText("ID=fedora\n")
        File(rootfs, "usr/lib/sysimage/rpm").mkdirs()
        File(rootfs, "usr/lib/sysimage/rpm/rpmdb.sqlite").writeText("db")
        assertTrue(TerminalLauncher.isProotInstalled(ctx, name))
    }

    @Test
    fun partialUserland_emptyPackageDbOrNoOsRelease_isNotInstalled() {
        stageDebianUserland(dpkgStatus = "")
        assertFalse(TerminalLauncher.isProotInstalled(ctx, name))
        File(rootfs, "var/lib/dpkg/status").writeText("Package: bash\n")
        File(rootfs, "etc/os-release").delete()
        assertFalse(TerminalLauncher.isProotInstalled(ctx, name))
    }

    @Test
    fun script_legacyCompleteUserland_isAdoptedNotWiped() {
        stageDebianUserland()
        val (exit, out) = runScriptFns("rootfs_extract_complete $name")
        assertEquals(out, 0, exit)
        assertTrue(File(container, ".flux_extract_ok").isFile)
    }

    @Test
    fun script_partialUserland_isIncomplete() {
        stageDebianUserland(dpkgStatus = "")
        val (exit, _) = runScriptFns("rootfs_extract_complete $name")
        assertEquals(1, exit)
        assertFalse(File(container, ".flux_extract_ok").exists())
    }

    @Test
    fun markerWithoutShell_isNotInstalled() {
        SafeFiles.deleteTree(rootfs)
        rootfs.mkdirs()
        File(container, TerminalLauncher.EXTRACT_MARKER).writeText("abc\n")
        assertFalse(TerminalLauncher.isProotInstalled(ctx, name))
    }

    // ── flux_install.sh rootfs_extract_complete / mark_extract_ok (run under host bash) ──

    private fun runScriptFns(body: String): Pair<Int, String> {
        assumeTrue(File("/bin/bash").canExecute())
        val script = repoFile("src/main/assets/scripts/debian/proot/setup/flux_install.sh").readText()
        val fns = script.substring(
            script.indexOf("EXTRACT_MARKER_NAME="),
            script.indexOf("guest_version_id() {"),
        )
        val harness = File(files, "harness.sh")
        harness.writeText("set -u\nPREFIX='${files}/usr'\nHOME='$home'\nROOTFS_SHA256='pinsha'\n$fns\n$body\n")
        val p = ProcessBuilder("/bin/bash", harness.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        return p.exitValue() to out
    }

    @Test
    fun script_partialTree_isIncomplete() {
        val (exit, _) = runScriptFns("rootfs_extract_complete $name")
        assertEquals(1, exit)
        assertFalse(File(container, ".flux_extract_ok").exists())
    }

    @Test
    fun script_markThenCheck_recordsPinnedSha() {
        val (exit, _) = runScriptFns("mark_extract_ok $name && rootfs_extract_complete $name")
        assertEquals(0, exit)
        assertEquals("pinsha", File(container, ".flux_extract_ok").readText().trim())
    }

    @Test
    fun script_legacyInstall_isAdoptedAndMarked() {
        File(home, ".fluxlinux_distro_${name}_installed").writeText("")
        val (exit, out) = runScriptFns("rootfs_extract_complete $name")
        assertEquals(out, 0, exit)
        assertTrue(File(container, ".flux_extract_ok").isFile)
    }

    @Test
    fun script_olderPinMarker_isKeptNotWiped() {
        File(container, ".flux_extract_ok").writeText("oldsha\n")
        val (exit, out) = runScriptFns("rootfs_extract_complete $name")
        assertEquals(0, exit)
        assertTrue(out, out.contains("keeping it"))
    }

    @Test
    fun script_skipsExtractOnlyWhenComplete() {
        val script = repoFile("src/main/assets/scripts/debian/proot/setup/flux_install.sh").readText()
        assertTrue(script.contains("if rootfs_has_shell \"\$DISTRO_ROOTFS\" && ! rootfs_extract_complete \"\$DISTRO\"; then"))
        // Marker written after both the file and registry install paths.
        assertEquals(2, Regex(Regex.escape("mark_extract_ok \"\$DISTRO\" || EXIT_CODE=1")).findAll(script).count())
    }

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        val candidates = listOf(
            File(cwd, rel),
            File(cwd, "app/$rel"),
            File(cwd.parentFile, rel),
            File(cwd.parentFile, "app/$rel"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("missing $rel (cwd=$cwd)")
    }
}
