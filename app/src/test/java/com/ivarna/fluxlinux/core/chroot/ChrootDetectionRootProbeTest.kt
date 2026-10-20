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
