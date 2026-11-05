package com.ivarna.fluxlinux.core.install

import org.junit.Assert.assertEquals
import org.junit.Test

/** The install FGS says "with a warning" and shows the warning instead of "Install complete". */
class InstallNotificationContentTest {

    private fun progress(
        finished: Boolean = false,
        failed: Boolean = false,
        warning: String? = null,
        phaseLabel: String = "Installing XFCE",
        detail: String = "apt",
    ) = OnboardingInstallRunner.Progress(
        phaseId = "CUSTOM",
        phaseLabel = phaseLabel,
        phaseIndex = 3,
        phaseCount = 4,
        overallPercent = if (finished) 100 else 70,
        detail = detail,
        failed = failed,
        finished = finished,
        warning = warning,
    )

    private fun content(p: OnboardingInstallRunner.Progress) =
        OnboardingInstallRunner.notificationContent(p, fallbackText = "Ubuntu terminal", warningLabel = "WARN")

    @Test
    fun finishedWithAWarning_saysSoAndShowsIt() {
        assertEquals(
            "WARN" to "shell setup didn't finish",
            content(progress(finished = true, warning = "shell setup didn't finish", detail = "Environment ready")),
        )
    }

    @Test
    fun cleanFinish_failureAndProgress_areUnchanged() {
        assertEquals(
            "Install complete" to "Environment ready",
            content(progress(finished = true, detail = "Environment ready")),
        )
        assertEquals("Install failed" to "boom", content(progress(finished = true, failed = true, detail = "boom")))
        assertEquals("Installing XFCE" to "apt", content(progress()))
        assertEquals("Installing…" to "Ubuntu terminal", content(progress(phaseLabel = "", detail = "")))
    }

    @Test
    fun aWarningOnAnUnfinishedOrFailedUpdate_isIgnored() {
        assertEquals("Installing XFCE" to "apt", content(progress(warning = "w")))
        assertEquals("Install failed" to "boom", content(progress(finished = true, failed = true, warning = "w", detail = "boom")))
    }
}
