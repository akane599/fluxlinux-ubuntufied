package com.ivarna.fluxlinux.core.chroot

import com.ivarna.fluxlinux.core.root.ChrootPaths
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
import java.nio.file.attribute.PosixFilePermissions

/**
 * fluxlinux_chroot.sh v3.0: the real `guest user` and `storage links` blocks (extracted
 * between their markers, MountLockTest pattern) run with /bin/sh against a temp rootfs.
 */
class ChrootGuestUserScriptTest {

    private lateinit var tmp: File
    private lateinit var chroot: File
    private lateinit var lib: File

    @Before
    fun setUp() {
        assumeTrue("needs a POSIX sh", File("/bin/sh").canExecute())
        tmp = Files.createTempDirectory("flux-guest-user").toFile()
        chroot = File(tmp, "rootfs").apply { mkdirs() }
        File(chroot, "etc").mkdirs()
        lib = File(tmp, "lib.sh").apply {
            writeText(
                "die() { echo \"DIE: \$*\"; exit 3; }\n" +
                    block(MIGRATION_BEGIN, MIGRATION_END) +
                    block("# --- guest user (v3.0)", "# --- end guest user") +
                    block("# --- storage links (v3.0)", "# --- end storage links"),
            )
        }
    }

    @After
    fun tearDown() {
        if (::tmp.isInitialized) SafeFiles.deleteTree(tmp)
    }

    private fun asset(name: String): String {
        val rel = "src/main/assets/scripts/chroot/$name"
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel")).first { it.exists() }.readText()
    }

    private val helperText: String by lazy { asset("fluxlinux_chroot.sh") }

    private fun block(begin: String, end: String, text: String = helperText): String {
        val start = text.indexOf(begin)
        val stop = text.indexOf(end, start)
        assertTrue("markers $begin / $end", start >= 0 && stop > start)
        return text.substring(start, stop) + "\n"
    }

    private fun passwd(vararg lines: String) =
        File(chroot, "etc/passwd").writeText(lines.joinToString("\n", postfix = "\n"))

    private fun sh(body: String): Pair<Int, String> {
        val p = ProcessBuilder("/bin/sh", "-c", ". '${lib.absolutePath}'\n$body")
            .redirectErrorStream(true)
            .apply { environment()["FLUX_CHROOT"] = chroot.absolutePath }
            .start()
        val out = String(p.inputStream.readBytes()).trim()
        return p.waitFor() to out
    }

    private fun resolve(user: String): Pair<Int, String> =
        sh("USER_NAME='$user'; resolve_guest_user; echo \"\$USER_NAME \$USER_HOME\"")

    @Test
    fun default_prefersAkane_elseFlux() {
        passwd("root:x:0:0::/root:/bin/sh", "flux:x:1000:1000::/home/flux:/bin/bash")
        assertEquals(0 to "flux /home/flux", resolve(""))
        passwd(
            "root:x:0:0::/root:/bin/sh",
            "flux:x:1000:1000::/home/flux:/bin/bash",
            "akane:x:1001:1001::/home/akane:/usr/bin/zsh",
        )
        assertEquals(0 to "akane /home/akane", resolve(""))
        assertEquals(0 to "flux /home/flux", resolve("flux"))
        assertEquals(0 to "root /root", resolve("root"))
    }

    @Test
    fun invalidOrUnknownUser_dies() {
        passwd("root:x:0:0::/root:/bin/sh", "akane:x:1000:1000::/home/akane:/bin/bash")
        listOf("Akane", "a b", "x;id", "../x", "-u", "a".repeat(33)).forEach { bad ->
            val (rc, out) = resolve(bad)
            assertEquals(bad, 3, rc)
            assertTrue(out, out.startsWith("DIE: invalid user name"))
        }
        val (rc, out) = resolve("nobody")
        assertEquals(3, rc)
        assertTrue(out, out.contains("not in"))
    }

    @Test
    fun passwdHome_usedOnlyWhenPlain() {
        passwd("akane:x:1000:1000::/srv/akane:/bin/bash")
        assertEquals(0 to "akane /srv/akane", resolve("akane"))
        passwd("akane:x:1000:1000::/srv/a\$(id):/bin/bash")
        assertEquals(0 to "akane /home/akane", resolve("akane"))
        passwd("akane:x:1000:1000::relative:/bin/bash")
        assertEquals(0 to "akane /home/akane", resolve("akane"))
    }

    private fun links(mode: String, user: String = "akane", home: String = "/home/akane"): String {
        val (rc, out) = sh(
            "_b=\$(storage_links_body); sh -c \"\$_b\" flux-links '$mode' '$user' '$home' '${chroot.absolutePath}'",
        )
        assertEquals(out, 0, rc)
        return out.lines().last { it.startsWith("FLUX_LINKS ") }
    }

