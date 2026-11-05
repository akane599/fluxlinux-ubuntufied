package com.ivarna.fluxlinux.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure onboarding plan logic (no rootfs / no assets).
 * Payload builders need ScriptManager + assets — covered on device only.
 */
class BaseDesktopInstallPlanTest {

    @Test
    fun distroById_debian_found() {
        val d = BaseDesktopInstallPlan.distroById("debian")
        assertNotNull(d)
        assertEquals("debian", d!!.id)
        assertTrue(d.prootSupported)
    }

    @Test
    fun distroById_debianChroot_found() {
        val d = BaseDesktopInstallPlan.distroById("debian13_chroot")
        assertNotNull(d)
        assertEquals("debian13_chroot", d!!.id)
        assertTrue(d.chrootSupported)
    }

    @Test
    fun guestEnvExports_chrootGetsUserAndProotFlag() {
        assertEquals(
            "export FLUX_GUEST_USER='akane'\nexport FLUX_PROOT='0'\n",
            BaseDesktopInstallPlan.guestEnvExports(DistroInstallProfile.require("ubuntu_chroot"), null),
        )
        assertEquals(
            "export FLUX_GUEST_USER='akane'\n",
            BaseDesktopInstallPlan.guestEnvExports(DistroInstallProfile.require("ubuntu"), null),
        )
        assertEquals("", BaseDesktopInstallPlan.guestEnvExports(DistroInstallProfile.require("debian"), null))
    }

    /** P3: family/customization/hw-accel payloads on a flux-only PRoot Ubuntu export flux. */
    @Test
    fun guestEnvExports_fluxOnlyProotRootfs_exportsFlux() {
        val rootfs = java.nio.file.Files.createTempDirectory("flux-env-exports").toFile()
        try {
            java.io.File(rootfs, "etc").mkdirs()
            java.io.File(rootfs, "etc/passwd").writeText("root:x:0:0::/root:/bin/sh\nflux:x:1000:1000::/home/flux:/bin/bash\n")
            assertEquals(
                "export FLUX_GUEST_USER='flux'\n",
                BaseDesktopInstallPlan.guestEnvExports(DistroInstallProfile.require("ubuntu"), rootfs),
            )
        } finally {
            com.ivarna.fluxlinux.core.system.SafeFiles.deleteTree(rootfs)
        }
    }

    @Test
    fun distroById_unknown_returnsNull() {
        assertNull(BaseDesktopInstallPlan.distroById("not-a-distro"))
        assertNull(BaseDesktopInstallPlan.distroById(""))
    }

    @Test
    fun methodFor_debian_isProot() {
        assertEquals("proot", BaseDesktopInstallPlan.methodFor("debian"))
    }

    @Test
    fun methodFor_debianChroot_isChroot() {
        assertEquals("chroot", BaseDesktopInstallPlan.methodFor("debian13_chroot"))
        assertEquals("chroot", BaseDesktopInstallPlan.methodFor("debian_chroot"))
    }

    @Test
    fun methodFor_unknown_fallsBackToProot() {
        // terminalComponentFor throws → methodFor catches and returns "proot"
        assertEquals("proot", BaseDesktopInstallPlan.methodFor("totally_unknown_distro"))
    }

    @Test
    fun phasesFor_proot_hasHostRootfsCustom() {
        val phases = BaseDesktopInstallPlan.phasesFor("proot")
        assertEquals(listOf("HOST", "DL", "ROOTFS", "CUSTOM"), phases.map { it.id })
        assertTrue(phases.all { it.weight > 0 })
        assertEquals(100, phases.sumOf { it.weight })
    }

    @Test
    fun phasesFor_chroot_hasRootCheckFirst() {
        val phases = BaseDesktopInstallPlan.phasesFor("chroot")
        assertEquals(listOf("R0", "HOST", "DL", "ROOTFS", "XFCE", "CUSTOM"), phases.map { it.id })
        assertEquals(100, phases.sumOf { it.weight })
    }

    @Test
    fun phasesFor_unknownMethod_treatedAsProotFamily() {
        // Non-chroot methods use proot phase list (install runner only branches on "chroot").
        val phases = BaseDesktopInstallPlan.phasesFor("something-else")
        assertEquals(listOf("HOST", "DL", "ROOTFS", "CUSTOM"), phases.map { it.id })
    }

