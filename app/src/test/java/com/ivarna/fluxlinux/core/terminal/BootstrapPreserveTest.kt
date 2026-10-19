package com.ivarna.fluxlinux.core.terminal

import com.ivarna.fluxlinux.core.system.SafeFiles
import com.ivarna.fluxlinux.core.terminal.BootstrapInstaller.ASIDE_NAME
import com.ivarna.fluxlinux.core.terminal.BootstrapInstaller.AsideRecovery
import com.ivarna.fluxlinux.core.terminal.BootstrapInstaller.TreeOps
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Guests (`usr/var/lib/proot-distro`) survive an interrupted or failed re-extract:
 * a stranded `.preserved_proot_distro` is recovered, never deleted, and nothing is
 * marked extracted while the copy-back is incomplete.
 *
 * Safety: every path lives under per-test `Files.createTempDirectory` dirs. The only
 * symlink (guest `~/storage/shared`) targets a separate victim temp dir whose sentinel
 * must survive every case. Cleanup is SafeFiles.deleteTree only (never follows links).
 */
class BootstrapPreserveTest {

    private lateinit var filesDir: File
    private lateinit var victim: File
    private lateinit var victimSentinel: File

    private val homeRel = "containers/ubuntu/rootfs/home/flux"
    private val live get() = File(filesDir, "usr/var/lib/proot-distro")
    private val aside get() = File(filesDir, ASIDE_NAME)

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("flux-preserve-files").toFile()
        victim = Files.createTempDirectory("flux-preserve-victim").toFile()
        victimSentinel = File(victim, "victim-sentinel.txt").apply { writeText("keep me") }
    }

    @After
    fun tearDown() {
        SafeFiles.deleteTree(filesDir)
        SafeFiles.deleteTree(victim)
    }

    /** A guest tree with a home sentinel and `~/storage/shared -> victim`. */
    private fun guestTree(root: File, marker: String): File {
        val home = File(root, homeRel)
        home.mkdirs()
        File(home, "sentinel.txt").writeText(marker)
        val storage = File(home, "storage").apply { mkdirs() }
        Files.createSymbolicLink(File(storage, "shared").toPath(), victim.toPath())
        return root
    }

    private fun sentinelIn(root: File): String = File(root, "$homeRel/sentinel.txt").readText()

    private fun assertVictimIntact() {
        assertTrue("victim sentinel must survive", victimSentinel.isFile)
        assertEquals("keep me", victimSentinel.readText())
    }

    /** Extracted bootstrap staged under `.bootstrap_extract_tmp/usr`. */
    private fun stagedPrefix(): File {
        val tmp = File(filesDir, ".bootstrap_extract_tmp")
        File(tmp, "usr/lib").mkdirs()
        File(tmp, "usr/lib/libtermux-exec.so").writeText("elf")
        File(tmp, "usr/var/lib/dpkg").mkdirs()
        return tmp
    }

    private fun asideCopies(): List<File> =
        filesDir.listFiles().orEmpty().filter { it.name.startsWith("$ASIDE_NAME.") }

    @Test
    fun strandedAside_withNoPrefix_isRestoredAfterExtract() {
        // Killed after usr/ was deleted: only the aside remains.
        guestTree(aside, "old-home")

        assertEquals(AsideRecovery.DEFERRED, BootstrapInstaller.recoverStrandedAside(filesDir))
        assertTrue("deferred aside must be kept", aside.isDirectory)

        var marked = false
        BootstrapInstaller.promotePrefixPreservingGuests(filesDir, stagedPrefix()) { marked = true }

        assertTrue(marked)
        assertEquals("old-home", sentinelIn(live))
        assertFalse(aside.exists())
        assertTrue(SafeFiles.isSymlink(File(live, "$homeRel/storage/shared")))
        assertVictimIntact()
    }

    @Test
    fun strandedAside_withPrefixPresent_isRestoredImmediately() {
        File(filesDir, "usr/var/lib/dpkg").mkdirs()
        guestTree(aside, "old-home")

        assertEquals(AsideRecovery.RESTORED, BootstrapInstaller.recoverStrandedAside(filesDir))

        assertEquals("old-home", sentinelIn(live))
        assertFalse(aside.exists())
        assertVictimIntact()
    }

    @Test
    fun ensureExtracted_recoversStrandedAsideBeforeFastPath() {
        File(filesDir, "usr/lib").mkdirs()
        File(filesDir, "usr/lib/libtermux-exec.so").writeText("elf")
        File(filesDir, "usr/var/lib").mkdirs()
        val ctx = FakeContext(filesDir, "$filesDir/jni")
        BootstrapInstaller.markExtracted(ctx)
        guestTree(aside, "old-home")

        assertTrue(BootstrapInstaller.ensureExtracted(ctx))

        assertEquals("old-home", sentinelIn(live))
        assertFalse(aside.exists())
        assertVictimIntact()
    }

    @Test
    fun asideAndLiveBothPresent_neitherDeleted_asideRenamedWithTimestamp() {
        guestTree(live, "current-home")
        guestTree(aside, "old-home")
        val ops = TreeOps(now = { 1234L })

        assertEquals(AsideRecovery.KEPT_AWAY, BootstrapInstaller.recoverStrandedAside(filesDir, ops))

        assertEquals("current-home", sentinelIn(live))
        assertFalse(aside.exists())
        val kept = File(filesDir, "$ASIDE_NAME.1234")
        assertEquals("old-home", sentinelIn(kept))
        assertVictimIntact()
    }

    @Test
    fun preserve_neverClobbersExistingAside() {
        // An older aside exists when a re-extract preserves the live guests.
        guestTree(live, "current-home")
        guestTree(aside, "old-home")
        File(filesDir, "usr/lib").mkdirs()

        var marked = false
        BootstrapInstaller.promotePrefixPreservingGuests(filesDir, stagedPrefix(), TreeOps(now = { 77L })) {
            marked = true
        }

        assertTrue(marked)
        assertEquals("current-home", sentinelIn(live))
        assertEquals("old-home", sentinelIn(File(filesDir, "$ASIDE_NAME.77")))
        assertFalse(aside.exists())
        assertVictimIntact()
    }

    @Test
    fun incompleteCopyBack_doesNotMarkExtracted_andKeepsAside() {
        guestTree(live, "current-home")
        File(filesDir, "usr/lib").mkdirs()
        // Preserve (live -> aside) works; restore (aside -> live) rename and copy both fail.
        val ops = TreeOps(
            move = { src, dst -> dst.absolutePath != live.absolutePath && src.renameTo(dst) },
            copy = { _, _ -> false }
        )

        var marked = false
        assertThrows(IllegalStateException::class.java) {
            BootstrapInstaller.promotePrefixPreservingGuests(filesDir, stagedPrefix(), ops) { marked = true }
        }

        assertFalse("must not mark extracted after an incomplete copy-back", marked)
        assertFalse(File(filesDir, "home/.fluxlinux/bootstrap.extracted").exists())
        assertEquals("aside is the only complete copy", "current-home", sentinelIn(aside))
        assertTrue(asideCopies().isEmpty())
        assertVictimIntact()

        // Next run recovers it (prefix now present, live tree absent).
        assertEquals(AsideRecovery.RESTORED, BootstrapInstaller.recoverStrandedAside(filesDir))
        assertEquals("current-home", sentinelIn(live))
        assertVictimIntact()
    }
}
