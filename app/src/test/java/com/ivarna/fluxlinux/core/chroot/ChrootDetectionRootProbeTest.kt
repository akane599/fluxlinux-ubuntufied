package com.ivarna.fluxlinux.core.chroot

import com.ivarna.fluxlinux.core.root.RootShell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** L4: a failed / timed-out root probe must not read as "not installed". */
class ChrootDetectionRootProbeTest {
    @get:Rule val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        RootShell.clearSuCache()
        ChrootDetection.invalidate()
    }

    @Test
    fun parseRootProbe_nonZeroExitOrMissingLine_isInconclusive() {
        assertNull(ChrootDetection.parseRootProbe(-2, ""))
        assertNull(ChrootDetection.parseRootProbe(-1, ""))
        assertNull(ChrootDetection.parseRootProbe(1, "MARKER=1 DIR=1 SHELL=1"))
        assertNull(ChrootDetection.parseRootProbe(0, ""))
    }

    @Test
    fun parseRootProbe_readsFlags() {
        assertEquals(
            ChrootDetection.RootProbeFlags(marker = true, dir = true, shell = false),
            ChrootDetection.parseRootProbe(0, "noise\nMARKER=1 DIR=1 SHELL=0\n"),
        )
    }

    @Test
    fun parseRootProbe_readsCompletionFlags() {
        val flags = ChrootDetection.parseRootProbe(0, "MARKER=0 DIR=1 SHELL=1 EXTRACT=1 XFCE=0")
        assertEquals(
            ChrootDetection.RootProbeFlags(
                marker = false, dir = true, shell = true, extractOk = true, xfce = false,
            ),
            flags,
        )
    }

    @Test
    fun isComplete_needsMarkerExtractOrDesktop_notShell() {
        val shellOnly = ChrootDetection.RootProbeFlags(marker = false, dir = true, shell = true)
        assertFalse(ChrootDetection.isComplete(shellOnly))
        assertTrue(ChrootDetection.isComplete(shellOnly.copy(marker = true)))
        assertTrue(ChrootDetection.isComplete(shellOnly.copy(extractOk = true)))
        assertTrue(ChrootDetection.isComplete(shellOnly.copy(xfce = true)))
    }

    @Test
    fun probe_viaRoot_interruptedExtraction_isNotInstalled() {
        assumeTrue(File("/bin/sh").canExecute())
        RootShell.seedSuInvocationForTest(listOf("/bin/sh", "-c"))
        val dir = tmp.newFolder("partial")
        File(dir, "bin").mkdirs()
        File(dir, "bin/sh").writeText("#!")
        File(dir, "usr/bin").mkdirs()
        File(dir, "usr/bin/bash").writeText("#!")
        val snap = ChrootDetection.probe(forceRoot = true, path = dir.absolutePath)
        assertTrue(snap.conclusive)
        assertTrue(snap.viaRoot)
        assertFalse("shell without a completion marker is a partial extract", snap.installed)
        assertTrue(snap.dirExists)

        File(dir, ChrootDetection.EXTRACT_MARKER).writeText("sha")
        assertTrue(ChrootDetection.probe(forceRoot = true, path = dir.absolutePath).installed)
    }

    @Test
    fun probe_viaRoot_legacyInstallWithDesktop_isInstalled() {
        assumeTrue(File("/bin/sh").canExecute())
        RootShell.seedSuInvocationForTest(listOf("/bin/sh", "-c"))
        val dir = tmp.newFolder("legacy")
        File(dir, "usr/bin").mkdirs()
        File(dir, "usr/bin/startxfce4").writeText("#!")
        assertTrue(ChrootDetection.probe(forceRoot = true, path = dir.absolutePath).installed)
    }

    @Test
    fun probe_rootCommandFails_isInconclusive() {
        assumeTrue(File("/bin/false").canExecute())
        // "su" that runs but fails every command.
        RootShell.seedSuInvocationForTest(listOf("/bin/false", "-c"))
        val dir = tmp.newFolder("hidden")
        val snap = ChrootDetection.probe(forceRoot = true, path = dir.absolutePath)
        assertFalse(snap.conclusive)
        assertFalse(snap.viaRoot)
    }

    @Test
    fun probe_rootCommandSucceeds_isConclusive() {
        assumeTrue(File("/bin/sh").canExecute())
        RootShell.seedSuInvocationForTest(listOf("/bin/sh", "-c"))
        val dir = tmp.newFolder("rootfs")
        File(dir, ".flux_configured").writeText("ok")
        val snap = ChrootDetection.probe(forceRoot = true, path = dir.absolutePath)
        assertTrue(snap.conclusive)
        assertTrue(snap.viaRoot)
        assertTrue(snap.installed)
    }
}
