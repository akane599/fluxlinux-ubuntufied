package com.ivarna.fluxlinux.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException

/** Audit §3: a missing script asset must fail (exit 127), never run as a successful echo. */
class ScriptManagerTest {

    private fun run(script: String): Pair<Int, String> {
        val p = ProcessBuilder("/bin/sh", "-c", script).start()
        val err = p.errorStream.bufferedReader().readText()
        p.inputStream.readBytes()
        return p.waitFor() to err
    }

    @Test
    fun presentAsset_returnsContentFromScriptsDir() {
        var opened: String? = null
        val text = ScriptManager.readScript("debian/x.sh") { path ->
            opened = path
            ByteArrayInputStream("echo hi\n".toByteArray())
        }
        assertEquals("scripts/debian/x.sh", opened)
        assertEquals("echo hi\n", text)
    }

    @Test
    fun missingAsset_exits127WithErrorOnStderr() {
        val script = ScriptManager.readScript("nope/missing.sh") { throw FileNotFoundException(it) }
        val (code, err) = run(script)
        assertEquals(127, code)
        assertTrue(err, err.contains("nope/missing.sh"))
    }

    @Test
    fun missingAsset_stopsAConcatenatedPayload() {
        val stub = ScriptManager.readScript("a.sh") { throw FileNotFoundException(it) }
        val (code, _) = run(stub + "exit 0\n")
        assertEquals(127, code)
    }

    @Test
    fun missingAsset_nameIsQuotedLiterally() {
        val (code, err) = run(ScriptManager.missingScript("x'; exit 0; '\$(id).sh"))
        assertEquals(127, code)
        assertTrue(err, err.contains("x'; exit 0; '\$(id).sh"))
    }
}
