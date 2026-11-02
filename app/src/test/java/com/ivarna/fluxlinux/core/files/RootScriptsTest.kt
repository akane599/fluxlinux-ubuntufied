package com.ivarna.fluxlinux.core.files

import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Command building, quoting and output parsing of the chroot root scripts. Script tests run
 * the real scripts with the host `sh` against temp dirs (links only inside temp fixtures;
 * cleanup via [SafeFiles.deleteTree]). Injection payloads only ever `touch`/`mkdir` a marker.
 */
class RootScriptsTest {
    private lateinit var tmp: File
    private lateinit var rootfs: File
    private lateinit var home: File
    private lateinit var cwd: File
    private lateinit var victim: File
    private lateinit var mounts: File
    private lateinit var runner: HostShellRunner
    private lateinit var scripts: RootScripts
    private lateinit var ops: ShellRootOps

    /** Names that would break naive quoting/framing; markers prove nothing was executed. */
    private val nasty = listOf(
        "it's",
        "new\nline",
        "\$(touch PWNED)",
        "`mkdir PWNED2`",
        ";touch PWNED3;",
        "-rf",
        "--",
        "sp ace",
        "back\\slash",
        "tab\tname",
        "a\"b",
        "*",
        "ünïcödé",
        "trailing\n",
        "\n12a4:0:0:0:0",
        "x\\040y",
    )

    @Before
    fun setUp() {
        assumeTrue("host sh + /proc needed", HostShellRunner.available)
        tmp = Files.createTempDirectory("root-scripts").toFile().canonicalFile
        rootfs = File(tmp, "chrootUbuntu").apply { mkdirs() }
        home = File(rootfs, "home/akane").apply { mkdirs() }
        cwd = File(tmp, "cwd").apply { mkdirs() }
        victim = Files.createTempDirectory("root-scripts-victim").toFile().canonicalFile
        File(victim, "sentinel.txt").writeText("keep me")
        mounts = File(tmp, "mounts").apply { writeText("") }
        runner = HostShellRunner(cwd)
        scripts = RootScripts(rootfs.path, HostShellRunner.hostPath, mounts.path)
        ops = ShellRootOps(runner, rootfs.path, scripts)
    }

    @After
    fun tearDown() {
        for (dir in listOfNotNull(if (::tmp.isInitialized) tmp else null, if (::victim.isInitialized) victim else null)) {
            assertTrue("cleanup $dir", SafeFiles.deleteTree(dir))
        }
    }

    private fun assertNoInjection(vararg dirs: File) {
        for (d in dirs.toList() + cwd) {
            for (m in listOf("PWNED", "PWNED2", "PWNED3")) assertFalse("$m in $d", File(d, m).exists())
        }
    }

    private fun mountLine(path: String) = "fake ${RootScripts.mountEscape(path)} tmpfs rw 0 0\n"

    // ---- pure quoting / parsing ---------------------------------------------------------

