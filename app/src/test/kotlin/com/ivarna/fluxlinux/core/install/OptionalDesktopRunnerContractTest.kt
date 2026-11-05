package com.ivarna.fluxlinux.core.install

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The runner's per-kind behaviour lives in [InstallSteps] ([InstallStepsTest] covers every
 * kind × method). This only pins that the device-only runner branches on those steps and
 * nothing else: no InstallKind checks in the run bodies, flags straight from the steps.
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
    fun theRun_isPlannedFromTheEffectiveKind() {
        assertTrue(src.contains("val runKind = InstallKind.effective(distroId, kind)"))
        assertTrue(src.contains("val steps = InstallSteps.of(profile.method, runKind)"))
        assertTrue(src.contains("BaseDesktopInstallPlan.phasesFor(profile.method, profile.displayName, runKind)"))
    }

    @Test
    fun runBodies_branchOnlyOnTheSteps() {
        for (name in listOf("runProot", "runChroot", "markDesktopComponents")) {
            val body = fn(name)
            assertFalse("$name must not test InstallKind itself", body.contains("InstallKind."))
            assertFalse("$name must not test the kind itself", Regex("""\bkind\b""").containsMatchIn(body))
            assertFalse("$name must not test the method itself", body.contains("\"chroot\""))
        }
        assertTrue(fn("runProot").contains("installDesktop = steps.installDesktop"))
        assertTrue(fn("runProot").contains("customizeDesktop = steps.customizeDesktop"))
        assertTrue(fn("runChroot").contains("installDesktop = steps.installDesktop"))
        assertTrue(fn("runChroot").contains("customizeDesktop = steps.customizeDesktop"))
        assertTrue(fn("markDesktopComponents").contains("if (!steps.markDesktopComponents) return"))
        // Desktop flags are written only through markDesktopComponents.
        for (name in listOf("runProot", "runChroot")) {
            assertFalse(name, fn(name).contains("\"xfce4_desktop\""))
            assertFalse(name, fn(name).contains("\"hw_accel\""))
        }
    }
}
