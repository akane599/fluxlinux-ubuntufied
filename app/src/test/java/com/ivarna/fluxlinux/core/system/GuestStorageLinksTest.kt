package com.ivarna.fluxlinux.core.system

import com.ivarna.fluxlinux.core.terminal.FakeContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Symlink safety (story HARD SAFETY RULE 2): every path lives under per-test
 * [Files.createTempDirectory] dirs; link targets are dangling guest paths asserted
 * absent on the host, or a "victim" temp dir with a sentinel. Cleanup never follows links.
 */
class GuestStorageLinksTest {

    private lateinit var filesDir: Path
    private lateinit var rootfs: Path
    private lateinit var victim: Path
    private lateinit var sentinel: Path

    @Before
    fun setUp() {
        for (link in GuestStorageLinks.LINKS) {
            assertFalse("target must not exist on host: ${link.target}", File(link.target).exists())
        }
        filesDir = Files.createTempDirectory("guest_storage_links")
        rootfs = filesDir.resolve("usr/var/lib/proot-distro/containers/ubuntu/rootfs")
        Files.createDirectories(rootfs)
        victim = Files.createTempDirectory("guest_storage_victim")
        sentinel = Files.write(victim.resolve("sentinel.txt"), "keep".toByteArray())
    }

    @After
    fun tearDown() {
        deleteNoFollow(filesDir)
        deleteNoFollow(victim)
    }

    private fun mkHome(rel: String): Path = Files.createDirectories(rootfs.resolve(rel))

    private fun storage(home: String): Path = rootfs.resolve(home).resolve("storage")

    private fun assertAllLinks(home: String) {
        for (link in GuestStorageLinks.LINKS) {
            val p = storage(home).resolve(link.name)
            assertTrue("$home/${link.name} must be a symlink", Files.isSymbolicLink(p))
            assertEquals(Paths.get(link.target), Files.readSymbolicLink(p))
        }
    }

    private fun assertVictimUntouched() {
        assertEquals(listOf(sentinel), Files.list(victim).use { it.toList() })
        assertEquals("keep", String(Files.readAllBytes(sentinel)))
    }

    @Test
    fun linksTable_matchesPinnedTargets() {
        assertEquals(
            listOf(
                "shared" to "/storage/emulated/0",
                "downloads" to "/storage/emulated/0/Download",
                "documents" to "/storage/emulated/0/Documents",
                "dcim" to "/storage/emulated/0/DCIM",
                "pictures" to "/storage/emulated/0/Pictures",
                "music" to "/storage/emulated/0/Music",
                "movies" to "/storage/emulated/0/Movies",
            ),
            GuestStorageLinks.LINKS.map { it.name to it.target }
        )
    }

    @Test
    fun ensureIn_createsAllLinksInBothHomes() {
        mkHome("home/flux")
        mkHome("root")

        val report = GuestStorageLinks.ensureIn(rootfs.toFile())

        assertEquals(GuestStorageLinks.Report(14, 0, emptyList()), report)
        for (home in listOf("home/flux", "root")) {
            assertTrue(Files.isDirectory(storage(home), NOFOLLOW_LINKS))
            assertFalse(Files.isSymbolicLink(storage(home)))
            assertAllLinks(home)
        }
    }

    @Test
    fun ensureIn_secondCall_createsNothing() {
        mkHome("home/flux")
        mkHome("root")
        GuestStorageLinks.ensureIn(rootfs.toFile())

        val report = GuestStorageLinks.ensureIn(rootfs.toFile())

        assertEquals(GuestStorageLinks.Report(0, 14, emptyList()), report)
        assertAllLinks("home/flux")
        assertAllLinks("root")
    }

    @Test
    fun ensureIn_preExistingRealDownloadsDir_untouchedAndSkipped() {
        mkHome("home/flux")
        mkHome("root")
        val downloads = Files.createDirectories(storage("home/flux").resolve("downloads"))
        val file = Files.write(downloads.resolve("photo.jpg"), "data".toByteArray())

        val report = GuestStorageLinks.ensureIn(rootfs.toFile())

        assertEquals(listOf("home/flux/storage/downloads"), report.skipped)
        assertEquals(13, report.created)
        assertFalse(Files.isSymbolicLink(downloads))
        assertTrue(Files.isDirectory(downloads, NOFOLLOW_LINKS))
        assertEquals("data", String(Files.readAllBytes(file)))
        assertAllLinks("root")
    }

