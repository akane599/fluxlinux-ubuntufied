package com.ivarna.fluxlinux.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnboardingConsentContractTest {

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        val candidates = listOf(
            File(cwd, rel),
            File(cwd, "app/$rel"),
            File(cwd.parentFile, rel),
            File(cwd.parentFile, "app/$rel")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("missing $rel (cwd=$cwd)")
    }

    @Test
    fun optionsPage_requiresDownloadConsentBeforeInstall() {
        val src = repoFile(
            "src/main/kotlin/com/ivarna/fluxlinux/ui/onboarding/OnboardingFlowScreen.kt"
        ).readText()
        assertTrue(src.contains("downloadConsent"))
        // 06499a1 (onboarding redesign, docs/plans/onboarding_redesign_plan.md §2.4)
        // renamed DownloadConsentRow to FluxConsentCheckboxCard.
        assertTrue("consent card", src.contains("FluxConsentCheckboxCard"))
        assertTrue(src.contains("enabled = downloadConsent"))
        assertTrue(
            "consent must disclose bypassing F-Droid checks",
            src.contains("not checked by F-Droid")
        )
        assertTrue(src.contains("downloads Linux system images"))
        assertTrue(
            "consent must start unchecked",
            src.contains("var downloadConsent by remember { mutableStateOf(false) }")
        )
        assertFalse(src.contains("var downloadConsent by remember { mutableStateOf(true) }"))
        assertTrue(src.contains("OnboardStep.Consent"))
        assertTrue(src.contains("OnboardStep.HostSetup"))
        assertTrue(src.contains("fun ConsentPage"))
        assertTrue(src.contains("fun HostSetupPage"))
        // 06499a1 replaced the embedded HostBootstrapStep with an in-page host card
        // (plan "Page 3") that drives the same TerminalLauncher.prepareHost path.
        assertTrue("host setup page runs host prep", src.contains("TerminalLauncher.prepareHost"))
    }
}