    private val names = listOf("shared", "downloads", "documents", "dcim", "pictures", "music", "movies")

    @Test
    fun storageLinks_createIdempotentAndStatus() {
        File(chroot, "home/akane").mkdirs()
        File(chroot, "root").mkdirs()
        assertTrue(links("status").contains("present=0"))
        assertFalse("status never writes", File(chroot, "home/akane/storage").exists())

        assertEquals("FLUX_LINKS mode=ensure created=14 existing=0 present=1 skipped=", links("ensure"))
        val shared = File(chroot, "home/akane/storage/shared").toPath()
        assertEquals("/sdcard", Files.readSymbolicLink(shared).toString())
        assertEquals(
            "/sdcard/Download",
            Files.readSymbolicLink(File(chroot, "root/storage/downloads").toPath()).toString(),
        )
        assertEquals("FLUX_LINKS mode=ensure created=0 existing=14 present=1 skipped=", links("ensure"))
        assertEquals("FLUX_LINKS mode=status created=0 existing=14 present=1 skipped=", links("status"))
        names.forEach { assertTrue(Files.isSymbolicLink(File(chroot, "home/akane/storage/$it").toPath())) }
    }

    @Test
    fun storageLinks_neverReplaceOrFollow() {
        File(chroot, "home/akane/storage").mkdirs()
        File(chroot, "root").mkdirs()
        val foreign = File(chroot, "home/akane/storage/music").apply { writeText("mine") }
        val wrong = File(chroot, "home/akane/storage/movies").toPath()
        Files.createSymbolicLink(wrong, File("/elsewhere").toPath())
        // /root/storage is a symlink: skipped, nothing written through it.
        val victim = File(tmp, "victim").apply { mkdirs() }
        Files.createSymbolicLink(File(chroot, "root/storage").toPath(), victim.toPath())

        val line = links("ensure")
        assertTrue(line, line.contains("created=5 "))
        assertTrue(line, line.contains("present=0"))
        assertTrue(line, line.contains("/home/akane/storage/music"))
        assertTrue(line, line.contains("/home/akane/storage/movies"))
        assertTrue(line, line.contains("/root/storage"))
        assertEquals("mine", foreign.readText())
        assertEquals("/elsewhere", Files.readSymbolicLink(wrong).toString())
        assertEquals(0, victim.list()?.size ?: -1)
    }

    @Test
    fun storageLinks_symlinkedHomeSkipped() {
        val victim = File(tmp, "victim").apply { mkdirs() }
        File(chroot, "home").mkdirs()
        Files.createSymbolicLink(File(chroot, "home/akane").toPath(), victim.toPath())
        val line = links("ensure")
        assertTrue(line, line.contains("present=0"))
        assertEquals(0, victim.list()?.size ?: -1)
    }

    @Test
    fun helper_v30_wiresUserAndLinks() {
        assertTrue(helperText.contains(ChrootPaths.CHROOT_HELPER_VERSION))
        assertTrue(helperText.contains("links|links-status)"))
        assertTrue(helperText.contains("run_storage_links ensure"))
        assertFalse("no hardcoded root|flux user gate", helperText.contains("root|flux)"))
    }

    // --- flux -> akane rename (user migration block) ------------------------------------

    private fun write(rel: String, text: String) =
        File(chroot, rel).apply { parentFile?.mkdirs(); writeText(text) }

    private fun read(rel: String) = File(chroot, rel).readText()

    /** A chroot created as flux before akane: accounts, sudoers, doas, cron and a home. */
    private fun fluxOnlyRootfs() {
        passwd(
            "root:x:0:0:root:/root:/bin/bash",
            "#flux:x:9:9::/home/flux:/bin/sh",
            "flux:x:1000:1000:Flux:/home/flux:/bin/bash",
            "fluxer:x:1001:1001::/home/fluxer:/bin/bash",
        )
        write("etc/shadow", "root:*:19000:0:99999:7:::\nflux:\$6\$salt\$hash:19000:0:99999:7:::\n")
        Files.setPosixFilePermissions(
            File(chroot, "etc/shadow").toPath(),
            PosixFilePermissions.fromString("rw-r-----"),
        )
        write(
            "etc/group",
            "root:x:0:\nsudo:x:27:flux\nflux:x:1000:\naudio:x:29:fluxer,flux\nfluxer:x:1001:\n",
        )
        write("etc/gshadow", "root:*::\nsudo:*::flux\nflux:!::\naudio:*:flux:fluxer,flux\n")
        write("etc/subuid", "flux:100000:65536\n")
        write("etc/subgid", "flux:100000:65536\n")
        write("etc/sudoers.d/flux", "# flux may sudo\nflux ALL=(ALL) NOPASSWD:ALL\nfluxer ALL=(ALL) ALL\n")
        write("etc/doas.conf", "permit nopass flux as root\npermit fluxer as flux\n")
        write("var/spool/cron/crontabs/flux", "@reboot true\n")
        write("home/flux/.zshrc", "# zshrc\n")
        File(chroot, "home/flux/storage").mkdirs()
        Files.createSymbolicLink(File(chroot, "home/flux/storage/shared").toPath(), File("/sdcard").toPath())
    }

