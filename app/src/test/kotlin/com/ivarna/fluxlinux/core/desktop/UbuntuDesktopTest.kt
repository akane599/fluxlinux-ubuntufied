package com.ivarna.fluxlinux.core.desktop

import com.ivarna.fluxlinux.core.desktop.UbuntuDesktop.Decision
import com.ivarna.fluxlinux.core.desktop.UbuntuDesktop.Probe
import com.ivarna.fluxlinux.core.install.InstallKind
import com.ivarna.fluxlinux.core.install.InstallSteps
import com.ivarna.fluxlinux.core.install.OnboardingInstallRunner
import com.ivarna.fluxlinux.core.system.SafeFiles
import com.ivarna.fluxlinux.core.utils.FakePrefsContext
import com.ivarna.fluxlinux.core.utils.StateManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The optional Ubuntu desktop: detection rules (flag, incomplete marker, startxfce4 check),
 * flag healing, and what failed, cancelled and successful install runs leave behind.
 */
class UbuntuDesktopTest {

    private val tmp: File = Files.createTempDirectory("flux-ubuntu-desktop").toFile()
    private val ctx = FakePrefsContext(filesDir = tmp)

    @After
    fun tearDown() {
        SafeFiles.deleteTree(tmp)
    }

    private val proot = "ubuntu"
    private val chroot = "ubuntu_chroot"

    private fun prootRootfs(): File =
        File(ctx.filesDir, "usr/var/lib/proot-distro/containers/ubuntu/rootfs")

    private fun touch(f: File): File = f.apply { parentFile?.mkdirs(); writeText("#!/bin/sh\n") }

    private fun flag(id: String) = StateManager.isComponentInstalled(ctx, id, UbuntuDesktop.COMPONENT_ID)

    private fun steps(kind: InstallKind, method: String = "proot") = InstallSteps.of(method, kind)

    /** What the runner does around a desktop run's family step. */
    private fun runStarts(id: String, kind: InstallKind, method: String = "proot") =
        OnboardingInstallRunner.beforeFamilyStep(ctx, id, steps(kind, method))

    private fun runSucceeds(id: String, kind: InstallKind, method: String = "proot") =
        OnboardingInstallRunner.recordDesktopComponents(ctx, id, steps(kind, method), customOk = true, hwOk = true)

    // ── pure rule ────────────────────────────────────────────────────────────

    @Test
    fun decide_installedIsFlagOrAFoundCheckWithNoUnfinishedRun() {
        val installed = DesktopDetection.INSTALLED
        val not = DesktopDetection.NOT_INSTALLED
        val incomplete = DesktopDetection.INCOMPLETE
        // flag, incomplete, probe -> detection, flag to store
        assertEquals(Decision(installed, null), UbuntuDesktop.decide(true, false, Probe.FOUND))
        assertEquals(Decision(installed, null), UbuntuDesktop.decide(true, false, Probe.UNKNOWN))
        assertEquals("stale flag heals to false", Decision(not, false), UbuntuDesktop.decide(true, false, Probe.MISSING))
        assertEquals("predates the flag: heals to true", Decision(installed, true), UbuntuDesktop.decide(false, false, Probe.FOUND))
        assertEquals(Decision(not, null), UbuntuDesktop.decide(false, false, Probe.MISSING))
        assertEquals(Decision(not, null), UbuntuDesktop.decide(false, false, Probe.UNKNOWN))
        // An unfinished run: startxfce4 proves nothing and never sets the flag.
        assertEquals(Decision(incomplete, null), UbuntuDesktop.decide(false, true, Probe.FOUND))
        assertEquals(Decision(incomplete, null), UbuntuDesktop.decide(false, true, Probe.UNKNOWN))
        assertEquals(Decision(incomplete, null), UbuntuDesktop.decide(false, true, Probe.MISSING))
        // A verified desktop whose re-run did not finish stays installed while XFCE is there.
        assertEquals(Decision(installed, null), UbuntuDesktop.decide(true, true, Probe.FOUND))
        assertEquals(Decision(installed, null), UbuntuDesktop.decide(true, true, Probe.UNKNOWN))
        assertEquals(Decision(incomplete, false), UbuntuDesktop.decide(true, true, Probe.MISSING))
    }

    @Test
    fun hasStartxfce4_binSbinAndSymlinks() {
        val bin = File(tmp, "bin-rootfs")
        assertFalse(UbuntuDesktop.hasStartxfce4(bin))
        touch(File(bin, "usr/bin/startxfce4"))
        assertTrue(UbuntuDesktop.hasStartxfce4(bin))
        val sbin = File(tmp, "sbin-rootfs")
        touch(File(sbin, "usr/sbin/startxfce4"))
        assertTrue(UbuntuDesktop.hasStartxfce4(sbin))

        // PRoot l2s / absolute guest links dangle on the host: a link still counts.
        val linked = File(tmp, "link-rootfs/usr/bin").apply { mkdirs() }
        Files.createSymbolicLink(File(linked, "startxfce4").toPath(), File(tmp, "missing-target").toPath())
        assertTrue(UbuntuDesktop.hasStartxfce4(File(tmp, "link-rootfs")))
    }

