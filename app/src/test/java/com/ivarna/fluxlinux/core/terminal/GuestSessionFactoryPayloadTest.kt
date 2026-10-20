package com.ivarna.fluxlinux.core.terminal

import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * L1: component sessions must not share a fixed `/tmp/flux_feature.sh`.
 * Runs the real payload under the host `sh` with a temp-dir standing in for
 * guest `/tmp`.
 */
class GuestSessionFactoryPayloadTest {

    private fun b64(script: String) =
        Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))

    private fun start(payload: String): Process =
        ProcessBuilder("sh", "-c", payload).redirectErrorStream(true).start()

    @Test
    fun payload_noFixedScriptPath() {
        val p = GuestSessionFactory.componentPayload(b64("true"), false, emptyMap())
        assertFalse(p.contains("/tmp/flux_feature.sh"))
        assertTrue(p.contains("mktemp /tmp/flux_feature.XXXXXX"))
    }

    @Test
    fun payload_runsScript_keepsExitCode_passesArg_andCleansUp() {
        val dir = Files.createTempDirectory("flux_payload_test").toFile()
        try {
            val tmp = File(dir, "tmp").apply { mkdirs() }
            val out = File(dir, "out")
            val script = "printf '%s|%s\\n' \"\$FLUX_TEST_VAL\" \"\$1\" > \"\$OUT\"; exit 7\n"
            val payload = GuestSessionFactory.componentPayload(
                b64(script),
                isUninstall = true,
                extraEnv = mapOf("OUT" to out.absolutePath, "FLUX_TEST_VAL" to "it's"),
                tmpDir = tmp.absolutePath,
            )
            val proc = start(payload)
            assertTrue(proc.waitFor(30, TimeUnit.SECONDS))
            assertEquals(7, proc.exitValue())
            assertEquals("it's|uninstall", out.readText().trim())
            assertEquals(emptyList<String>(), tmp.list()?.toList())
        } finally {
            SafeFiles.deleteTree(dir)
        }
    }

    @Test
    fun concurrentSessions_getDistinctScriptPaths() {
        val dir = Files.createTempDirectory("flux_payload_concurrent").toFile()
        try {
            val tmp = File(dir, "tmp").apply { mkdirs() }
            val outA = File(dir, "a")
            val outB = File(dir, "b")
            // A holds its script open while B is written and run.
            val scriptA = "sleep 1\necho \"A \$0\" > \"\$OUT\"\n"
            val scriptB = "echo \"B \$0\" > \"\$OUT\"\n"
            val a = start(
                GuestSessionFactory.componentPayload(
                    b64(scriptA), false, mapOf("OUT" to outA.absolutePath), tmp.absolutePath,
                ),
            )
            val b = start(
                GuestSessionFactory.componentPayload(
                    b64(scriptB), false, mapOf("OUT" to outB.absolutePath), tmp.absolutePath,
                ),
            )
            assertTrue(a.waitFor(30, TimeUnit.SECONDS))
            assertTrue(b.waitFor(30, TimeUnit.SECONDS))
            assertEquals(0, a.exitValue())
            assertEquals(0, b.exitValue())
            val lineA = outA.readText().trim()
            val lineB = outB.readText().trim()
            assertTrue(lineA, lineA.startsWith("A "))
            assertTrue(lineB, lineB.startsWith("B "))
            assertNotEquals(lineA.removePrefix("A "), lineB.removePrefix("B "))
            assertEquals(emptyList<String>(), tmp.list()?.toList())
        } finally {
            SafeFiles.deleteTree(dir)
        }
    }
}
