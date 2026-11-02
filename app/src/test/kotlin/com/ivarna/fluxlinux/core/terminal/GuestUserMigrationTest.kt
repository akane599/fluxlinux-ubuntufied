package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import android.content.ContextWrapper
import com.ivarna.fluxlinux.core.system.SafeFiles
import com.ivarna.fluxlinux.core.terminal.GuestUserMigration.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GuestUserMigrationTest {

    private lateinit var tmp: Path
    private lateinit var rootfs: Path
    private lateinit var victim: Path

    private val nofollow = LinkOption.NOFOLLOW_LINKS

    private val passwd = """
        root:x:0:0:root:/root:/bin/bash
        fluxbox:x:999:999::/home/fluxbox:/usr/sbin/nologin
        flux:x:1000:1000::/home/flux:/bin/zsh
        nobody:x:65534:65534:nobody:/nonexistent:/usr/sbin/nologin

    """.trimIndent()

    private val group = """
        root:x:0:
        sudo:x:27:flux
        audio:x:29:pulse,flux,fluxbox
        fluxbox:x:999:
        video:x:44:fluxbox
        flux:x:1000:

    """.trimIndent()

    private val shadow = """
        root:*:20000:0:99999:7:::
        fluxbox:!:20000::::::
        flux:${'$'}y${'$'}hash:20000:0:99999:7:::

    """.trimIndent()

    private val gshadow = """
        root:*::
        sudo:*::flux
        audio:*:flux:pulse,flux
        flux:!::

    """.trimIndent()

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("guest-user-migration")
        rootfs = tmp.resolve("rootfs")
        // Temp stand-in for shared storage; the links inside the home point here.
        victim = Files.createDirectories(tmp.resolve("victim/emulated/0"))
        Files.write(victim.resolve("photo.jpg"), byteArrayOf(1, 2, 3))
    }

    @After
    fun tearDown() {
        SafeFiles.deleteTree(tmp.toFile())
    }

    private fun write(rel: String, text: String, mode: String? = null): Path {
        val p = rootfs.resolve(rel)
        Files.createDirectories(p.parent)
        Files.write(p, text.toByteArray())
        if (mode != null) Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(mode))
        return p
    }

    private fun read(rel: String) = String(Files.readAllBytes(rootfs.resolve(rel)))

    private fun mode(rel: String) =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(rootfs.resolve(rel), nofollow))

    private fun home(user: String): Path = rootfs.resolve("home/$user")

    /** A full legacy install: accounts, home with storage links, sudoers. */
    private fun legacyInstall() {
        write("etc/passwd", passwd, "rw-r--r--")
        write("etc/group", group, "rw-r--r--")
        write("etc/shadow", shadow, "rw-r-----")
        write("etc/gshadow", gshadow, "rw-r-----")
        write("etc/subuid", "flux:100000:65536\n")
        write("etc/subgid", "flux:100000:65536\n")
        write("etc/sudoers.d/flux", "flux ALL=(ALL) NOPASSWD:ALL\n", "r--r-----")
        write("home/flux/.zsh_history", "cd /home/flux\n")
        write("home/flux/.config/xfce4/panel.xml", "<x/>")
        Files.createDirectories(home("flux").resolve("storage"))
        Files.createSymbolicLink(home("flux").resolve("storage/shared"), victim)
        Files.createSymbolicLink(home("flux").resolve("storage/dcim"), victim.resolve("DCIM")) // dangling
        Files.createDirectories(rootfs.resolve("home/fluxbox"))
    }

    private fun assertMigrated() {
        assertEquals(
            passwd.replace("flux:x:1000:1000::/home/flux:", "akane:x:1000:1000::/home/akane:"),
            read("etc/passwd"),
        )
        assertEquals(
            """
            root:x:0:
            sudo:x:27:akane
            audio:x:29:pulse,akane,fluxbox
            fluxbox:x:999:
            video:x:44:fluxbox
            akane:x:1000:

            """.trimIndent(),
            read("etc/group"),
        )
        assertEquals(shadow.replace("\nflux:", "\nakane:"), read("etc/shadow"))
        assertEquals(
            """
            root:*::
            sudo:*::akane
            audio:*:akane:pulse,akane
            akane:!::

            """.trimIndent(),
            read("etc/gshadow"),
        )
        assertEquals("akane:100000:65536\n", read("etc/subuid"))
        assertEquals("akane:100000:65536\n", read("etc/subgid"))
        assertTrue(Files.isDirectory(home("akane"), nofollow))
        assertTrue(Files.isSymbolicLink(home("flux")))
        assertEquals(Paths.get("akane"), Files.readSymbolicLink(home("flux")))
        assertEquals("cd /home/flux\n", read("home/akane/.zsh_history"))
        assertTrue(Files.isDirectory(rootfs.resolve("home/fluxbox"), nofollow))
    }

    @Test
    fun freshMigration_renamesAccountsHomeAndSudoers() {
        legacyInstall()

        assertEquals(Outcome.MIGRATED, GuestUserMigration.migrate(rootfs))

        assertMigrated()
        assertEquals("rw-r--r--", mode("etc/passwd"))
        assertEquals("rw-r-----", mode("etc/shadow"))
        assertEquals("rw-r-----", mode("etc/gshadow"))
        assertFalse(Files.exists(rootfs.resolve("etc/sudoers.d/flux"), nofollow))
        assertEquals("akane ALL=(ALL) NOPASSWD:ALL\n", read("etc/sudoers.d/akane"))
        assertEquals("r--r-----", mode("etc/sudoers.d/akane"))
        // No temp files left behind.
        val leftovers = Files.newDirectoryStream(rootfs.resolve("etc")).use { s -> s.map { it.fileName.toString() } }
        assertTrue(leftovers.toString(), leftovers.none { it.contains(".flux-migrate") })
    }

    @Test
    fun secondRun_isNoOp() {
        legacyInstall()
        GuestUserMigration.migrate(rootfs)
        val before = listOf("etc/passwd", "etc/group", "etc/shadow", "etc/gshadow").map { read(it) }
        val mtime = Files.getLastModifiedTime(rootfs.resolve("etc/passwd"))

        assertEquals(Outcome.ALREADY_MIGRATED, GuestUserMigration.migrate(rootfs))

        assertEquals(before, listOf("etc/passwd", "etc/group", "etc/shadow", "etc/gshadow").map { read(it) })
        assertEquals(mtime, Files.getLastModifiedTime(rootfs.resolve("etc/passwd")))
        assertMigrated()
    }

    @Test
    fun storageLinks_moveAsLinks_targetsUntouched() {
        legacyInstall()

        GuestUserMigration.migrate(rootfs)

        val shared = home("akane").resolve("storage/shared")
        val dcim = home("akane").resolve("storage/dcim")
        assertTrue(Files.isSymbolicLink(shared))
        assertEquals(victim, Files.readSymbolicLink(shared))
        assertTrue(Files.isSymbolicLink(dcim))
        assertEquals(victim.resolve("DCIM"), Files.readSymbolicLink(dcim))
        assertEquals(listOf("photo.jpg"), Files.newDirectoryStream(victim).use { s -> s.map { it.fileName.toString() } })
        assertEquals(3, Files.readAllBytes(victim.resolve("photo.jpg")).size)
    }

    @Test
    fun missingShadowAndGshadow_isFine() {
        write("etc/passwd", passwd)
        write("etc/group", group)
        Files.createDirectories(home("flux"))

        assertEquals(Outcome.MIGRATED, GuestUserMigration.migrate(rootfs))

        assertTrue(read("etc/passwd").contains("\nakane:x:1000:1000::/home/akane:/bin/zsh\n"))
        assertTrue(read("etc/group").contains("\nakane:x:1000:\n"))
        assertFalse(Files.exists(rootfs.resolve("etc/shadow"), nofollow))
        assertFalse(Files.exists(rootfs.resolve("etc/gshadow"), nofollow))
    }

    @Test
    fun ownerUnreadableShadow_isReadAndKeepsItsMode() {
        legacyInstall()
        Files.setPosixFilePermissions(rootfs.resolve("etc/shadow"), PosixFilePermissions.fromString("---------"))

        assertEquals(Outcome.MIGRATED, GuestUserMigration.migrate(rootfs))

        assertEquals("---------", mode("etc/shadow"))
        Files.setPosixFilePermissions(rootfs.resolve("etc/shadow"), PosixFilePermissions.fromString("rw-------"))
        assertTrue(read("etc/shadow").contains("\nakane:"))
    }

    @Test
    fun nearNames_andForeignGroupsNamedFlux_areUntouched() {
        // The flux group is NOT the user's primary group here (gid 1000 is "users2").
        write("etc/passwd", "fluxd:x:998:998::/home/fluxd:/bin/sh\nflux:x:1000:1000::/home/flux:/bin/sh\n")
        write("etc/group", "fluxd:x:998:fluxdx\nflux:x:500:xflux,flux\nusers2:x:1000:\n")
        Files.createDirectories(home("flux"))

        GuestUserMigration.migrate(rootfs)

        assertEquals("fluxd:x:998:998::/home/fluxd:/bin/sh\nakane:x:1000:1000::/home/akane:/bin/sh\n", read("etc/passwd"))
        assertEquals("fluxd:x:998:fluxdx\nflux:x:500:xflux,akane\nusers2:x:1000:\n", read("etc/group"))
    }

    @Test
    fun customHomeField_isKept() {
        write("etc/passwd", "flux:x:1000:1000:Flux User:/srv/flux:/bin/sh")
        GuestUserMigration.migrate(rootfs)
        // No trailing newline in, none out.
        assertEquals("akane:x:1000:1000:Flux User:/srv/flux:/bin/sh", read("etc/passwd"))
    }

    @Test
    fun doasRule_identityRenamed() {
        write("etc/passwd", passwd)
        write("etc/doas.conf", "permit nopass flux\n# permit flux\npermit keepenv fluxbox as flux cmd flux\n", "rw-r--r--")

        GuestUserMigration.migrate(rootfs)

        assertEquals("permit nopass akane\n# permit flux\npermit keepenv fluxbox as flux cmd flux\n", read("etc/doas.conf"))
        assertEquals("rw-r--r--", mode("etc/doas.conf"))
    }

    @Test
    fun sudoersNearNamesAndComments_untouched() {
        write("etc/passwd", passwd)
        write("etc/sudoers.d/other", "# flux is our user\nfluxbox ALL=(ALL) ALL\nflux ALL=(ALL) NOPASSWD:ALL\n", "r--r-----")

        GuestUserMigration.migrate(rootfs)

        assertEquals("# flux is our user\nfluxbox ALL=(ALL) ALL\nakane ALL=(ALL) NOPASSWD:ALL\n", read("etc/sudoers.d/other"))
        assertEquals("r--r-----", mode("etc/sudoers.d/other"))
    }

    @Test
    fun perUserSpoolEntries_renamed() {
        write("etc/passwd", passwd)
        write("var/lib/AccountsService/users/flux", "[User]\n")
        write("var/spool/cron/crontabs/flux", "* * * * * true\n")
        write("var/mail/flux", "")
        write("var/mail/fluxbox", "")

        GuestUserMigration.migrate(rootfs)

        for (rel in listOf("var/lib/AccountsService/users", "var/spool/cron/crontabs", "var/mail")) {
            assertFalse(rel, Files.exists(rootfs.resolve("$rel/flux"), nofollow))
            assertTrue(rel, Files.isRegularFile(rootfs.resolve("$rel/akane"), nofollow))
        }
        assertTrue(Files.exists(rootfs.resolve("var/mail/fluxbox")))
    }

    @Test
    fun concurrentRuns_neverFail_exactlyOneMigrates() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(10) { round ->
                rootfs = tmp.resolve("rootfs$round")
                legacyInstall()
                val barrier = CyclicBarrier(4)
                val outcomes = (1..4).map {
                    pool.submit(Callable { barrier.await(); GuestUserMigration.migrate(rootfs) })
                }.map { it.get(30, TimeUnit.SECONDS) }

                assertTrue(outcomes.toString(), outcomes.none { it == Outcome.FAILED })
                assertEquals(outcomes.toString(), 1, outcomes.count { it == Outcome.MIGRATED })
                assertEquals(3, outcomes.count { it == Outcome.ALREADY_MIGRATED })
                assertMigrated()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun staleTempFromInterruptedRun_isRemoved() {
        legacyInstall()
        write("etc/.passwd.flux-migrate.deadbeef", "half")

        assertEquals(Outcome.MIGRATED, GuestUserMigration.migrate(rootfs))

        assertFalse(Files.exists(rootfs.resolve("etc/.passwd.flux-migrate.deadbeef"), nofollow))
        assertMigrated()
    }

    // --- interrupted / partial states -------------------------------------------------

    @Test
    fun resume_homeMovedLinkMissing_passwdNotRewritten() {
        legacyInstall()
        Files.move(home("flux"), home("akane"))

        assertEquals(Outcome.MIGRATED, GuestUserMigration.migrate(rootfs))

        assertMigrated()
    }

    @Test
    fun resume_homeMovedAndLinked_accountsHalfWritten() {
        legacyInstall()
        Files.move(home("flux"), home("akane"))
        Files.createSymbolicLink(home("flux"), Paths.get("akane"))
        // Crash after gshadow and group were written, before shadow/passwd.
        write("etc/gshadow", gshadow.replace("sudo:*::flux", "sudo:*::akane")
            .replace("audio:*:flux:pulse,flux", "audio:*:akane:pulse,akane").replace("\nflux:!", "\nakane:!"))
        write("etc/group", group.replace("sudo:x:27:flux", "sudo:x:27:akane")
            .replace("pulse,flux,fluxbox", "pulse,akane,fluxbox").replace("\nflux:x:1000:", "\nakane:x:1000:"))

        assertEquals(Outcome.MIGRATED, GuestUserMigration.migrate(rootfs))

        assertMigrated()
    }

    @Test
    fun resume_gshadowWrittenButGroupNot_stillRenamesConsistently() {
        legacyInstall()
        write("etc/gshadow", gshadow.replace("sudo:*::flux", "sudo:*::akane")
            .replace("audio:*:flux:pulse,flux", "audio:*:akane:pulse,akane").replace("\nflux:!", "\nakane:!"))

        assertEquals(Outcome.MIGRATED, GuestUserMigration.migrate(rootfs))

        assertMigrated()
    }

    @Test
    fun resume_passwdCommittedButHomeNotMoved() {
        legacyInstall()
        write("etc/passwd", passwd.replace("flux:x:1000:1000::/home/flux:", "akane:x:1000:1000::/home/akane:"))

        assertEquals(Outcome.MIGRATED, GuestUserMigration.migrate(rootfs))

        assertTrue(Files.isDirectory(home("akane"), nofollow))
        assertEquals(Paths.get("akane"), Files.readSymbolicLink(home("flux")))
        assertTrue(Files.isSymbolicLink(home("akane").resolve("storage/shared")))
    }

    @Test
    fun freshAkaneInstall_getsNoCompatLink() {
        write("etc/passwd", "root:x:0:0::/root:/bin/sh\nakane:x:1000:1000::/home/akane:/bin/sh\n")
        Files.createDirectories(home("akane"))

        assertEquals(Outcome.ALREADY_MIGRATED, GuestUserMigration.migrate(rootfs))

        assertFalse(Files.exists(home("flux"), nofollow))
    }

    @Test
    fun akaneHomeAlreadyExists_noMerge_bothKept() {
        legacyInstall()
        write("home/akane/mine.txt", "keep")

        assertEquals(Outcome.MIGRATED, GuestUserMigration.migrate(rootfs))

        assertTrue(Files.isDirectory(home("flux"), nofollow))
        assertFalse(Files.isSymbolicLink(home("flux")))
        assertEquals("keep", read("home/akane/mine.txt"))
        assertFalse(Files.exists(home("akane").resolve(".zsh_history"), nofollow))
        assertTrue(read("etc/passwd").contains("\nakane:x:1000:1000::/home/akane:"))
    }

    @Test
    fun bothUsersInPasswd_conflict_nothingTouched() {
        legacyInstall()
        val both = passwd + "akane:x:1001:1001::/home/akane:/bin/sh\n"
        write("etc/passwd", both)

        assertEquals(Outcome.CONFLICT, GuestUserMigration.migrate(rootfs))

        assertEquals(both, read("etc/passwd"))
        assertTrue(Files.isDirectory(home("flux"), nofollow))
        assertFalse(Files.exists(home("akane"), nofollow))
    }

    @Test
    fun noFluxUser_orNoRootfs_notApplicable() {
        assertEquals(Outcome.NOT_APPLICABLE, GuestUserMigration.migrate(rootfs))
        write("etc/passwd", "root:x:0:0::/root:/bin/sh\n")
        assertEquals(Outcome.NOT_APPLICABLE, GuestUserMigration.migrate(rootfs))
    }

    @Test
    fun symlinkedHomeDir_isNotFollowed() {
        write("etc/passwd", passwd)
        val outside = Files.createDirectories(tmp.resolve("outside/flux"))
        Files.createDirectories(rootfs)
        Files.createSymbolicLink(rootfs.resolve("home"), outside.parent)

        GuestUserMigration.migrate(rootfs)

        assertTrue(Files.isDirectory(outside, nofollow))
        assertFalse(Files.exists(tmp.resolve("outside/akane"), nofollow))
    }

    @Test
    fun nonUbuntuProotIds_areNoOp() {
        // Every id but "ubuntu" returns before the context is ever used.
        for (id in listOf("ubuntu_chroot", "debian", "kali", "alpine", "")) {
            assertFalse(id, GuestUserMigration.migrateIfNeeded(throwingContext(), id))
        }
    }

    @Test
    fun ubuntuId_migratesTheProotContainerUnderFilesDir() {
        rootfs = tmp.resolve("files/usr/var/lib/proot-distro/containers/ubuntu/rootfs")
        legacyInstall()
        val ctx = object : ContextWrapper(null) {
            override fun getFilesDir(): File = tmp.resolve("files").toFile()
        }

        assertTrue(GuestUserMigration.migrateIfNeeded(ctx, "ubuntu"))
        assertMigrated()
        assertFalse(GuestUserMigration.migrateIfNeeded(ctx, "ubuntu"))
    }

    private fun throwingContext(): Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = error("context must not be touched")
    }
}