    /** Runs the real migration body with the temp rootfs as ROOT prefix. */
    private fun migrate(old: String = "flux", new: String = "akane"): Pair<Int, String> =
        sh("sh -c \"\$(user_migration_body)\" flux-migrate '$old' '$new' '${chroot.absolutePath}'")

    @Test
    fun migrationBlock_isIdenticalInSetupScript() {
        assertEquals(
            block(MIGRATION_BEGIN, MIGRATION_END),
            block(MIGRATION_BEGIN, MIGRATION_END, asset("setup_guest_chroot.sh")),
        )
    }

    @Test
    fun migration_renamesFluxToAkaneEverywhere_thenIdempotent() {
        assumeTrue("needs awk", File("/usr/bin/awk").canExecute() || File("/bin/awk").canExecute())
        fluxOnlyRootfs()
        val (rc, out) = migrate()
        assertEquals(out, 0, rc)
        assertTrue(out, out.contains("FLUX_MIGRATE result=migrated"))

        assertEquals(
            "root:x:0:0:root:/root:/bin/bash\n#flux:x:9:9::/home/flux:/bin/sh\n" +
                "akane:x:1000:1000:Flux:/home/akane:/bin/bash\nfluxer:x:1001:1001::/home/fluxer:/bin/bash\n",
            read("etc/passwd"),
        )
        assertEquals("root:*:19000:0:99999:7:::\nakane:\$6\$salt\$hash:19000:0:99999:7:::\n", read("etc/shadow"))
        assertEquals(
            "shadow mode kept",
            "rw-r-----",
            PosixFilePermissions.toString(Files.getPosixFilePermissions(File(chroot, "etc/shadow").toPath())),
        )
        assertEquals(
            "root:x:0:\nsudo:x:27:akane\nakane:x:1000:\naudio:x:29:fluxer,akane\nfluxer:x:1001:\n",
            read("etc/group"),
        )
        assertEquals("root:*::\nsudo:*::akane\nakane:!::\naudio:*:akane:fluxer,akane\n", read("etc/gshadow"))
        assertEquals("akane:100000:65536\n", read("etc/subuid"))
        assertEquals("akane:100000:65536\n", read("etc/subgid"))
        assertFalse(File(chroot, "etc/sudoers.d/flux").exists())
        assertEquals(
            "# flux may sudo\nakane ALL=(ALL) NOPASSWD:ALL\nfluxer ALL=(ALL) ALL\n",
            read("etc/sudoers.d/akane"),
        )
        assertEquals("permit nopass akane as root\npermit fluxer as flux\n", read("etc/doas.conf"))
        assertEquals("@reboot true\n", read("var/spool/cron/crontabs/akane"))

        // home/flux was moved (not copied): links inside stay links, compat link left behind.
        val home = File(chroot, "home/akane")
        assertTrue(home.isDirectory && !Files.isSymbolicLink(home.toPath()))
        assertEquals("# zshrc\n", read("home/akane/.zshrc"))
        assertEquals("/sdcard", Files.readSymbolicLink(File(home, "storage/shared").toPath()).toString())
        assertEquals("akane", Files.readSymbolicLink(File(chroot, "home/flux").toPath()).toString())
        assertTrue(File(chroot, "etc").list().orEmpty().none { it.endsWith(".flux-migrate") })

        val passwdAfter = read("etc/passwd")
        val (rc2, out2) = migrate()
        assertEquals(out2, 0, rc2)
        assertTrue(out2, out2.contains("FLUX_MIGRATE result=already"))
        assertEquals(passwdAfter, read("etc/passwd"))
    }

    @Test
    fun migration_conflictTouchesNothing_andRepairsInterruptedHome() {
        assumeTrue("needs awk", File("/usr/bin/awk").canExecute() || File("/bin/awk").canExecute())
        passwd("flux:x:1000:1000::/home/flux:/bin/bash", "akane:x:1001:1001::/home/akane:/bin/bash")
        File(chroot, "home/flux").mkdirs()
        val (rc, out) = migrate()
        assertEquals(out, 0, rc)
        assertTrue(out, out.contains("FLUX_MIGRATE result=conflict"))
        assertTrue(File(chroot, "home/flux").isDirectory)
        assertFalse(File(chroot, "home/akane").exists())

        // passwd already renamed but the home move did not happen: finish it.
        passwd("akane:x:1000:1000::/home/akane:/bin/bash")
        write("home/flux/.zshrc", "x\n")
        val (rc2, out2) = migrate()
        assertEquals(out2, 0, rc2)
        assertTrue(out2, out2.contains("FLUX_MIGRATE result=repaired"))
        assertEquals("x\n", read("home/akane/.zshrc"))
        assertEquals("akane", Files.readSymbolicLink(File(chroot, "home/flux").toPath()).toString())
    }

