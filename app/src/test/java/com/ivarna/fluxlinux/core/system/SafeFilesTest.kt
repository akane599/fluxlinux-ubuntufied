package com.ivarna.fluxlinux.core.system

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

/**
 * Symlinks here only point at (a) a per-test "victim" temp dir holding a sentinel,
 * or (b) a dangling Android path that does not exist on the build host.
 * Cleanup uses [SafeFiles.deleteTree] (never Kotlin's recursive delete).
 */
class SafeFilesTest {

    private lateinit var work: File
    private lateinit var victim: File
    private lateinit var sentinel: File

    @Before
    fun setUp() {
        work = Files.createTempDirectory("safefiles_work").toFile()
        victim = Files.createTempDirectory("safefiles_victim").toFile()
        sentinel = File(victim, "sentinel.txt").apply { writeText("keep me") }
        File(victim, "sub").mkdirs()
        File(victim, "sub/deep.txt").writeText("deep")
    }

    @After
    fun tearDown() {
        SafeFiles.deleteTree(work)
        SafeFiles.deleteTree(victim)
    }

    private fun link(at: File, target: Path) {
        at.parentFile?.mkdirs()
        Files.createSymbolicLink(at.toPath(), target)
    }

    private fun victimIntact() {
        assertTrue("sentinel must survive", sentinel.isFile)
        assertEquals("keep me", sentinel.readText())
        assertTrue("victim subtree must survive", File(victim, "sub/deep.txt").isFile)
    }

    private fun danglingTarget(): Path {
        val p = File("/storage/emulated/0/Download")
        assertFalse("dangling target must not exist on the host", p.exists())
        return p.toPath()
    }

    @Test
    fun deleteTree_nestedLinkToVictim_removesTreeAndSentinelSurvives() {
        val tree = File(work, "rootfs")
        File(tree, "home/flux/docs").mkdirs()
        File(tree, "home/flux/docs/a.txt").writeText("a")
        link(File(tree, "home/flux/storage/shared"), victim.toPath())

        assertTrue(SafeFiles.deleteTree(tree))

        assertFalse(Files.exists(tree.toPath(), LinkOption.NOFOLLOW_LINKS))
        victimIntact()
    }

    @Test
    fun deleteTree_rootTargetIsLink_unlinksOnly() {
        val rootLink = File(work, "shared")
        link(rootLink, victim.toPath())

        assertTrue(SafeFiles.deleteTree(rootLink))

        assertFalse(Files.exists(rootLink.toPath(), LinkOption.NOFOLLOW_LINKS))
        victimIntact()
    }

    @Test
    fun deleteTree_danglingLinks_areDeleted() {
        val tree = File(work, "rootfs")
        val nested = File(tree, "home/flux/storage/downloads")
        link(nested, danglingTarget())
        val rootDangling = File(work, "dangling")
        link(rootDangling, danglingTarget())

        assertTrue(SafeFiles.deleteTree(tree))
        assertTrue(SafeFiles.deleteTree(rootDangling))

        assertFalse(Files.exists(tree.toPath(), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(rootDangling.toPath(), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun deleteTree_missingTarget_returnsTrue() {
        assertTrue(SafeFiles.deleteTree(File(work, "nope")))
    }

    @Test
    fun copyTree_recreatesLinksAsLinksAndDoesNotCopyVictim() {
        val src = File(work, "src")
        File(src, "home/flux").mkdirs()
        File(src, "home/flux/a.txt").writeText("a")
        val exe = File(src, "bin/tool").apply { parentFile?.mkdirs(); writeText("#!/bin/sh") }
        exe.setExecutable(true, false)
        link(File(src, "home/flux/storage/shared"), victim.toPath())
        link(File(src, "home/flux/storage/downloads"), danglingTarget())
        val dst = File(work, "dst")

        assertTrue(SafeFiles.copyTree(src, dst))

        assertEquals("a", File(dst, "home/flux/a.txt").readText())
        assertTrue("exec bit preserved", File(dst, "bin/tool").canExecute())
        for (name in listOf("shared", "downloads")) {
            val s = File(src, "home/flux/storage/$name").toPath()
            val d = File(dst, "home/flux/storage/$name").toPath()
            assertTrue("$name must be a symlink", Files.isSymbolicLink(d))
            assertEquals(Files.readSymbolicLink(s), Files.readSymbolicLink(d))
        }
        // Nothing from the victim was materialised as real files in dst.
        val regularNames = mutableListOf<String>()
        SafeFiles.walkNoFollow(dst) { f, attrs -> if (attrs.isRegularFile) regularNames.add(f.name) }
        assertEquals(listOf("a.txt", "tool"), regularNames.sorted())
        victimIntact()
    }

    @Test
    fun copyTree_rootIsLink_copiesLinkItself() {
        val srcLink = File(work, "shared")
        link(srcLink, victim.toPath())
        val dst = File(work, "copied")

        assertTrue(SafeFiles.copyTree(srcLink, dst))

        assertTrue(Files.isSymbolicLink(dst.toPath()))
        assertEquals(victim.toPath(), Files.readSymbolicLink(dst.toPath()))
    }

    @Test
    fun copyTree_existingLinkInDestination_isReplacedNotWrittenThrough() {
        val src = File(work, "src")
        File(src, "storage/sub").mkdirs()
        File(src, "storage/sub/deep.txt").writeText("from src")
        val dst = File(work, "dst")
        // dst/storage is a link to the victim: copying a real dir there must not write into victim.
        link(File(dst, "storage"), victim.toPath())

        assertTrue(SafeFiles.copyTree(src, dst, overwrite = true))

        assertFalse(Files.isSymbolicLink(File(dst, "storage").toPath()))
        assertEquals("from src", File(dst, "storage/sub/deep.txt").readText())
        assertEquals("deep", File(victim, "sub/deep.txt").readText())
        victimIntact()
    }

    @Test
    fun copyTree_overwriteFalse_refusesExistingFile() {
        val src = File(work, "src").apply { mkdirs() }
        File(src, "a.txt").writeText("new")
        val dst = File(work, "dst").apply { mkdirs() }
        File(dst, "a.txt").writeText("old")

        assertFalse(SafeFiles.copyTree(src, dst, overwrite = false))
        assertEquals("old", File(dst, "a.txt").readText())
    }

    @Test
    fun copyTree_missingSource_returnsFalse() {
        assertFalse(SafeFiles.copyTree(File(work, "nope"), File(work, "dst")))
    }

    @Test
    fun walkNoFollow_doesNotVisitVictimContents() {
        val tree = File(work, "rootfs")
        File(tree, "home/flux").mkdirs()
        File(tree, "home/flux/a.txt").writeText("a")
        link(File(tree, "home/flux/storage/shared"), victim.toPath())

        val visited = mutableListOf<File>()
        var linkSeen = false
        SafeFiles.walkNoFollow(tree) { f, attrs ->
            visited += f
            if (f.name == "shared") linkSeen = attrs.isSymbolicLink && !attrs.isDirectory
        }

        assertTrue("link reported as a link", linkSeen)
        assertTrue(visited.none { it.name == "sentinel.txt" || it.name == "deep.txt" })
        assertEquals(visited.size, visited.toSet().size)
        assertTrue(visited.contains(tree))
    }

    @Test
    fun isSymlink_detectsLinksIncludingDangling() {
        val l = File(work, "l")
        link(l, danglingTarget())
        assertTrue(SafeFiles.isSymlink(l))
        assertFalse(SafeFiles.isSymlink(victim))
        assertFalse(SafeFiles.isSymlink(File(work, "missing")))
    }
}
