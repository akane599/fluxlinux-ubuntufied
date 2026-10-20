package com.ivarna.fluxlinux.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [M4] Onboarding completion is persisted by the install runner on success,
 * so Back / rotation / process death after the install cannot re-run onboarding.
 */
class OnboardingCompletionContractTest {

    @Test
    fun everySuccessPathPersistsOnboardingCompleteBeforePostingSuccess() {
        val src = repoFile("src/main/kotlin/com/ivarna/fluxlinux/core/install/OnboardingInstallRunner.kt").readText()
        val successCalls = Regex("""\n\s*postSuccess\(""").findAll(src).toList()
        assertEquals("proot + chroot success paths", 2, successCalls.size)
        for (call in successCalls) {
            val before = src.substring(0, call.range.first).takeLast(400)
            assertTrue(before, before.contains("StateManager.setOnboardingComplete(appCtx, true)"))
        }
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
