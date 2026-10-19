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

    /** Kotlin `File.walk()` / `File.walk(FileWalkDirection.…)`; not unrelated `x.walk(a, b)` helpers. */
    private val fileWalk = Regex("""\.walk\(\s*(\)|FileWalkDirection)""")

    private fun hitIn(line: String): String? =
        forbidden.firstOrNull { line.contains(it) } ?: fileWalk.find(line)?.let { ".walk(" }

    /**
     * Only exception: TermuxHostPaths.applyPackageToExtractedPrefix walks host PREFIX
     * subdirs (usr/bin, usr/etc, usr/share, …) whose roots exclude
     * usr/var/lib/proot-distro. The rest of that file is checked like any other.
     */
    private val allowedFile = "TermuxHostPaths.kt"
    private val allowedFunction = "fun applyPackageToExtractedPrefix("

    /** 0-based line range of [allowedFunction]'s body (up to the next `fun`). */
    private fun allowedLines(lines: List<String>): IntRange {
        val start = lines.indexOfFirst { it.contains(allowedFunction) }
        if (start < 0) return IntRange.EMPTY
        val next = (start + 1 until lines.size).firstOrNull { lines[it].contains(Regex("""\bfun\s""")) }
        return start until (next ?: lines.size)
    }

    @Test
    fun mainCode_hasNoLinkFollowingRecursiveFileOps() {
        val root = mainKotlinDir()
        val sources = root.listFiles().orEmpty().flatMap { collectKotlin(it) }
        assertTrue("expected Kotlin sources under $root", sources.size > 50)

        val violations = sources.flatMap { f ->
            val lines = f.readLines()
            val allowed = if (f.name == allowedFile) allowedLines(lines) else IntRange.EMPTY
            lines.mapIndexedNotNull { i, line ->
                if (i in allowed) return@mapIndexedNotNull null
                hitIn(line)?.let { "${f.relativeTo(root)}:${i + 1}: $it" }
            }
        }
        assertTrue(
            "use SafeFiles (never follows symlinks) instead of:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun detector_flagsFileWalkButNotUnrelatedWalkHelpers() {
        assertTrue(hitIn("root.walk().forEach { }") == ".walk(")
        assertTrue(hitIn("root.walk(FileWalkDirection.BOTTOM_UP)") == ".walk(")
        assertTrue(hitIn("ctx.walk(ctx.rootfs, ctx.rootfs, path, followLast = true)") == null)
    }

    @Test
    fun allowlist_coversOnlyApplyPackageToExtractedPrefix() {
        val lines = listOf(
            "fun other() { a.walkTopDown() }",
            "    fun applyPackageToExtractedPrefix(filesDir: File): Int {",
            "        root.walkTopDown().forEach { }",
            "    }",
            "    private fun later() { b.walkTopDown() }"
        )
        assertTrue(allowedLines(lines) == 1..3)
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
