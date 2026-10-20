package com.ivarna.fluxlinux.core.terminal

import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * [M1] Host prefix extraction is serialised: while one extraction (e.g. "Repair
 * host") holds [BootstrapInstaller.extractLock], terminal/desktop/onboarding
 * preparation and other extractions wait instead of racing over `usr/`.
 */
class BootstrapExtractLockTest {

    private lateinit var dir: File
    private lateinit var ctx: FakeContext

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("flux-extract-lock").toFile()
        File(dir, "usr/lib").mkdirs()
        File(dir, "usr/lib/libtermux-exec.so").writeText("fake")
        File(dir, "home/.fluxlinux").mkdirs()
        File(dir, "home/.fluxlinux/bootstrap.extracted").writeText(
            "${BootstrapInstaller.EXTRACT_VERSION}|${TermuxHostPaths.PACKAGE}",
        )
        File(dir, "home/.fluxlinux/setup_termux.done").writeText("ok")
        ctx = FakeContext(dir, "$dir/lib")
        HostScriptDeployer.markDeployed(ctx)
    }

    @After
    fun tearDown() {
        SafeFiles.deleteTree(dir)
    }

    private fun assertWaitsForLock(call: () -> Boolean) {
        val result = AtomicReference<Boolean?>(null)
        val done = CountDownLatch(1)
        BootstrapInstaller.extractLock.lock()
        try {
            Thread {
                result.set(call())
                done.countDown()
            }.start()
            assertFalse("ran while another extraction held the lock", done.await(300, TimeUnit.MILLISECONDS))
        } finally {
            BootstrapInstaller.extractLock.unlock()
        }
        assertTrue("never resumed after the lock was released", done.await(5, TimeUnit.SECONDS))
        assertEquals(true, result.get())
    }

    @Test
    fun prepareHostBlocking_waitsForARunningExtraction() {
        assertWaitsForLock { TerminalLauncher.prepareHostBlocking(ctx) }
    }

    @Test
    fun ensureExtracted_waitsForARunningExtraction() {
        assertWaitsForLock { BootstrapInstaller.ensureExtracted(ctx) }
    }

    @Test
    fun busy_isFalseOnTheHappyPath() {
        assertTrue(BootstrapInstaller.ensureExtracted(ctx))
        assertFalse(BootstrapInstaller.busy.value)
    }

    @Test
    fun homeRepair_runsOnTheLauncherExecutor() {
        val src = File(
            listOf("src/main", "app/src/main").map { File(it) }.first { it.isDirectory },
            "kotlin/com/ivarna/fluxlinux/ui/screens/HomeScreen.kt",
        ).readText()
        val repair = src.substringAfter("fun repairHost()").substringBefore("\n    }\n")
        assertTrue(repair.contains("TerminalLauncher.executor.execute"))
        assertFalse(repair.contains("Thread {"))
    }
}