    @Test
    fun phasesFor_terminal_proot_hasNoDesktopPhase() {
        val phases = BaseDesktopInstallPlan.phasesFor("proot", "Ubuntu", InstallKind.TERMINAL)
        assertEquals(listOf("HOST", "DL", "ROOTFS", "CUSTOM"), phases.map { it.id })
        assertEquals("Installing Ubuntu…", phases.single { it.id == "ROOTFS" }.label)
        assertEquals("Setting up zsh and Oh My Zsh…", phases.single { it.id == "CUSTOM" }.label)
        assertFalse(phases.any { it.label.contains("XFCE") })
        assertTrue(phases.all { it.weight > 0 })
        assertEquals(100, phases.sumOf { it.weight })
    }

    @Test
    fun phasesFor_terminal_chroot_installsBasePackagesInsteadOfXfce() {
        val phases = BaseDesktopInstallPlan.phasesFor("chroot", "Ubuntu (Rooted)", InstallKind.TERMINAL)
        assertEquals(listOf("R0", "HOST", "DL", "ROOTFS", "BASE", "CUSTOM"), phases.map { it.id })
        assertEquals("Installing base packages…", phases.single { it.id == "BASE" }.label)
        assertEquals("Setting up zsh and Oh My Zsh…", phases.single { it.id == "CUSTOM" }.label)
        assertFalse(phases.any { it.label.contains("XFCE") })
        assertTrue(phases.all { it.weight > 0 })
        assertEquals(100, phases.sumOf { it.weight })
    }

    @Test
    fun phasesFor_terminalAndDesktop_isThePreOptionalPlan() {
        for (method in listOf("proot", "chroot")) {
            assertEquals(
                method,
                BaseDesktopInstallPlan.phasesFor(method, "Ubuntu"),
                BaseDesktopInstallPlan.phasesFor(method, "Ubuntu", InstallKind.TERMINAL_AND_DESKTOP),
            )
        }
        assertEquals(
            listOf("R0", "HOST", "DL", "ROOTFS", "XFCE", "CUSTOM"),
            BaseDesktopInstallPlan.phasesFor("chroot", "Ubuntu", InstallKind.TERMINAL_AND_DESKTOP).map { it.id },
        )
    }

    @Test
    fun phasesFor_desktopAddOn_neverDownloadsOrExtracts() {
        val proot = BaseDesktopInstallPlan.phasesFor("proot", "Ubuntu", InstallKind.DESKTOP_ADD_ON)
        assertEquals(listOf("HOST", "XFCE", "CUSTOM"), proot.map { it.id })
        val chroot = BaseDesktopInstallPlan.phasesFor("chroot", "Ubuntu", InstallKind.DESKTOP_ADD_ON)
        assertEquals(listOf("R0", "HOST", "XFCE", "CUSTOM"), chroot.map { it.id })
        for (phases in listOf(proot, chroot)) {
            assertEquals("Installing XFCE desktop…", phases.single { it.id == "XFCE" }.label)
            assertTrue(phases.all { it.weight > 0 })
            assertEquals(100, phases.sumOf { it.weight })
        }
    }

    @Test
    fun familyPhaseId_isAPhaseOfEveryPlan() {
        val expected = mapOf(
            ("proot" to InstallKind.TERMINAL) to "ROOTFS",
            ("proot" to InstallKind.TERMINAL_AND_DESKTOP) to "ROOTFS",
            ("proot" to InstallKind.DESKTOP_ADD_ON) to "XFCE",
            ("chroot" to InstallKind.TERMINAL) to "BASE",
            ("chroot" to InstallKind.TERMINAL_AND_DESKTOP) to "XFCE",
            ("chroot" to InstallKind.DESKTOP_ADD_ON) to "XFCE",
        )
        expected.forEach { (key, id) ->
            val (method, kind) = key
            assertEquals("$method/$kind", id, BaseDesktopInstallPlan.familyPhaseId(method, kind))
            val ids = BaseDesktopInstallPlan.phasesFor(method, "Ubuntu", kind).map { it.id }
            assertTrue("$method/$kind: $ids", id in ids && "HOST" in ids && "CUSTOM" in ids)
        }
    }

    @Test
    fun desktopFlagExports_defaultScriptsToDesktopOn() {
        assertEquals("export FLUX_INSTALL_DESKTOP='1'\n", BaseDesktopInstallPlan.installDesktopExport(true))
        assertEquals("export FLUX_INSTALL_DESKTOP='0'\n", BaseDesktopInstallPlan.installDesktopExport(false))
        assertEquals("export FLUX_CUSTOMIZE_DESKTOP='1'\n", BaseDesktopInstallPlan.customizeDesktopExport(true))
        assertEquals("export FLUX_CUSTOMIZE_DESKTOP='0'\n", BaseDesktopInstallPlan.customizeDesktopExport(false))
    }