    @Test
    fun ensureIn_differentlyTargetedLink_leftAlone() {
        mkHome("home/flux")
        val other = "/flux-guest-storage-test/elsewhere/Music"
        assertFalse(File(other).exists())
        Files.createDirectories(storage("home/flux"))
        val music = storage("home/flux").resolve("music")
        Files.createSymbolicLink(music, Paths.get(other))

        val report = GuestStorageLinks.ensureIn(rootfs.toFile())

        assertEquals(listOf("home/flux/storage/music"), report.skipped)
        assertEquals(6, report.created)
        assertEquals(Paths.get(other), Files.readSymbolicLink(music))
    }

    @Test
    fun ensureIn_missingFluxHome_notCreated() {
        mkHome("root")

        val report = GuestStorageLinks.ensureIn(rootfs.toFile())

        assertEquals(GuestStorageLinks.Report(7, 0, emptyList()), report)
        assertFalse(Files.exists(rootfs.resolve("home"), NOFOLLOW_LINKS))
        assertAllLinks("root")
    }

    @Test
    fun ensureIn_storageIsFile_skipsHome() {
        mkHome("home/flux")
        mkHome("root")
        val storageFile = Files.write(storage("home/flux"), "not a dir".toByteArray())

        val report = GuestStorageLinks.ensureIn(rootfs.toFile())

        assertEquals(listOf("home/flux/storage"), report.skipped)
        assertEquals(7, report.created)
        assertTrue(Files.isRegularFile(storageFile, NOFOLLOW_LINKS))
        assertEquals("not a dir", String(Files.readAllBytes(storageFile)))
        assertAllLinks("root")
    }

    @Test
    fun ensureIn_storageIsSymlink_skipsHomeWithoutWritingThroughIt() {
        mkHome("home/flux")
        Files.createSymbolicLink(storage("home/flux"), victim)

        val report = GuestStorageLinks.ensureIn(rootfs.toFile())

        assertEquals(GuestStorageLinks.Report(0, 0, listOf("home/flux/storage")), report)
        assertEquals(victim, Files.readSymbolicLink(storage("home/flux")))
        assertVictimUntouched()
    }

    @Test
    fun ensureIn_symlinkedHome_isNotFollowed() {
        mkHome("home")
        Files.createSymbolicLink(rootfs.resolve("home/flux"), victim)
        Files.createSymbolicLink(rootfs.resolve("root"), victim)

        val report = GuestStorageLinks.ensureIn(rootfs.toFile())

        assertEquals(GuestStorageLinks.Report(0, 0, emptyList()), report)
        assertVictimUntouched()
    }

    @Test
    fun ensure_and_linksPresent_useDistroRootfs() {
        val ctx = FakeContext(filesDir.toFile(), "/fake/jni")
        assertFalse(GuestStorageLinks.linksPresent(ctx))
        mkHome("home/flux")
        assertFalse(GuestStorageLinks.linksPresent(ctx))

        val report = GuestStorageLinks.ensure(ctx)

        assertEquals(GuestStorageLinks.Report(7, 0, emptyList()), report)
        assertTrue(GuestStorageLinks.linksPresent(ctx))
        assertAllLinks("home/flux")
    }

    @Test
    fun ensure_missingRootfsOrUnknownDistro_returnsNull() {
        val ctx = FakeContext(filesDir.toFile(), "/fake/jni")
        assertNull(GuestStorageLinks.ensure(ctx, "debian"))
        assertNull(GuestStorageLinks.ensure(ctx, "no-such-distro"))
        assertFalse(GuestStorageLinks.linksPresent(ctx, "debian"))
    }

    /** Test-local cleanup: never follows a symlink; a link is deleted as a link. */
    private fun deleteNoFollow(root: Path) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