    @Test
    fun q_singleQuotesAndEscapesQuotes() {
        assertEquals("'plain'", RootScripts.q("plain"))
        assertEquals("'it'\\''s'", RootScripts.q("it's"))
        assertEquals("'\$(x) `y` \"z\"\n'", RootScripts.q("\$(x) `y` \"z\"\n"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun q_rejectsNul() {
        RootScripts.q("a\u0000b")
    }

    @Test
    fun mountEscape_roundTrips() {
        val p = "/data/a b\tc\nd\\e"
        assertEquals("/data/a\\040b\\011c\\012d\\134e", RootScripts.mountEscape(p))
        assertEquals(p, RootScripts.mountUnescape(RootScripts.mountEscape(p)))
    }

    @Test
    fun scripts_neverEmbedUnquotedPaths() {
        val evil = "${rootfs.path}/home/akane/\$(touch PWNED)'; touch PWNED3; '"
        for (s in listOf(
            scripts.list(evil),
            scripts.chain(home.path, listOf("\$(touch PWNED)", "a'b")),
            scripts.mutation(RootMutation.Delete(evil)),
            scripts.mutation(RootMutation.Move(evil, "$evil-2")),
        )) {
            assertTrue(s, s.contains(RootScripts.q(evil)) || s.contains(RootScripts.q("\$(touch PWNED)")))
            assertFalse(s, s.contains("'\$(touch PWNED)'; touch PWNED3; ''"))
        }
    }

    @Test
    fun parseStat_mapsTypes() {
        assertEquals(GuestFs.Type.DIR, RootScripts.parseStat("41ed:4096:1700000000:1000:1000")?.type)
        assertEquals(GuestFs.Type.FILE, RootScripts.parseStat("81a4:12:1700000000:0:0")?.type)
        val link = RootScripts.parseStat("a1ff:7:1700000001:0:0\n")
        assertEquals(GuestFs.Type.LINK, link?.type)
        assertEquals(1_700_000_001_000L, link?.mtimeMillis)
        assertEquals(GuestFs.Type.OTHER, RootScripts.parseStat("11a4:0:0:0:0")?.type)
        assertNull(RootScripts.parseStat("garbage"))
    }

    @Test
    fun parseList_framesNamesWithNewlinesAndRejectsSpoofs() {
        val out = ByteArrayOutputStreamBuilder()
            .text("/r/proc\n").nul()
            // A name holding "\n./fake/<fields>" stays ONE entry: names cannot contain '/'.
            .text("./a\n12a4:0:0:0:0/81a4:3:10:1:1\n")
            .text("./lnk/a1ff:3:10:0:0\n")
            .bytes(byteArrayOf('.'.code.toByte(), '/'.code.toByte(), 0xC3.toByte(), '/'.code.toByte()))
            .text("81a4:1:1:0:0\n")
            .nul()
            .text("./lnk").nul().text("../t\narget\n").nul()
            .build()
        val snap = RootScripts.parseList("/r/home", out)
        assertEquals(listOf("/r/proc"), snap.mounts)
        assertEquals(listOf("/r/home/a\n12a4:0:0:0:0", "/r/home/lnk"), snap.entries.map { it.path })
        assertEquals(GuestFs.Type.FILE, snap.entries[0].stat?.type)
        assertEquals(3L, snap.entries[0].stat?.size)
        assertEquals("../t\narget", snap.entries[1].linkTarget)
    }

    // ---- real scripts on the host shell ---------------------------------------------------

    @Test
    fun list_roundTripsNastyNamesWithoutExecutingThem() {
        for (n in nasty) File(home, n).writeText(n)
        Files.createSymbolicLink(File(home, "link it's").toPath(), File("../ta rget\nx").toPath())
        File(home, "dir\nx").mkdir()

        val snap = ops.list(home.path)

        val byName = snap.entries.associateBy { HostPaths.name(it.path) }
        assertEquals((nasty + "link it's" + "dir\nx").toSet(), byName.keys)
        for (n in nasty) {
            assertEquals(n, GuestFs.Type.FILE, byName.getValue(n).stat?.type)
            assertEquals(n, n.toByteArray().size.toLong(), byName.getValue(n).stat?.size)
        }
        assertEquals(GuestFs.Type.DIR, byName.getValue("dir\nx").stat?.type)
        assertEquals(GuestFs.Type.LINK, byName.getValue("link it's").stat?.type)
        assertEquals("../ta rget\nx", byName.getValue("link it's").linkTarget)
        assertEquals(emptyList<String>(), snap.mounts)
        assertNoInjection(home, rootfs)
    }

    @Test
    fun chain_stopsAtFirstLinkAndReportsMissing() {
        File(home, "a b").mkdir()
        Files.createSymbolicLink(File(home, "a b/l'nk").toPath(), File("/etc").toPath())
        val snap = ops.chain(rootfs.path, listOf("home", "akane", "a b", "l'nk", "passwd"))
        assertEquals(
            listOf("home", "home/akane", "home/akane/a b", "home/akane/a b/l'nk").map { "${rootfs.path}/$it" },
            snap.entries.map { it.path },
        )
        assertEquals(GuestFs.Type.LINK, snap.entries.last().stat?.type)
        assertEquals("/etc", snap.entries.last().linkTarget)

        val missing = ops.chain(home.path, listOf("nope", "deeper"))
        assertEquals(1, missing.entries.size)
        assertNull(missing.entries[0].stat)
    }

    @Test
    fun mutations_handleNastyNames() {
        for (n in nasty) {
            val p = File(home, n).path
            assertTrue("create $n", ops.mutate(RootMutation.CreateFile(p)))
            assertFalse("no clobber $n", ops.mutate(RootMutation.CreateFile(p)))
            assertTrue("move $n", ops.mutate(RootMutation.Move(p, "$p.moved")))
            assertTrue("copy $n", ops.mutate(RootMutation.Copy("$p.moved", "$p.copy")))
            assertTrue("delete $n", ops.mutate(RootMutation.Delete("$p.moved")))
            assertTrue("delete copy $n", ops.mutate(RootMutation.Delete("$p.copy")))
        }
        val d = File(home, "-dir 'x'\n").path
        assertTrue(ops.mutate(RootMutation.Mkdir(d)))
        assertTrue(File(d).isDirectory)
        assertEquals(listOf("-dir 'x'\n"), home.list()!!.toList())
        assertNoInjection(home, rootfs)
    }

    @Test
    fun delete_unlinksLinkButNeverItsTarget() {
        val link = File(home, "victim")
        Files.createSymbolicLink(link.toPath(), victim.toPath())
        val dir = File(home, "tree").apply { mkdirs() }
        Files.createSymbolicLink(File(dir, "inner").toPath(), victim.toPath())

        assertTrue(ops.mutate(RootMutation.Delete(link.path)))
        assertTrue(ops.mutate(RootMutation.Delete(dir.path)))

        assertFalse(Files.exists(link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertFalse(dir.exists())
        assertEquals("keep me", File(victim, "sentinel.txt").readText())
    }

    @Test
    fun mutations_refuseLinkedParentsAndPathsOutsideRootfs() {
        Files.createSymbolicLink(File(home, "out").toPath(), victim.toPath())
        // Parent component is a link: refused as root even if the caller got it wrong.
        assertFalse(ops.mutate(RootMutation.Delete(File(home, "out/sentinel.txt").path)))
        assertFalse(ops.mutate(RootMutation.CreateFile(File(home, "out/new").path)))
        // The rootfs itself and anything outside it.
        assertFalse(ops.mutate(RootMutation.Delete(rootfs.path)))
        assertFalse(ops.mutate(RootMutation.Delete(File(victim, "sentinel.txt").path)))
        assertFalse(ops.mutate(RootMutation.Delete("${rootfs.path}-evil")))
        assertTrue(File(victim, "sentinel.txt").exists())
        assertFalse(File(victim, "new").exists())
    }

    @Test
    fun guard_refusesAnythingTouchingAMountInsideRootfs() {
        val proc = File(rootfs, "proc").apply { mkdirs() }
        File(proc, "keep").writeText("x")
        val spaced = File(rootfs, "sp ace").apply { mkdirs() }
        File(spaced, "keep").writeText("x")
        mounts.writeText(mountLine("/") + mountLine(rootfs.path) + mountLine(proc.path) + mountLine(spaced.path))

        assertEquals(listOf(proc.path, spaced.path), ops.mounts())
        assertFalse(ops.mutate(RootMutation.Delete(proc.path))) // the mount point itself
        assertFalse(ops.mutate(RootMutation.Delete(File(proc, "keep").path))) // below it
        assertFalse(ops.mutate(RootMutation.Delete(File(spaced, "keep").path))) // escaped mount path
        assertFalse(ops.mutate(RootMutation.CreateFile(File(proc, "new").path)))
        assertTrue(File(proc, "keep").exists())
        assertTrue(File(spaced, "keep").exists())

        // A directory holding a mount point is never removed or copied recursively.
        val holder = File(home, "holder").apply { mkdirs() }
        val inner = File(holder, "m").apply { mkdirs() }
        mounts.appendText(mountLine(inner.path))
        assertFalse(ops.mutate(RootMutation.Delete(holder.path)))
        assertFalse(ops.mutate(RootMutation.Copy(holder.path, File(home, "copy").path)))
        assertTrue(inner.isDirectory)

        // Unrelated paths are fine.
        assertTrue(ops.mutate(RootMutation.CreateFile(File(home, "ok").path)))
    }

    @Test
    fun guard_failsClosedWithoutMountTable() {
        val s = RootScripts(rootfs.path, HostShellRunner.hostPath, File(tmp, "missing-mounts").path)
        val o = ShellRootOps(runner, rootfs.path, s)
        assertFalse(o.mutate(RootMutation.CreateFile(File(home, "x").path)))
        assertFalse(File(home, "x").exists())
    }

    @Test
    fun write_tempThenCommit_replacesContentAndKeepsMode() {
        val target = File(home, "notes 'x'.txt").apply { writeText("old") }
        Files.setPosixFilePermissions(target.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"))
        val temp = File(home, ".flux-saf-test.tmp").path

        assertEquals(0, runner.runWithStdin(scripts.mutation(RootMutation.WriteTemp(temp)), "new\ncontent".toByteArray()))
        assertTrue(ops.mutate(RootMutation.CommitTemp(temp, target.path)))

        assertEquals("new\ncontent", target.readText())
        assertEquals("rw-r-----", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(target.toPath())))
        assertFalse(File(temp).exists())
    }

    @Test
    fun write_neverClobbersAnExistingTempAndAbortCleansUp() {
        val temp = File(home, ".flux-saf-x.tmp").apply { writeText("someone else's") }
        assertTrue(runner.runWithStdin(scripts.mutation(RootMutation.WriteTemp(temp.path)), "data".toByteArray()) != 0)
        assertEquals("someone else's", temp.readText())

        val fresh = File(home, ".flux-saf-y.tmp")
        assertEquals(0, runner.runWithStdin(scripts.mutation(RootMutation.WriteTemp(fresh.path)), "data".toByteArray()))
        assertTrue(ops.mutate(RootMutation.AbortTemp(fresh.path)))
        assertFalse(fresh.exists())
    }

    @Test
    fun commit_refusesLinkTarget() {
        Files.createSymbolicLink(File(home, "ln").toPath(), File(victim, "sentinel.txt").toPath())
        val temp = File(home, ".flux-saf-z.tmp").path
        assertEquals(0, runner.runWithStdin(scripts.mutation(RootMutation.WriteTemp(temp)), "evil".toByteArray()))
        assertFalse(ops.mutate(RootMutation.CommitTemp(temp, File(home, "ln").path)))
        assertEquals("keep me", File(victim, "sentinel.txt").readText())
        assertFalse(File(temp).exists())
    }

    @Test
    fun list_missingDirIsAClearError() {
        val e = runCatching { ops.list(File(home, "nope").path) }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(e is RootFsException)
    }

    /** Tiny byte builder for hand-made script output. */
    private class ByteArrayOutputStreamBuilder {
        private val out = java.io.ByteArrayOutputStream()
        fun text(s: String) = apply { out.write(s.toByteArray()) }
        fun bytes(b: ByteArray) = apply { out.write(b) }
        fun nul() = apply { out.write(0) }
        fun build(): ByteArray = out.toByteArray()
    }
}
