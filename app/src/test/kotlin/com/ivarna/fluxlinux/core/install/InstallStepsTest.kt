package com.ivarna.fluxlinux.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Per-kind install steps: what each run of the install runner does, for both methods. */
class InstallStepsTest {

    private val methods = listOf("proot", "chroot")

    private fun each(kind: InstallKind, check: (String, InstallSteps) -> Unit) =
        methods.forEach { m -> check(m, InstallSteps.of(m, kind)) }

    @Test
    fun terminal_installsNoDesktopPart() {
        each(InstallKind.TERMINAL) { m, s ->
            assertTrue(m, s.downloadRootfs)
            assertTrue(m, s.extractRootfs)
            assertFalse(m, s.needsInstalledRootfs)
            assertFalse("$m: never requires startxfce4", s.requireStartxfce4)
            assertFalse("$m: never marks desktop flags", s.markDesktopComponents)
            assertFalse(m, s.markDesktopIncomplete)
            assertFalse(m, s.installDesktop)
            assertFalse(m, s.customizeDesktop)
            assertFalse(m, s.stageXfceAssets)
            assertFalse(m, s.runHwAccel)
        }
    }

    @Test
    fun terminalAndDesktop_isTheFullInstall() {
        each(InstallKind.TERMINAL_AND_DESKTOP) { m, s ->
            assertTrue(m, s.downloadRootfs)
            assertTrue(m, s.extractRootfs)
            assertFalse(m, s.needsInstalledRootfs)
            assertTrue(m, s.installDesktop)
            assertTrue(m, s.markDesktopIncomplete)
            assertTrue(m, s.requireStartxfce4)
            assertTrue(m, s.stageXfceAssets)
            assertTrue(m, s.customizeDesktop)
            assertTrue(m, s.runHwAccel)
            assertTrue(m, s.markDesktopComponents)
        }
    }

    @Test
    fun desktopAddOn_neverDownloadsOrExtracts_andNeedsTheRootfs() {
        each(InstallKind.DESKTOP_ADD_ON) { m, s ->
            assertFalse("$m: never downloads", s.downloadRootfs)
            assertFalse("$m: never extracts", s.extractRootfs)
            assertTrue(m, s.needsInstalledRootfs)
            // It re-runs family setup and customization with the desktop on.
            assertTrue(m, s.installDesktop)
            assertTrue(m, s.customizeDesktop)
            assertTrue(m, s.markDesktopIncomplete)
            assertTrue(m, s.requireStartxfce4)
            assertTrue(m, s.stageXfceAssets)
            assertTrue(m, s.runHwAccel)
            assertTrue(m, s.markDesktopComponents)
        }
    }

    @Test
    fun methodDifferences_rootCheckHostOmzAndTheFamilyPhase() {
        for (kind in InstallKind.entries) {
            val proot = InstallSteps.of("proot", kind)
            val chroot = InstallSteps.of("chroot", kind)
            assertFalse(kind.name, proot.checkRoot)
            assertTrue(kind.name, chroot.checkRoot)
            assertTrue("$kind: host Oh My Zsh staging is PRoot-only", proot.stageHostOhMyZsh)
            assertFalse(kind.name, chroot.stageHostOhMyZsh)
            assertFalse(kind.name, proot.chroot)
            assertTrue(kind.name, chroot.chroot)
        }
        assertEquals("ROOTFS", InstallSteps.of("proot", InstallKind.TERMINAL).familyPhaseId)
        assertEquals("ROOTFS", InstallSteps.of("proot", InstallKind.TERMINAL_AND_DESKTOP).familyPhaseId)
        assertEquals("XFCE", InstallSteps.of("proot", InstallKind.DESKTOP_ADD_ON).familyPhaseId)
        assertEquals("BASE", InstallSteps.of("chroot", InstallKind.TERMINAL).familyPhaseId)
        assertEquals("XFCE", InstallSteps.of("chroot", InstallKind.TERMINAL_AND_DESKTOP).familyPhaseId)
        assertEquals("XFCE", InstallSteps.of("chroot", InstallKind.DESKTOP_ADD_ON).familyPhaseId)
    }

    @Test
    fun everyStepsPhase_isInThatRunsPlan() {
        for (m in methods) for (kind in InstallKind.entries) {
            val s = InstallSteps.of(m, kind)
            val ids = BaseDesktopInstallPlan.phasesFor(m, "Ubuntu", kind).map { it.id }
            val label = "$m/$kind"
            assertTrue(label, s.familyPhaseId in ids)
            assertTrue(label, "HOST" in ids && "CUSTOM" in ids)
            assertEquals(label, s.checkRoot, "R0" in ids)
            assertEquals(label, s.downloadRootfs, "DL" in ids)
            // Chroot extracts in its own phase; PRoot extracts inside the family phase.
            if (s.chroot) assertEquals(label, s.extractRootfs, "ROOTFS" in ids)
        }
    }
}