    @Test
    fun installKind_terminalOnlyIsUbuntuOnly() {
        assertEquals(InstallKind.TERMINAL, InstallKind.effective("ubuntu", InstallKind.TERMINAL))
        assertEquals(InstallKind.TERMINAL, InstallKind.effective("ubuntu_chroot", InstallKind.TERMINAL))
        // Other family scripts ignore FLUX_INSTALL_DESKTOP and always install XFCE.
        assertEquals(InstallKind.TERMINAL_AND_DESKTOP, InstallKind.effective("debian", InstallKind.TERMINAL))
        assertEquals(InstallKind.DESKTOP_ADD_ON, InstallKind.effective("ubuntu", InstallKind.DESKTOP_ADD_ON))
        assertEquals(
            InstallKind.TERMINAL_AND_DESKTOP,
            InstallKind.effective("ubuntu", InstallKind.TERMINAL_AND_DESKTOP),
        )
        assertFalse(InstallKind.TERMINAL.installsDesktop)
        assertTrue(InstallKind.TERMINAL_AND_DESKTOP.installsDesktop)
        assertTrue(InstallKind.DESKTOP_ADD_ON.installsDesktop)
    }

    @Test
    fun installKind_rerunKeepsAnInstalledDesktopAndNeverAddsOne() {
        assertEquals(InstallKind.TERMINAL_AND_DESKTOP, InstallKind.forRerun(desktopInstalled = true))
        assertEquals(InstallKind.TERMINAL, InstallKind.forRerun(desktopInstalled = false))
    }

    @Test
    fun notificationText_namesWhatEachKindInstalls() {
        val res = InstallKind.entries.map { OnboardingInstallRunner.notificationTextRes(it) }
        assertEquals(InstallKind.entries.size, res.toSet().size)
    }

    @Test
    fun scriptConstants_pointAtDebianFamilyPaths() {
        assertTrue(BaseDesktopInstallPlan.FAMILY_SCRIPT.contains("setup_debian_family"))
        assertTrue(BaseDesktopInstallPlan.CUSTOMIZATION_SCRIPT.contains("setup_customization_debian"))
    }

    @Test
    fun distroById_alpine_found() {
        val d = BaseDesktopInstallPlan.distroById("alpine")
        assertNotNull(d)
        assertEquals("alpine", d!!.id)
        assertTrue(d.prootSupported)
        assertEquals(false, d.comingSoon)
    }

    @Test
    fun distroById_alpineChroot_found() {
        val d = BaseDesktopInstallPlan.distroById("alpine_chroot")
        assertNotNull(d)
        assertEquals("alpine_chroot", d!!.id)
        assertTrue(d.chrootSupported)
    }

    @Test
    fun methodFor_alpine() {
        assertEquals("proot", BaseDesktopInstallPlan.methodFor("alpine"))
        assertEquals("chroot", BaseDesktopInstallPlan.methodFor("alpine_chroot"))
    }

    @Test
    fun phasesFor_alpineDisplayName() {
        val phases = BaseDesktopInstallPlan.phasesFor("proot", "Alpine")
        assertTrue(phases.any { it.label.contains("Alpine") })
        assertEquals(100, phases.sumOf { it.weight })
    }

    @Test
    fun profileFor_alpine() {
        val p = BaseDesktopInstallPlan.profileFor("alpine")
        assertNotNull(p)
        assertEquals("alpine", p!!.prootName)
    }

    @Test
    fun distroById_fedora_void_opensuse() {
        assertEquals("fedora", BaseDesktopInstallPlan.distroById("fedora")!!.id)
        assertEquals("void_chroot", BaseDesktopInstallPlan.distroById("void_chroot")!!.id)
        assertEquals("opensuse", BaseDesktopInstallPlan.distroById("opensuse")!!.id)
        assertEquals("proot", BaseDesktopInstallPlan.methodFor("fedora"))
        assertEquals("chroot", BaseDesktopInstallPlan.methodFor("opensuse_chroot"))
        assertEquals("void", BaseDesktopInstallPlan.profileFor("void")!!.prootName)
    }

    @Test
    fun distroById_deepin_chimera_manjaro() {
        assertEquals("deepin", BaseDesktopInstallPlan.distroById("deepin")!!.id)
        assertEquals("deepin_chroot", BaseDesktopInstallPlan.distroById("deepin_chroot")!!.id)
        assertEquals("chimera", BaseDesktopInstallPlan.distroById("chimera")!!.id)
        assertEquals("chimera_chroot", BaseDesktopInstallPlan.distroById("chimera_chroot")!!.id)
        assertEquals("manjaro", BaseDesktopInstallPlan.distroById("manjaro")!!.id)
        assertEquals("manjaro_chroot", BaseDesktopInstallPlan.distroById("manjaro_chroot")!!.id)
    }

