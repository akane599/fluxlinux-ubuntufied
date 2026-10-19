package com.ivarna.fluxlinux.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks symlink safety: Kotlin's recursive file helpers follow symlinked
 * directories, so once guest homes carry `~/storage/shared -> /storage/emulated/0`
 * they would delete/copy/count the user's shared storage. All recursive work in
 * `:app` main code must use `core.system.SafeFiles` instead.
 */
class SymlinkSafetyContractTest {

    private fun mainKotlinDir(): File {
        val cwd = File("").absoluteFile
        val rel = "src/main/kotlin"
        val candidates = listOf(
            File(cwd, rel),
            File(cwd, "app/$rel"),
            File(cwd.parentFile, "app/$rel")
        )
        return candidates.firstOrNull { File(it, "com/ivarna/fluxlinux").isDirectory }
            ?: error("missing app/$rel (cwd=$cwd)")
    }

    private val forbidden = listOf(
        "deleteRecursively(",
        "copyRecursively(",
        "walkTopDown(",
        "walkBottomUp("
    )

    /**
     * Only exception: TermuxHostPaths walks host PREFIX subdirs (usr/bin, usr/etc,
     * usr/share, …) whose roots exclude usr/var/lib/proot-distro.
     */
    private val allowlist = setOf("TermuxHostPaths.kt")

    @Test
    fun mainCode_hasNoLinkFollowingRecursiveFileOps() {
        val root = mainKotlinDir()
        val sources = root.listFiles().orEmpty().flatMap { collectKotlin(it) }
        assertTrue("expected Kotlin sources under $root", sources.size > 50)

        val violations = sources
            .filter { it.name !in allowlist }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val hit = forbidden.firstOrNull { line.contains(it) }
                    hit?.let { "${f.relativeTo(root)}:${i + 1}: $it" }
                }
            }
        assertTrue(
            "use SafeFiles (never follows symlinks) instead of:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun safeFiles_neverRequestsLinkFollowing() {
        val safe = File(mainKotlinDir(), "com/ivarna/fluxlinux/core/system/SafeFiles.kt").readText()
        // FileVisitOption.FOLLOW_LINKS is the only way walkFileTree follows links.
        assertTrue("SafeFiles must not use FileVisitOption", !safe.contains("FileVisitOption"))
        assertTrue(safe.contains("LinkOption.NOFOLLOW_LINKS"))
    }

    /** Plain recursion over the repo source tree (contains no symlinks we create). */
    private fun collectKotlin(f: File): List<File> = when {
        f.isFile -> if (f.name.endsWith(".kt")) listOf(f) else emptyList()
        f.isDirectory -> f.listFiles().orEmpty().flatMap { collectKotlin(it) }
        else -> emptyList()
    }
}