    @Test
    fun migration_neverMergesIntoAnExistingHome() {
        assumeTrue("needs awk", File("/usr/bin/awk").canExecute() || File("/bin/awk").canExecute())
        passwd("flux:x:1000:1000::/home/flux:/bin/bash")
        write("home/flux/.zshrc", "old\n")
        write("home/akane/.zshrc", "other\n")
        val (rc, out) = migrate()
        assertEquals(out, 0, rc)
        assertTrue(out, out.contains("FLUX_MIGRATE result=migrated"))
        assertEquals("old\n", read("home/flux/.zshrc"))
        assertEquals("other\n", read("home/akane/.zshrc"))
    }

    @Test
    fun resolve_akaneOnFluxOnlyChroot_migratesThenLogsInAsAkane() {
        assumeTrue("needs awk", File("/usr/bin/awk").canExecute() || File("/bin/awk").canExecute())
        fluxOnlyRootfs()
        // The real migrate_guest_user chroots as root; here the body runs on the ROOT prefix.
        val (rc, out) = sh(
            "migrate_guest_user() { sh -c \"\$(user_migration_body)\" m \"\$1\" \"\$2\" \"\$FLUX_CHROOT\"; }\n" +
                "USER_NAME=akane; resolve_guest_user; echo \"\$USER_NAME \$USER_HOME\"",
        )
        assertEquals(out, 0, rc)
        assertTrue(out, out.contains("FLUX_MIGRATE result=migrated"))
        assertEquals("akane /home/akane", out.lines().last())
        assertTrue(read("etc/passwd").contains("akane:x:1000:1000:Flux:/home/akane:"))
    }

    @Test
    fun resolve_akaneOnFluxOnlyChroot_fallsBackToFluxWhenMigrationCannotRun() {
        fluxOnlyRootfs()
        val passwdBefore = read("etc/passwd")
        // Real migrate_guest_user: the temp rootfs has no /bin/sh, so it cannot run.
        val (rc, out) = resolve("akane")
        assertEquals(out, 0, rc)
        assertEquals("flux /home/flux", out.lines().last())
        assertTrue(out, out.contains("using flux"))
        assertEquals(passwdBefore, read("etc/passwd"))

        // Busy mount / conflict / failure: same fallback, never DIE.
        val (rc2, out2) = sh(
            "migrate_guest_user() { echo 'FLUX_MIGRATE result=busy'; return 1; }\n" +
                "USER_NAME=akane; resolve_guest_user; echo \"\$USER_NAME \$USER_HOME\"",
        )
        assertEquals(out2, 0, rc2)
        assertEquals("flux /home/flux", out2.lines().last())

        // links-status is read-only: no rename even when it could run.
        val (rc3, out3) = sh(
            "migrate_guest_user() { echo MIGRATED; return 0; }\n" +
                "NO_MIGRATE=1; USER_NAME=akane; resolve_guest_user; echo \"\$USER_NAME \$USER_HOME\"",
        )
        assertEquals(out3, 0, rc3)
        assertFalse(out3, out3.contains("MIGRATED"))
        assertEquals("flux /home/flux", out3.lines().last())
    }

    @Test
    fun mountBusy_onlyForMountsAtOrBelowTheOldHome() {
        val (rc, _) = sh("user_migration_mount_busy '${chroot.absolutePath}' flux")
        assertEquals("nothing mounted under a temp rootfs", 1, rc)
    }

    @Test
    fun scripts_wireMigrationAndNoAkaneDefault() {
        assertFalse(asset("start_gui_chroot.sh").contains("FLUX_GUEST_USER:-akane"))
        val setup = asset("setup_guest_chroot.sh")
        val creating = setup.indexOf("progress \"Creating User")
        assertTrue(creating > 0)
        assertTrue(
            "rename before creating a user",
            setup.lastIndexOf("migrate_flux_user", creating) > setup.indexOf("migrate_flux_user() {"),
        )
        val skip = setup.indexOf(".flux_configured\" ] && ! migrate_flux_user")
        assertTrue("already-installed path renames too", skip > 0)
        assertTrue(asset("start_guest_gui.sh").contains("FLUX_MIGRATE"))
        assertTrue(helperText.contains("[ \"\$MODE\" = links ] || NO_MIGRATE=1"))
    }

    private companion object {
        const val MIGRATION_BEGIN = "# --- user migration (v3.0)"
        const val MIGRATION_END = "# --- end user migration"
    }
}
