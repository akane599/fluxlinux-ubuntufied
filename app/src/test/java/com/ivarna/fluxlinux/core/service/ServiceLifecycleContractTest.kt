package com.ivarna.fluxlinux.core.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * M18: keep-alive services must not be sticky. Their work (install runner, desktop, terminal
 * sessions) dies with the process, so a sticky restart only shows a false notification.
 * `dataSync` services must also stop on the Android 15 FGS time limit.
 */
class ServiceLifecycleContractTest {

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.isFile } ?: error("missing $rel (cwd=$cwd)")
    }

    private fun service(name: String): String =
        repoFile("src/main/kotlin/com/ivarna/fluxlinux/core/service/$name.kt").readText()

    @Test
    fun keepAliveServices_areNeverSticky() {
        for (name in listOf("BaseInstallService", "DesktopSessionService", "AppTerminalService")) {
            val src = service(name)
            assertFalse("$name must not return START_STICKY", Regex("""\bSTART_STICKY\b""").containsMatchIn(src))
            assertTrue("$name returns START_NOT_STICKY", src.contains("START_NOT_STICKY"))
        }
    }

    @Test
    fun nullIntent_stopsInsteadOfPromoting() {
        for (name in listOf("BaseInstallService", "DesktopSessionService")) {
            val src = service(name)
            val start = src.indexOf("override fun onStartCommand")
            val nullCheck = src.indexOf("if (intent == null)", start)
            assertTrue("$name checks a null intent", nullCheck > start)
            val firstForeground = src.indexOf("startForeground", start)
            val firstEnter = src.indexOf("enterForeground(", start).let { if (it < 0) Int.MAX_VALUE else it }
            assertTrue(
                "$name handles a null intent before entering the foreground",
                nullCheck < minOf(firstForeground.takeIf { it >= 0 } ?: Int.MAX_VALUE, firstEnter),
            )
        }
    }

    @Test
    fun dataSyncServices_stopOnFgsTimeout() {
        val manifest = repoFile("src/main/AndroidManifest.xml").readText()
        for (name in listOf("BaseInstallService")) {
            assertTrue(
                "$name is a dataSync FGS in the manifest",
                Regex("""\.core\.service\.$name"\s+[^>]*foregroundServiceType="dataSync"""").containsMatchIn(manifest),
            )
            assertTrue(
                "$name overrides onTimeout(startId, fgsType)",
                service(name).contains("override fun onTimeout(startId: Int, fgsType: Int)"),
            )
        }
    }

    @Test
    fun baseInstallStop_isNotGatedOnlyOnRunning() {
        val src = service("BaseInstallService")
        val stop = src.substring(src.indexOf("fun stop(context: Context)"))
        assertFalse("stop() must not return early on !running alone", stop.contains("if (!running.get()) return"))
        assertTrue("stop() covers a start() not yet created", stop.contains("startRequested"))
    }
}