    // ── PRoot: the file check is conclusive both ways ───────────────────────

    @Test
    fun proot_predatesTheFlag_isRecognisedAndRecorded() {
        assertEquals(DesktopDetection.NOT_INSTALLED, UbuntuDesktop.detect(ctx, proot, mayAskRoot = false))
        touch(File(prootRootfs(), "usr/bin/startxfce4"))
        assertFalse(flag(proot))
        assertEquals(DesktopDetection.INSTALLED, UbuntuDesktop.detect(ctx, proot, mayAskRoot = false))
        assertTrue("healed to true", flag(proot))
    }

    @Test
    fun proot_staleFlag_healsToFalse() {
        StateManager.setComponentInstalled(ctx, proot, UbuntuDesktop.COMPONENT_ID, true)
        assertEquals("stored state answers first", DesktopDetection.INSTALLED, UbuntuDesktop.storedDetection(ctx, proot))
        // XFCE is gone from the guest (or never was): the check wins and clears the flag.
        assertEquals(DesktopDetection.NOT_INSTALLED, UbuntuDesktop.detect(ctx, proot, mayAskRoot = false))
        assertFalse(flag(proot))
        assertEquals(DesktopDetection.NOT_INSTALLED, UbuntuDesktop.storedDetection(ctx, proot))
    }

    @Test
    fun proot_runFailedAfterDpkgUnpackedStartxfce4_staysIncomplete() {
        for (kind in listOf(InstallKind.DESKTOP_ADD_ON, InstallKind.TERMINAL_AND_DESKTOP)) {
            val c = FakePrefsContext(filesDir = File(tmp, kind.name))
            OnboardingInstallRunner.beforeFamilyStep(c, proot, steps(kind))
            touch(File(c.filesDir, "usr/var/lib/proot-distro/containers/ubuntu/rootfs/usr/bin/startxfce4"))
            // The family script then fails (configure, locale step): no recordDesktopComponents.
            assertEquals(kind.name, DesktopDetection.INCOMPLETE, UbuntuDesktop.detect(c, proot, mayAskRoot = false))
            assertFalse("$kind: never healed to installed", StateManager.isComponentInstalled(c, proot, UbuntuDesktop.COMPONENT_ID))
            assertTrue(kind.name, UbuntuDesktop.isIncomplete(c, proot))
            assertEquals(kind.name, DesktopDetection.INCOMPLETE, UbuntuDesktop.storedDetection(c, proot))
        }
    }

    @Test
    fun proot_cancelledRun_staysIncomplete_untilARunSucceeds() {
        runStarts(proot, InstallKind.DESKTOP_ADD_ON)
        touch(File(prootRootfs(), "usr/bin/startxfce4"))
        // InstallController.cancel → the runner aborts before its success path: same state,
        // and also after process death (the marker is persisted).
        repeat(2) {
            assertEquals(DesktopDetection.INCOMPLETE, UbuntuDesktop.detect(ctx, proot, mayAskRoot = false))
        }
        assertFalse(flag(proot))

        // A retried add-on that succeeds records the desktop and clears the marker.
        runStarts(proot, InstallKind.DESKTOP_ADD_ON)
        runSucceeds(proot, InstallKind.DESKTOP_ADD_ON)
        assertFalse(UbuntuDesktop.isIncomplete(ctx, proot))
        assertTrue(flag(proot))
        assertEquals(DesktopDetection.INSTALLED, UbuntuDesktop.detect(ctx, proot, mayAskRoot = false))
        assertTrue(StateManager.isComponentInstalled(ctx, proot, "customization"))
        assertTrue(StateManager.isComponentInstalled(ctx, proot, "hw_accel"))
    }

    @Test
    fun terminalOnlyRun_neverTouchesTheDesktopState() {
        runStarts(proot, InstallKind.TERMINAL)
        runSucceeds(proot, InstallKind.TERMINAL)
        assertFalse(UbuntuDesktop.isIncomplete(ctx, proot))
        assertFalse(flag(proot))
        assertFalse(StateManager.isComponentInstalled(ctx, proot, "customization"))

        // ... nor an unfinished add-on before it.
        runStarts(proot, InstallKind.DESKTOP_ADD_ON)
        runStarts(proot, InstallKind.TERMINAL)
        runSucceeds(proot, InstallKind.TERMINAL)
        assertTrue(UbuntuDesktop.isIncomplete(ctx, proot))
    }

