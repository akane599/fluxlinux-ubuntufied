package com.ivarna.fluxlinux.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The runner's terminal-only / add-on wiring (device-only code paths): desktop flags reach
 * both guest scripts, desktop-only steps are gated, and phases come from the plan by id.
 */
class OptionalDesktopRunnerContractTest {

    private val src: String by lazy {
        val rel = "src/main/kotlin/com/ivarna/fluxlinux/core/install/OnboardingInstallRunner.kt"
        val cwd = File("").absoluteFile
        listOf(File(cwd, rel), File(cwd, "app/$rel")).first { it.isFile }.readText()
    }

    private fun fn(name: String): String =
        src.substringAfter("private fun $name(").substringBefore("\n    }\n")

    @Test
    fun bothRuntimes_passTheDesktopFlagsToFamilyAndCustomization() {
        val proot = fn("runProot")
        assertTrue(proot.contains("familySetupB64(appCtx, theme, distroId, installDesktop = desktop)"))
        assertTrue(proot.contains("\"FLUX_CUSTOMIZE_DESKTOP=\${if (desktop) \"1\" else \"0\"} \""))
        val chroot = fn("runChroot")
        assertTrue(chroot.contains("familySetupPayload(appCtx, theme, distroId, installDesktop = desktop)"))
        assertTrue(chroot.contains("customizationPayload(appCtx, theme, distroId, customizeDesktop = desktop)"))
    }

    @Test
    fun desktopOnlySteps_areGated() {
        for (name in listOf("runProot", "runChroot")) {
            val body = fn(name)
            assertTrue(name, body.contains("val hwOk = desktop && runHwAccelIfPresent("))
            assertTrue(name, body.contains("phaseIdx(phases, BaseDesktopInstallPlan.familyPhaseId("))
            assertTrue(name, body.contains("markDesktopComponents(distroId, desktop,"))
            assertTrue(name, body.contains("R.string.install_addon_needs_rootfs"))
            // Flags are only written through markDesktopComponents (gated on desktop).
            assertFalse(name, body.contains("\"xfce4_desktop\""))
            assertFalse(name, body.contains("\"hw_accel\""))
        }
        assertTrue(fn("runProot").contains("if (desktop && !isProotXfceInstalled("))
        assertTrue(fn("runChroot").contains("if (desktop && !TerminalLauncher.isChrootXfceInstalled("))
        val markFirstLine = fn("markDesktopComponents").substringAfter("{").trim().lineSequence().first()
        assertEquals("a terminal-only run leaves the flags alone", "if (!desktop) return", markFirstLine.trim())
    }

    @Test
    fun runKind_isCoercedForDistrosWithoutTerminalOnly_andPicksThePlan() {
        assertTrue(src.contains("val runKind = InstallKind.effective(distroId, kind)"))
        assertTrue(src.contains("BaseDesktopInstallPlan.phasesFor(method, profile.displayName, runKind)"))
    }
}
