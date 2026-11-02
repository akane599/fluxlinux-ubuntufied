package com.ivarna.fluxlinux.core.root

import com.ivarna.fluxlinux.core.install.ChrootSetupCommand
import com.ivarna.fluxlinux.core.install.DistroInstallProfile
import com.ivarna.fluxlinux.core.terminal.ChrootCommandBuilder
import com.ivarna.fluxlinux.core.terminal.LinuxCommandBuilder
import com.ivarna.fluxlinux.core.utils.UbuntuRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guest user resolver: akane on both Ubuntu installs, flux on every other distro. */
class GuestUsersTest {

    @Test
    fun names_validated() {
        listOf("akane", "flux", "root", "_x", "a-b_9", "a".repeat(32)).forEach {
            assertTrue(it, GuestUsers.isValidName(it))
        }
        listOf(null, "", "Akane", "1a", "-a", "a b", "a;id", "a'b", "../x", "a".repeat(33)).forEach {
            assertFalse("$it", GuestUsers.isValidName(it))
        }
    }

    @Test
    fun chrootUser_akaneOnlyForUbuntuChroot() {
        assertEquals("akane", UbuntuRuntime.UBUNTU_GUEST_USER)
        assertEquals("akane", GuestUsers.chrootUser("ubuntu_chroot"))
        assertEquals("flux", GuestUsers.chrootUser("debian_chroot"))
        assertEquals("flux", GuestUsers.chrootUser(null))
        assertEquals("akane", GuestUsers.chrootUserForPath(ChrootPaths.UBUNTU_CHROOT_PATH))
        assertEquals("flux", GuestUsers.chrootUserForPath(ChrootPaths.DEBIAN_CHROOT_PATH))
        assertEquals("flux", GuestUsers.chrootUserForPath(null))
        assertEquals(
            ChrootPaths.UBUNTU_CHROOT_PATH,
            DistroInstallProfile.require("ubuntu_chroot").chrootPath,
        )
    }

    @Test
    fun defaultUser_byMethod() {
        assertEquals("akane", GuestUsers.defaultUser("chroot", "ubuntu_chroot"))
        assertEquals("flux", GuestUsers.defaultUser("chroot", "alpine_chroot"))
        assertEquals("akane", GuestUsers.defaultUser("proot", "ubuntu"))
        assertEquals("flux", GuestUsers.defaultUser("proot", "debian"))
        assertEquals("flux", GuestUsers.defaultUser(null, null))
    }

    @Test
    fun resolveChrootUser_explicitWinsAndIsValidated() {
        assertEquals("akane", GuestUsers.resolveChrootUser(null, ChrootPaths.UBUNTU_CHROOT_PATH))
        assertEquals("flux", GuestUsers.resolveChrootUser("flux", ChrootPaths.UBUNTU_CHROOT_PATH))
        assertEquals("root", GuestUsers.resolveChrootUser("root", ChrootPaths.UBUNTU_CHROOT_PATH))
        assertThrows(IllegalArgumentException::class.java) {
            GuestUsers.resolveChrootUser("x; reboot", ChrootPaths.UBUNTU_CHROOT_PATH)
        }
        assertEquals("/root", GuestUsers.homeOf("root"))
        assertEquals("/home/akane", GuestUsers.homeOf("akane"))
    }

    @Test
    fun scriptEnv_chrootOnly() {
        assertEquals(
            mapOf("FLUX_GUEST_USER" to "akane", "FLUX_PROOT" to "0"),
            GuestUsers.scriptEnv("ubuntu_chroot", "chroot"),
        )
        assertEquals("flux", GuestUsers.scriptEnv("debian_chroot", "chroot")["FLUX_GUEST_USER"])
        assertEquals(mapOf("FLUX_GUEST_USER" to "akane"), GuestUsers.scriptEnv("ubuntu", "proot"))
        assertTrue(GuestUsers.scriptEnv("debian", "proot").isEmpty())
    }

    @Test
    fun sessionUser_perTypeAndRuntime() {
        assertEquals("akane", LinuxCommandBuilder.sessionUserForType("shell", "chroot", "ubuntu_chroot"))
        assertEquals("flux", LinuxCommandBuilder.sessionUserForType("shell", "chroot", "debian_chroot"))
        assertEquals("akane", LinuxCommandBuilder.sessionUserForType("shell", "proot", "ubuntu"))
        assertEquals("flux", LinuxCommandBuilder.sessionUserForType("shell", "proot", "alpine"))
        assertEquals("root", LinuxCommandBuilder.sessionUserForType("shell-root", "chroot", "ubuntu_chroot"))
        assertEquals("root", LinuxCommandBuilder.sessionUserForType("component", "chroot", "ubuntu_chroot"))
        assertEquals("flux", LinuxCommandBuilder.sessionUserForType("shell"))
    }

