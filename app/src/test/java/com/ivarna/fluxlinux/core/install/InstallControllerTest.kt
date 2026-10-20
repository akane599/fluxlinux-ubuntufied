package com.ivarna.fluxlinux.core.install

import com.ivarna.fluxlinux.core.install.InstallController.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [H2] The install outlives any screen; only an explicit cancel stops it. */
class InstallControllerTest {

    private class FakeEngine : InstallEngine {
        val starts = mutableListOf<Pair<String, String>>()
        val sinks = mutableListOf<(OnboardingInstallRunner.Progress) -> Unit>()
        var cancels = 0

        override fun start(
            distroId: String,
            theme: String,
            onProgress: (OnboardingInstallRunner.Progress) -> Unit,
        ) {
            starts += distroId to theme
            sinks += onProgress
        }

        override fun cancel() {
            cancels++
        }
    }

    private fun progress(
        pct: Int,
        log: String? = null,
        failed: Boolean = false,
        finished: Boolean = false,
        error: String? = null,
    ) = OnboardingInstallRunner.Progress(
        phaseId = "ROOTFS",
        phaseLabel = "Rootfs",
        phaseIndex = 2,
        phaseCount = 4,
        overallPercent = pct,
        detail = "detail $pct",
        logLine = log,
        failed = failed,
        finished = finished,
        errorMessage = error,
    )

    @Test
    fun start_marksRunning_andProgressAccumulates() {
        val engine = FakeEngine()
        val c = InstallController(engine)
        c.start("ubuntu", "light")

        assertEquals(listOf("ubuntu" to "light"), engine.starts)
        assertEquals(Status.Running, c.state.value.status)
        assertTrue(c.state.value.isActiveFor("ubuntu"))
        assertFalse(c.state.value.isActiveFor("debian"))

        engine.sinks[0](progress(10, log = "a"))
        engine.sinks[0](progress(20, log = "b"))
        val s = c.state.value
        assertEquals(20, s.percent)
        assertEquals("Rootfs", s.phaseLabel)
        assertEquals("a\nb\n", s.logText)
        assertEquals(Status.Running, s.status)
    }

    @Test
    fun stateSurvivesScreenResubscription_withoutCancelling() {
        val engine = FakeEngine()
        val c = InstallController(engine)
        c.start("ubuntu", "dark")
        engine.sinks[0](progress(40, log = "extracting"))

        // A recreated screen just reads the same flow again: nothing is cancelled
        // and the live progress is still there.
        val resubscribed = c.state.value
        assertEquals(0, engine.cancels)
        assertEquals(40, resubscribed.percent)
        assertEquals("extracting\n", resubscribed.logText)

        engine.sinks[0](progress(100, finished = true))
        assertEquals(Status.Succeeded, c.state.value.status)
        assertTrue(c.state.value.isActiveFor("ubuntu"))
    }

    @Test
    fun cancel_isExplicit_resetsToIdle_andDropsLateCallbacks() {
        val engine = FakeEngine()
        val c = InstallController(engine)
        c.start("ubuntu", "dark")
        c.cancel()

        assertEquals(1, engine.cancels)
        assertEquals(Status.Idle, c.state.value.status)
        // The cancelled job's late "Install cancelled" must not resurrect the run.
        engine.sinks[0](progress(0, failed = true, finished = true, error = "Install cancelled"))
        assertEquals(Status.Idle, c.state.value.status)
        assertNull(c.state.value.errorMessage)
    }

    @Test
    fun restart_newGenerationIgnoresOldRunCallbacks() {
        val engine = FakeEngine()
        val c = InstallController(engine)
        c.start("ubuntu", "dark")
        val firstRun = c.state.value.runId
        c.start("ubuntu", "dark")
        assertTrue(c.state.value.runId > firstRun)

        engine.sinks[0](progress(0, failed = true, finished = true, error = "Install cancelled"))
        assertEquals(Status.Running, c.state.value.status)

        engine.sinks[1](progress(55))
        assertEquals(55, c.state.value.percent)
    }

    @Test
    fun failure_recordsError_andClearFinishedOnlyAfterFinish() {
        val engine = FakeEngine()
        val c = InstallController(engine)
        c.start("ubuntu", "dark")

        c.clearFinished()
        assertEquals("running run is not cleared", Status.Running, c.state.value.status)

        engine.sinks[0](progress(0, log = "ERROR: boom", failed = true, finished = true, error = "boom"))
        assertEquals(Status.Failed, c.state.value.status)
        assertEquals("boom", c.state.value.errorMessage)

        c.clearFinished()
        assertEquals(Status.Idle, c.state.value.status)
        assertFalse(c.state.value.isActiveFor("ubuntu"))
    }

    @Test
    fun hostRepair_refusedWhileAnInstallRuns_orAnExtractionIsBusy() {
        val engine = FakeEngine()
        val c = InstallController(engine)
        assertTrue(InstallController.hostRepairAllowed(c.state.value, extracting = false))
        assertFalse(InstallController.hostRepairAllowed(c.state.value, extracting = true))

        c.start("debian", "dark") // e.g. Distro Settings reinstall, then Back to Home
        assertFalse(InstallController.hostRepairAllowed(c.state.value, extracting = false))

        engine.sinks[0](progress(100, finished = true))
        assertTrue(InstallController.hostRepairAllowed(c.state.value, extracting = false))
    }

    @Test
    fun homeRepair_checksTheGuardAtTapAndBeforeExtracting() {
        val src = repoFile("src/main/kotlin/com/ivarna/fluxlinux/ui/screens/HomeScreen.kt").readText()
        val repair = src.substringAfter("fun repairHost()").substringBefore("\n    }\n")
        assertTrue(src.contains("InstallController.hostRepairAllowed("))
        val tap = repair.indexOf("if (!repairAllowed())")
        val onExecutor = repair.indexOf("if (!repairAllowed())", tap + 1)
        val extract = repair.indexOf("ensureExtracted(app, force = true)")
        assertTrue(repair, tap in 0 until onExecutor && onExecutor < extract)
    }

    @Test
    fun screens_doNotCancelOnDispose() {
        // Contract: no composition-scoped runner and no cancel-on-dispose remain.
        for (rel in listOf(
            "src/main/kotlin/com/ivarna/fluxlinux/ui/onboarding/OnboardingFlowScreen.kt",
            "src/main/kotlin/com/ivarna/fluxlinux/ui/screens/InstallConfigScreen.kt",
        )) {
            val src = repoFile(rel).readText()
            assertFalse(rel, src.contains("OnboardingInstallRunner("))
            assertFalse(rel, src.contains("onDispose { runner.cancel() }"))
            assertTrue(rel, src.contains("InstallController.get("))
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
