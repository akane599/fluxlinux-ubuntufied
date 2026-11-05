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
        for (name in listOf("runProot", "runChroot")) {
            val body = fn(name)
            assertFalse("$name must not test InstallKind itself", body.contains("InstallKind."))
            assertFalse("$name must not test the kind itself", Regex("""\bkind\b""").containsMatchIn(body))
            assertFalse("$name must not test the method itself", body.contains("\"chroot\""))
            assertTrue(name, body.contains("installDesktop = steps.installDesktop"))
            assertTrue(name, body.contains("customizeDesktop = steps.customizeDesktop"))
            // Desktop state is written only through the two unit-tested recorders
            // (UbuntuDesktopTest runs them for failed, cancelled and successful runs).
            assertFalse(name, body.contains("\"xfce4_desktop\""))
            assertFalse(name, body.contains("\"hw_accel\""))
            assertFalse(name, body.contains("UbuntuDesktop.mark"))
            val marker = body.indexOf("beforeFamilyStep(appCtx, distroId, steps)")
            val record = body.indexOf("recordDesktopComponents(appCtx, distroId, steps,")
            val success = body.indexOf("postSuccess(onProgress")
            assertTrue("$name: marker before the family step, record on the success path", marker in 0 until record)
            assertTrue(name, record < success)
        }
        // Chroot: the marker is set right before the family script runs, after its payload.
        val chroot = fn("runChroot")
        assertTrue(
            chroot.indexOf("beforeFamilyStep(appCtx, distroId, steps)") <
                chroot.indexOf("runChrootGuestBlocking(familyPayload"),
        )
        val proot = fn("runProot")
        assertTrue(
            proot.indexOf("beforeFamilyStep(appCtx, distroId, steps)") <
                proot.indexOf("arrayOf(bash, installScript, profile.prootName, setupB64)"),
        )
    }
}
