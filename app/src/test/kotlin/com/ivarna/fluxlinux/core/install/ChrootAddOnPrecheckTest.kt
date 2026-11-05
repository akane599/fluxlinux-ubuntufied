package com.ivarna.fluxlinux.core.install

import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.chroot.ChrootDetection
import com.ivarna.fluxlinux.core.root.RootShell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The chroot desktop add-on needs an installed rootfs that only root can see. A root probe
 * that failed or timed out must say so, not "install Ubuntu (Rooted) first".
 */
class ChrootAddOnPrecheckTest {
    @get:Rule val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        RootShell.clearSuCache()
        ChrootDetection.invalidate()
    }

    private fun snap(installed: Boolean, viaRoot: Boolean, conclusive: Boolean = true) =
        ChrootDetection.Snapshot(
            installed = installed,
            markerOk = installed,
            dirExists = installed,
            viaRoot = viaRoot,
            rootOk = viaRoot,
            conclusive = conclusive,
        )

    @Test
    fun decision_installedProceeds_rootSilenceIsNotAMissingRootfs() {
        assertNull(OnboardingInstallRunner.chrootAddOnRefusal(snap(installed = true, viaRoot = true)))
        // App-visible markers are enough on their own.
        assertNull(OnboardingInstallRunner.chrootAddOnRefusal(snap(installed = true, viaRoot = false)))
        // su failed or timed out (inconclusive), or root is gone: unknown, not missing.
        assertEquals(
            R.string.install_addon_root_unverified,
            OnboardingInstallRunner.chrootAddOnRefusal(snap(installed = false, viaRoot = false, conclusive = false)),
        )
        assertEquals(
            R.string.install_addon_root_unverified,
            OnboardingInstallRunner.chrootAddOnRefusal(snap(installed = false, viaRoot = false)),
        )
        // Root answered and saw no completed rootfs.
        assertEquals(
            R.string.install_addon_needs_rootfs,
            OnboardingInstallRunner.chrootAddOnRefusal(snap(installed = false, viaRoot = true)),
        )
    }

    @Test
    fun realProbe_suFails_isTheRootMessage() {
        assumeTrue(File("/bin/false").canExecute())
        RootShell.seedSuInvocationForTest(listOf("/bin/false", "-c"))
        val dir = tmp.newFolder("hidden")
        val refusal = OnboardingInstallRunner.chrootAddOnRefusal(
            ChrootDetection.probe(forceRoot = true, path = dir.absolutePath),
        )
        assertEquals(R.string.install_addon_root_unverified, refusal)
    }

    @Test
    fun realProbe_rootAnswers_missingOrInstalled() {
        assumeTrue(File("/bin/sh").canExecute())
        RootShell.seedSuInvocationForTest(listOf("/bin/sh", "-c"))
        val dir = tmp.newFolder("rootfs")
        assertEquals(
            R.string.install_addon_needs_rootfs,
            OnboardingInstallRunner.chrootAddOnRefusal(ChrootDetection.probe(forceRoot = true, path = dir.absolutePath)),
        )
        File(dir, ChrootDetection.EXTRACT_MARKER).writeText("sha")
        assertNull(
            OnboardingInstallRunner.chrootAddOnRefusal(ChrootDetection.probe(forceRoot = true, path = dir.absolutePath)),
        )
    }

    @Test
    fun runChroot_refusesTheAddOnFromAFreshRootProbe() {
        val rel = "src/main/kotlin/com/ivarna/fluxlinux/core/install/OnboardingInstallRunner.kt"
        val cwd = File("").absoluteFile
        val src = listOf(File(cwd, rel), File(cwd, "app/$rel")).first { it.isFile }.readText()
        val body = src.substringAfter("private fun runChroot(").substringBefore("\n    }\n")
        val branch = body.substringAfter("if (steps.needsInstalledRootfs) {").substringBefore("\n        }\n")
        assertTrue(branch, branch.contains("chrootAddOnRefusal(ChrootDetection.probe(forceRoot = true, path = chrootPath))"))
        assertTrue(branch, branch.contains("appCtx.getString(refusal, profile.displayName)"))
        assertTrue(branch, !branch.contains("isChrootInstalled"))
    }
}