    @Test
    fun methodFor_deepin_chimera_manjaro() {
        assertEquals("proot", BaseDesktopInstallPlan.methodFor("deepin"))
        assertEquals("chroot", BaseDesktopInstallPlan.methodFor("deepin_chroot"))
        assertEquals("proot", BaseDesktopInstallPlan.methodFor("chimera"))
        assertEquals("chroot", BaseDesktopInstallPlan.methodFor("chimera_chroot"))
        assertEquals("proot", BaseDesktopInstallPlan.methodFor("manjaro"))
        assertEquals("chroot", BaseDesktopInstallPlan.methodFor("manjaro_chroot"))
    }

    @Test
    fun profileFor_deepin_chimera_manjaro() {
        assertEquals("deepin", BaseDesktopInstallPlan.profileFor("deepin")!!.prootName)
        assertEquals(
            "manjaro",
            BaseDesktopInstallPlan.profileFor("manjaro")!!.prootName
        )
        assertEquals(
            "chimera",
            BaseDesktopInstallPlan.profileFor("chimera")!!.prootName
        )
        assertEquals(
            "chroot",
            BaseDesktopInstallPlan.profileFor("chimera_chroot")!!.method
        )
        // New family paths must NOT contain debian/alpine (common gets prepended).
        val chimera = BaseDesktopInstallPlan.profileFor("chimera")!!
        assertFalse(chimera.familyScript.contains("debian"))
        assertFalse(chimera.familyScript.contains("alpine"))
        val deepin = BaseDesktopInstallPlan.profileFor("deepin")!!
        assertFalse(deepin.familyScript.contains("debian"))
        assertFalse(deepin.familyScript.contains("alpine"))
    }

    @Test
    fun distroById_ukpa() {
        assertEquals("ubuntu", BaseDesktopInstallPlan.distroById("ubuntu")!!.id)
        assertEquals("ubuntu_chroot", BaseDesktopInstallPlan.distroById("ubuntu_chroot")!!.id)
        assertEquals("kali", BaseDesktopInstallPlan.distroById("kali")!!.id)
        assertEquals("kali_chroot", BaseDesktopInstallPlan.distroById("kali_chroot")!!.id)
        assertEquals("parrot", BaseDesktopInstallPlan.distroById("parrot")!!.id)
        assertEquals("parrot_chroot", BaseDesktopInstallPlan.distroById("parrot_chroot")!!.id)
        assertEquals("archlinux", BaseDesktopInstallPlan.distroById("archlinux")!!.id)
        assertEquals("archlinux_chroot", BaseDesktopInstallPlan.distroById("archlinux_chroot")!!.id)
    }

    @Test
    fun methodFor_ukpa() {
        assertEquals("proot", BaseDesktopInstallPlan.methodFor("ubuntu"))
        assertEquals("chroot", BaseDesktopInstallPlan.methodFor("ubuntu_chroot"))
        assertEquals("proot", BaseDesktopInstallPlan.methodFor("kali"))
        assertEquals("chroot", BaseDesktopInstallPlan.methodFor("kali_chroot"))
        assertEquals("proot", BaseDesktopInstallPlan.methodFor("parrot"))
        assertEquals("chroot", BaseDesktopInstallPlan.methodFor("parrot_chroot"))
        assertEquals("proot", BaseDesktopInstallPlan.methodFor("archlinux"))
        assertEquals("chroot", BaseDesktopInstallPlan.methodFor("archlinux_chroot"))
    }

    @Test
    fun profileFor_ukpa() {
        assertEquals("ubuntu", BaseDesktopInstallPlan.profileFor("ubuntu")!!.prootName)
        assertEquals("kali", BaseDesktopInstallPlan.profileFor("kali")!!.prootName)
        assertEquals("parrot", BaseDesktopInstallPlan.profileFor("parrot")!!.prootName)
        assertEquals("archlinux", BaseDesktopInstallPlan.profileFor("archlinux")!!.prootName)
        assertEquals("chroot", BaseDesktopInstallPlan.profileFor("ubuntu_chroot")!!.method)
        listOf("ubuntu", "kali", "parrot", "archlinux").forEach { id ->
            val p = BaseDesktopInstallPlan.profileFor(id)!!
            assertFalse("$id family must not contain debian", p.familyScript.contains("debian"))
            assertFalse("$id family must not contain alpine", p.familyScript.contains("alpine"))
        }
    }

    @Test
    fun terminalComponentFor_rejectsUnknownDistro() {
        try {
            com.ivarna.fluxlinux.core.data.terminalComponentFor("nope")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unsupported"))
        }
    }
}
