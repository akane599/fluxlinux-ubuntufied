package com.ivarna.fluxlinux.core.files

import com.ivarna.fluxlinux.core.files.GuestDocumentPaths.DocId
import com.ivarna.fluxlinux.core.files.GuestDocumentPaths.ROOT_HOME
import com.ivarna.fluxlinux.core.files.GuestDocumentPaths.ROOT_SYSTEM
import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * HARD SAFETY RULE 2: every link lives inside per-test temp dirs; targets are either host
 * paths that do not exist, or the separate [victim] temp dir holding a sentinel.
 * Cleanup uses [SafeFiles.deleteTree] only.
 */
class GuestDocumentPathsTest {
    private lateinit var tmp: File
    private lateinit var container: File
    private lateinit var rootfs: File
    private lateinit var victim: File
    private lateinit var sentinel: File
    private lateinit var shared: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("gdp-test").toFile().canonicalFile
        container = File(tmp, "containers/ubuntu").apply { mkdirs() }
        rootfs = File(container, "rootfs").apply { mkdirs() }
        File(rootfs, "home/flux").mkdirs()
        File(rootfs, "root").mkdirs()
        victim = Files.createTempDirectory("gdp-victim").toFile().canonicalFile
        sentinel = File(victim, "sentinel.txt").apply { writeText("keep me") }
        shared = Files.createTempDirectory("gdp-shared").toFile().canonicalFile
        File(shared, "Download").mkdirs()
        File(shared, "Download/report.pdf").writeText("pdf")
    }

    @After
    fun tearDown() {
        val dirs = listOfNotNull(
            if (::tmp.isInitialized) tmp else null,
            if (::victim.isInitialized) victim else null,
            if (::shared.isInitialized) shared else null,
        )
        for (dir in dirs) assertTrue("cleanup $dir", SafeFiles.deleteTree(dir))
    }

    private fun link(at: String, target: String): File {
        val f = File(rootfs, at)
        f.parentFile.mkdirs()
        Files.createSymbolicLink(f.toPath(), File(target).toPath())
        return f
    }

    /** A guest-absolute target that must not exist on the build host. */
    private fun absentOnHost(path: String): String {
        assertFalse("test target must not exist on host: $path", File(path).exists())
        return path
    }

    // ---- id codec ------------------------------------------------------------------

    @Test
    fun docIdRoundTrips() {
        for (id in listOf("$ROOT_HOME:", "$ROOT_HOME:Documents/a b.txt", "$ROOT_SYSTEM:usr/bin")) {
            val doc = GuestDocumentPaths.parse(id)
            assertNotNull(id, doc)
            doc!!
            assertEquals(id, doc.toString())
            assertEquals(id, GuestDocumentPaths.encode(doc.rootId, doc.relPath))
        }
        val root = GuestDocumentPaths.parse("$ROOT_HOME:")!!
        assertTrue(root.isRoot)
        assertEquals("$ROOT_HOME:x", GuestDocumentPaths.childId(root, "x"))
        assertEquals("$ROOT_HOME:x/y", GuestDocumentPaths.childId(DocId(ROOT_HOME, "x"), "y"))
        assertEquals(DocId(ROOT_HOME, "x"), GuestDocumentPaths.parentOf(DocId(ROOT_HOME, "x/y")))
        assertEquals(root, GuestDocumentPaths.parentOf(DocId(ROOT_HOME, "x")))
        assertEquals("y", GuestDocumentPaths.displayName(DocId(ROOT_HOME, "x/y"), "/home/flux"))
        assertEquals("flux", GuestDocumentPaths.displayName(root, "/home/flux"))
    }

    @Test
    fun unsafeIdsAreRejected() {
        val bad = listOf(
            "$ROOT_HOME:..", "$ROOT_HOME:a/../..", "$ROOT_HOME:/etc", "$ROOT_HOME:a//b", "$ROOT_HOME:./a",
            "$ROOT_HOME:a\u0000b", "unknown:a", ":a", "no-separator", "$ROOT_SYSTEM:a/",
        )
        for (id in bad) assertNull("must reject $id", GuestDocumentPaths.parse(id))
        val root = DocId(ROOT_HOME, "")
        for (name in listOf("..", ".", "a/b", "", "a\u0000")) assertNull(GuestDocumentPaths.childId(root, name))
    }

    @Test
    fun childRelationAndUniqueNames() {
        assertTrue(GuestDocumentPaths.isChild(DocId(ROOT_HOME, ""), DocId(ROOT_HOME, "a/b")))
        assertTrue(GuestDocumentPaths.isChild(DocId(ROOT_HOME, "a"), DocId(ROOT_HOME, "a/b")))
        assertFalse(GuestDocumentPaths.isChild(DocId(ROOT_HOME, "a"), DocId(ROOT_HOME, "ab")))
        assertFalse(GuestDocumentPaths.isChild(DocId(ROOT_HOME, "a"), DocId(ROOT_SYSTEM, "a/b")))
        assertFalse(GuestDocumentPaths.isChild(DocId(ROOT_HOME, "a"), DocId(ROOT_HOME, "a")))

        val taken = setOf("a.txt", "a (1).txt", "dir.d")
        assertEquals("b.txt", GuestDocumentPaths.uniqueName("b.txt", false) { it in taken })
        assertEquals("a (2).txt", GuestDocumentPaths.uniqueName("a.txt", false) { it in taken })
        assertEquals("dir.d (1)", GuestDocumentPaths.uniqueName("dir.d", true) { it in taken })
    }

    // ---- resolver ------------------------------------------------------------------

    @Test
    fun homeRootFallsBackToRootWhenFluxHomeMissing() {
        assertEquals("/home/flux", GuestDocumentPaths.rootBase(rootfs, ROOT_HOME))
        assertEquals("/", GuestDocumentPaths.rootBase(rootfs, ROOT_SYSTEM))
        assertTrue(SafeFiles.deleteTree(File(rootfs, "home/flux")))
        assertEquals("/root", GuestDocumentPaths.rootBase(rootfs, ROOT_HOME))
        assertEquals("/home/flux/a", GuestDocumentPaths.guestPath("/home/flux", DocId(ROOT_HOME, "a")))
        assertEquals("/usr", GuestDocumentPaths.guestPath("/", DocId(ROOT_SYSTEM, "usr")))
    }

    @Test
    fun absoluteGuestLinkResolvesInsideRootfs() {
        val guestTarget = absentOnHost("/flux-guest-only-test/alternatives/editor")
        File(rootfs, guestTarget).apply { parentFile.mkdirs(); writeText("vi") }
        link("usr/bin/editor", guestTarget)
        // Chained: relative link to the absolute one.
        link("usr/local/bin/ed", "../../bin/editor")

        val direct = GuestDocumentPaths.resolve(rootfs, "/usr/bin/editor", null)
        assertEquals(File(rootfs, guestTarget).canonicalFile, direct)
        assertEquals(direct, GuestDocumentPaths.resolve(rootfs, "/usr/local/bin/ed", null))
        // The entry itself is the link, not its target.
        assertEquals(File(rootfs, "usr/bin/editor"), GuestDocumentPaths.resolveEntry(rootfs, "/usr/bin/editor", null))
    }

    @Test
    fun hostAbsoluteTargetIsReinterpretedInsideRootfsNotOnHost() {
        link("home/flux/escape", victim.absolutePath)
        val resolved = GuestDocumentPaths.resolve(rootfs, "/home/flux/escape/sentinel.txt", null)
        assertNotNull(resolved)
        assertTrue(resolved!!.toPath().startsWith(rootfs.toPath()))
        assertFalse(resolved.toPath().startsWith(victim.toPath()))
        assertFalse(resolved.exists())
    }

    @Test
    fun prootL2sHostLinksStayAllowedInsideContainer() {
        val l2s = File(container, ".l2s/.l2s.libfoo0001").apply { parentFile.mkdirs(); writeText("so") }
        link("usr/lib/libfoo.so", l2s.absolutePath)
        assertEquals(l2s.canonicalFile, GuestDocumentPaths.resolve(rootfs, "/usr/lib/libfoo.so", null))
    }

    @Test
    fun sharedStorageLinkNeedsSharedRoot() {
        link("home/flux/storage/downloads", absentOnHost("/storage/emulated/0/Download"))
        link("home/flux/storage/sd", absentOnHost("/sdcard"))
        val path = "/home/flux/storage/downloads/report.pdf"

        assertNull(GuestDocumentPaths.resolve(rootfs, path, null))
        assertEquals(File(shared, "Download/report.pdf"), GuestDocumentPaths.resolve(rootfs, path, shared))
        assertEquals(shared, GuestDocumentPaths.resolve(rootfs, "/home/flux/storage/sd", shared))
        // Climbing out of shared storage is an escape.
        assertNull(GuestDocumentPaths.resolve(rootfs, "/home/flux/storage/sd/..", shared))
    }

    @Test
    fun escapingRelativeLinksResolveToNull() {
        // Host-wise these point at container/<absent>, never at a real host path.
        link("home/flux/up", "../../../no-such-flux-test-dir")
        assertFalse(File(container, "no-such-flux-test-dir").exists())
        assertNull(GuestDocumentPaths.resolve(rootfs, "/home/flux/up", null))
        assertNull(GuestDocumentPaths.resolve(rootfs, "/home/flux/up/etc", null))
        link("usr/lib/deep", "../../../../victim")
        assertNull(GuestDocumentPaths.resolve(rootfs, "/usr/lib/deep", null))
    }

    @Test
    fun symlinkLoopsResolveToNull() {
        link("home/flux/a", "b")
        link("home/flux/b", "a")
        link("home/flux/self", absentOnHost("/home/flux/self"))
        assertNull(GuestDocumentPaths.resolve(rootfs, "/home/flux/a", null))
        assertNull(GuestDocumentPaths.resolve(rootfs, "/home/flux/self/x", null))
        // The entry is still addressable (for delete/rename), resolving only its parent.
        assertEquals(File(rootfs, "home/flux/a"), GuestDocumentPaths.resolveEntry(rootfs, "/home/flux/a", null))
    }

    // ---- delete never follows links ------------------------------------------------

    @Test
    fun deleteEntryUnlinksLinkAndNeverTouchesVictim() {
        val viaLink = link("home/flux/victim-link", victim.absolutePath)
        val viaDir = File(rootfs, "home/flux/project").apply { mkdirs() }
        File(viaDir, "file.txt").writeText("x")
        Files.createSymbolicLink(File(viaDir, "victim").toPath(), victim.toPath())
        link("home/flux/storage/downloads", absentOnHost("/storage/emulated/0/Download"))

        assertTrue(GuestDocumentPaths.deleteEntry(rootfs, "/home/flux/victim-link", shared))
        assertFalse(Files.exists(viaLink.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(GuestDocumentPaths.deleteEntry(rootfs, "/home/flux/project", shared))
        assertFalse(viaDir.exists())
        // Deleting a shared-storage link entry unlinks it; shared storage content survives.
        assertTrue(GuestDocumentPaths.deleteEntry(rootfs, "/home/flux/storage/downloads", shared))
        assertTrue(File(shared, "Download/report.pdf").isFile)

        assertTrue("victim sentinel must survive", sentinel.isFile)
        assertEquals("keep me", sentinel.readText())
    }

    @Test
    fun deleteEntryRefusesGuestRootAndEscapes() {
        assertFalse(GuestDocumentPaths.deleteEntry(rootfs, "/", shared))
        assertFalse(GuestDocumentPaths.deleteEntry(rootfs, "/home/..", shared))
        assertFalse(GuestDocumentPaths.deleteEntry(rootfs, "/home/flux/..", shared))
        assertTrue(File(rootfs, "home/flux").isDirectory)
        link("home/flux/sd", absentOnHost("/sdcard"))
        // Entry under a link that maps to the shared root: the parent resolves, but "/.."-style climbs fail.
        assertFalse(GuestDocumentPaths.deleteEntry(rootfs, "/home/flux/sd/..", shared))
        assertTrue(rootfs.isDirectory)
        assertTrue(File(shared, "Download/report.pdf").isFile)
    }
}