    @Test
    fun partialCustomization_recordsTheDesktopButNotTheCustomization() {
        runStarts(proot, InstallKind.DESKTOP_ADD_ON)
        OnboardingInstallRunner.recordDesktopComponents(
            ctx, proot, steps(InstallKind.DESKTOP_ADD_ON), customOk = false, hwOk = false,
        )
        assertTrue(flag(proot))
        assertFalse(UbuntuDesktop.isIncomplete(ctx, proot))
        assertFalse(StateManager.isComponentInstalled(ctx, proot, "customization"))
        assertFalse(StateManager.isComponentInstalled(ctx, proot, "hw_accel"))
    }

    @Test
    fun uninstall_clearsTheIncompleteMarker() {
        runStarts(proot, InstallKind.DESKTOP_ADD_ON)
        runStarts(chroot, InstallKind.DESKTOP_ADD_ON, method = "chroot")
        StateManager.clearDistroState(ctx, proot)
        assertFalse(UbuntuDesktop.isIncomplete(ctx, proot))
        assertTrue("the other install keeps its own", UbuntuDesktop.isIncomplete(ctx, chroot))
    }

    // ── chroot: the stored state unless root may be asked ───────────────────

    @Test
    fun chroot_withoutRoot_answersFromTheStoredState() {
        // A PRoot rootfs with XFCE says nothing about the chroot install.
        touch(File(prootRootfs(), "usr/bin/startxfce4"))
        assertEquals(DesktopDetection.NOT_INSTALLED, UbuntuDesktop.detect(ctx, chroot, mayAskRoot = false))

        runStarts(chroot, InstallKind.DESKTOP_ADD_ON, method = "chroot")
        assertEquals(DesktopDetection.INCOMPLETE, UbuntuDesktop.detect(ctx, chroot, mayAskRoot = false))
        assertFalse(flag(chroot))

        runSucceeds(chroot, InstallKind.DESKTOP_ADD_ON, method = "chroot")
        assertEquals(DesktopDetection.INSTALLED, UbuntuDesktop.detect(ctx, chroot, mayAskRoot = false))
        assertEquals(DesktopDetection.INSTALLED, UbuntuDesktop.storedDetection(ctx, chroot))
    }

    // ── call sites ──────────────────────────────────────────────────────────

    private fun src(rel: String): String {
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel")).first { it.isFile }.readText()
    }

    @Test
    fun desktopLauncher_refusesUbuntuWithoutTheDesktop_andProbesChrootOffMain() {
        val launcher = src("src/main/kotlin/com/ivarna/fluxlinux/core/desktop/DesktopLauncher.kt")
        assertTrue(launcher.contains("val needsDesktopCheck = UbuntuRuntime.isUbuntuId(distroId)"))
        assertEquals(R_FIRST, DesktopLauncher.desktopMissingMessage(DesktopDetection.NOT_INSTALLED))
        assertEquals(R_INCOMPLETE, DesktopLauncher.desktopMissingMessage(DesktopDetection.INCOMPLETE))
        // Chroot: the su probe sits inside the background thread, before main.post.
        val thread = launcher.indexOf("Thread {")
        val probe = launcher.indexOf("UbuntuDesktop.detect(app, distroId, mayAskRoot = true)")
        val post = launcher.indexOf("main.post {", thread)
        assertTrue("probe must run on the chroot probe thread", probe in (thread + 1) until post)
        // PRoot: the file check also runs on its worker thread.
        val prootThread = launcher.indexOf("Thread {", post)
        val prootCheck = launcher.indexOf("UbuntuDesktop.detect(app, distroId, mayAskRoot = false)")
        assertTrue(prootCheck in (prootThread + 1) until launcher.indexOf("main.post {", prootThread))
    }

    @Test
    fun screens_detectOnlyThroughTheOneEntryPoint() {
        for (rel in listOf(
            "src/main/kotlin/com/ivarna/fluxlinux/ui/screens/HomeScreen.kt",
            "src/main/kotlin/com/ivarna/fluxlinux/ui/screens/InstallConfigScreen.kt",
            "src/main/kotlin/com/ivarna/fluxlinux/core/desktop/DesktopLauncher.kt",
        )) {
            val s = src(rel)
            assertTrue(rel, s.contains("UbuntuDesktop.detect("))
            assertFalse(rel, s.contains("UbuntuDesktop.COMPONENT_ID"))
            assertFalse(rel, s.contains("UbuntuDesktop.probe("))
            assertFalse(rel, s.contains("UbuntuDesktop.isInstalled("))
        }
    }

    private companion object {
        val R_FIRST = com.ivarna.fluxlinux.R.string.desktop_install_first
        val R_INCOMPLETE = com.ivarna.fluxlinux.R.string.desktop_install_incomplete
    }
}
