package com.ivarna.fluxlinux.core.chroot

import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.core.terminal.FakeContext
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChrootDetectionTest {
    @get:Rule val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        ChrootDetection.invalidate()
    }

    @Test
    fun emptyDir_isNotInstalled() {
        val dir = tmp.newFolder("empty-chroot")
        val snap = ChrootDetection.probe(forceRoot = false, path = dir.absolutePath)
        assertFalse(snap.installed)
        assertTrue(snap.dirExists)
    }

    @Test
    fun marker_isInstalled() {
        val dir = tmp.newFolder("marked")
        File(dir, ".flux_configured").writeText("ok")
        val snap = ChrootDetection.probe(forceRoot = false, path = dir.absolutePath)
        assertTrue(snap.installed)
        assertTrue(ChrootDetection.isInstalled(dir.absolutePath))
    }

    @Test
    fun shellWithoutMarker_isNotInstalled() {
        // M2: an interrupted extraction already has a shell.
        val dir = tmp.newFolder("partial")
        File(dir, "bin").mkdirs()
        File(dir, "bin/sh").writeText("#!")
        val snap = ChrootDetection.probe(forceRoot = false, path = dir.absolutePath)
        assertFalse(snap.installed)
        assertTrue(snap.dirExists)
    }

    @Test
    fun extractMarker_isInstalled() {
        val dir = tmp.newFolder("extracted")
        File(dir, "bin").mkdirs()
        File(dir, "bin/sh").writeText("#!")
        File(dir, ChrootDetection.EXTRACT_MARKER).writeText("sha")
        assertTrue(ChrootDetection.probe(forceRoot = false, path = dir.absolutePath).installed)
        assertTrue(ChrootDetection.isInstalled(dir.absolutePath))
    }

    @Test
    fun legacyConfiguredOrDesktop_isInstalled() {
        val dir = tmp.newFolder("legacy-desktop")
        File(dir, "usr/bin").mkdirs()
        File(dir, "usr/bin/startxfce4").writeText("#!")
        assertTrue(ChrootDetection.probe(forceRoot = false, path = dir.absolutePath).installed)
    }

    @Test
    fun staleCache_trueUntilMarkUninstalled() {
        val path = ChrootPaths.FEDORA_CHROOT_PATH
        ChrootDetection.putCacheForTest(path, true)
        assertTrue(ChrootDetection.isInstalled(path))
        ChrootDetection.markUninstalled(path)
        assertFalse(ChrootDetection.isInstalled(path))
        val ctx = FakeContext(tmp.newFolder("files"), tmp.newFolder("jni").absolutePath)
        assertFalse(TerminalLauncher.isDistroInstalledOnFs(ctx, "fedora_chroot"))
    }

    @Test
    fun proot_filesystem_false_when_container_missing() {
        val ctx = FakeContext(tmp.newFolder("files"), tmp.newFolder("jni").absolutePath)
        assertFalse(TerminalLauncher.isDistroInstalledOnFs(ctx, "fedora"))
        assertFalse(TerminalLauncher.isProotInstalled(ctx, "fedora"))
    }
}