    @Test
    fun prootLoginUser_fallsBackToFluxUntilMigrated() {
        val tmp = java.nio.file.Files.createTempDirectory("guest-users").toFile()
        try {
            val rootfs = java.io.File(tmp, "containers/ubuntu/rootfs")
            java.io.File(rootfs, "etc").mkdirs()
            java.io.File(rootfs, "home/flux").mkdirs()
            // No passwd: the homes decide.
            assertEquals("flux", GuestUsers.prootLoginUser("akane", rootfs))
            java.io.File(rootfs, "home/akane").mkdirs()
            assertEquals("akane", GuestUsers.prootLoginUser("akane", rootfs))
            // passwd wins over the homes.
            val passwd = java.io.File(rootfs, "etc/passwd")
            passwd.writeText("root:x:0:0::/root:/bin/sh\nflux:x:1000:1000::/home/flux:/bin/bash\n")
            assertEquals("flux", GuestUsers.prootLoginUser("akane", rootfs))
            assertEquals("/home/flux", GuestUsers.prootHome(rootfs).path.removePrefix(rootfs.path))
            passwd.writeText("root:x:0:0::/root:/bin/sh\nakane:x:1000:1000::/home/akane:/bin/bash\n")
            assertEquals("akane", GuestUsers.prootLoginUser("akane", rootfs))
            assertEquals("/home/akane", GuestUsers.prootHome(rootfs).path.removePrefix(rootfs.path))
            // Only the Ubuntu user ever falls back; explicit others pass through.
            assertEquals("root", GuestUsers.prootLoginUser("root", rootfs))
            assertEquals("flux", GuestUsers.prootLoginUser("flux", rootfs))
            // Other containers resolve to flux.
            val debian = java.io.File(tmp, "containers/debian/rootfs").apply { mkdirs() }
            assertEquals("/home/flux", GuestUsers.prootHome(debian).path.removePrefix(debian.path))
            assertEquals("flux", GuestUsers.loginUserFromPasswd("akane", "flux:x:1000:1000::/home/flux:/bin/sh"))
            assertEquals("akane", GuestUsers.loginUserFromPasswd("akane", "root:x:0:0::/root:/bin/sh"))
        } finally {
            com.ivarna.fluxlinux.core.system.SafeFiles.deleteTree(tmp)
        }
    }

    @Test
    fun chrootBuilder_defaultsToPathUser() {
        val ubuntu = ChrootCommandBuilder.buildEnv(chrootPath = ChrootPaths.UBUNTU_CHROOT_PATH)
        assertEquals("/home/akane", ubuntu["HOME"])
        assertEquals(ChrootPaths.UBUNTU_CHROOT_PATH, ubuntu["FLUX_CHROOT"])
        assertEquals("/home/flux", ChrootCommandBuilder.buildEnv(chrootPath = ChrootPaths.DEBIAN_CHROOT_PATH)["HOME"])
        assertEquals(
            "exec sh ${ChrootPaths.CHROOT_HELPER} login --user akane --shell zsh",
            ChrootCommandBuilder.buildRootInner("exec zsh", user = "akane"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ChrootCommandBuilder.buildRootInner("exec zsh", user = "a b")
        }
    }

    @Test
    fun chrootSetupCommand_verifiesThenExportsUser() {
        val profile = DistroInstallProfile.require("ubuntu_chroot")
        val cmd = ChrootSetupCommand.build(
            profile, "setup_guest_chroot.sh", "a".repeat(64), "b".repeat(64),
            "/data/adb/ksu/bin/busybox", ChrootPaths.UBUNTU_CHROOT_PATH, "/data/user/0/x/files/home",
            "com.ivarna.fluxlinux",
        )
        val dest = ChrootSetupCommand.stagedPath("setup_guest_chroot.sh")
        assertEquals("${ChrootPaths.ROOT_STAGE_DIR}/setup_guest_chroot.sh", dest)
        val guard = cmd.indexOf(RootShell.shaMatches(dest, "a".repeat(64)))
        assertTrue(guard > 0)
        assertTrue(cmd.contains(RootShell.shaMatches(BusyBoxPaths.RESOLVER_ON_DEVICE, "b".repeat(64))))
        assertTrue(cmd.indexOf("sh '$dest'") > guard)
        assertTrue(cmd.contains("export FLUX_GUEST_USER='akane'; "))
        assertTrue(cmd.contains("export FLUX_CHROOT='${ChrootPaths.UBUNTU_CHROOT_PATH}'; "))
        assertTrue(cmd.contains("export FLUX_DISTRO_LABEL='ubuntu'; "))
        assertTrue(cmd.contains("export FLUX_BB='/data/adb/ksu/bin/busybox'; "))
        assertTrue(cmd.trimEnd().endsWith("exit 127; fi"))
        assertFalse(cmd.contains("/data/local/tmp/setup_"))
        assertFalse(
            "shell-writable busybox never exported",
            ChrootSetupCommand.build(
                profile, "setup_guest_chroot.sh", "a".repeat(64), "b".repeat(64),
                "/data/local/tmp/busybox", ChrootPaths.UBUNTU_CHROOT_PATH, "/h", "p",
            ).contains("FLUX_BB="),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ChrootSetupCommand.build(profile, "../x.sh", "a", "b", null, "/c", "/h", "p")
        }
    }
}
