package com.ivarna.fluxlinux.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * [M3] A stale install generation must never reach the UI: its late
 * "Install cancelled" would overwrite the new run and its Retry would cancel it.
 */
class OnboardingInstallRunnerGenerationTest {

    @Test
    fun guardGeneration_dropsCallbacksOnceANewerRunStarted() {
        val generation = AtomicInteger(0)
        val seen = mutableListOf<String>()
        val first = OnboardingInstallRunner.guardGeneration<String>(generation, generation.incrementAndGet()) {
            seen += "old:$it"
        }
        first("progress")
        val second = OnboardingInstallRunner.guardGeneration<String>(generation, generation.incrementAndGet()) {
            seen += "new:$it"
        }
        // The old job's cancellation arrives after the new start(): dropped.
        first("Install cancelled")
        second("progress")

        assertEquals(listOf("old:progress", "new:progress"), seen)
    }

    @Test
    fun guardGeneration_keepsCurrentRunCancellation() {
        // An explicit cancel() does not bump the generation, so the current run
        // still reports "Install cancelled".
        val generation = AtomicInteger(0)
        val seen = mutableListOf<String>()
        val sink = OnboardingInstallRunner.guardGeneration<String>(generation, generation.incrementAndGet()) {
            seen += it
        }
        sink("Install cancelled")
        assertEquals(listOf("Install cancelled"), seen)
    }

    @Test
    fun runner_routesEveryProgressThroughTheGuardedSink() {
        val src = repoFile("src/main/kotlin/com/ivarna/fluxlinux/core/install/OnboardingInstallRunner.kt").readText()
        assertTrue(src.contains("val guarded = guardGeneration(generation, gen)"))
        // post() must not bypass the guard by notifying directly.
        val post = src.substringAfter("private fun post(").substringBefore("\n    }\n")
        assertTrue(post, post.contains("main.post { onProgress(progress) }"))
        assertTrue(post, !post.contains("updateInstallNotification"))
    }

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        val candidates = listOf(
            File(cwd, rel),
            File(cwd, "app/$rel"),
            File(cwd.parentFile, rel),
            File(cwd.parentFile, "app/$rel"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("missing $rel (cwd=$cwd)")
    }
}
