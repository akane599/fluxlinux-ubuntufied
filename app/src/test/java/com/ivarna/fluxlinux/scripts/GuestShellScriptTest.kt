package com.ivarna.fluxlinux.scripts

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs each `*_test.sh` under `app/src/test/shell` with the host `/bin/sh`, passing the
 * assets dir. Each script sources the asset helpers it covers and exercises them
 * in a `mktemp -d` scratch dir (exit 0 = pass).
 */
class GuestShellScriptTest {

    private fun repoDir(rel: String): File {
        val cwd = File("").absoluteFile
        return listOf(
            File(cwd, rel),
            File(cwd, "app/$rel"),
            File(cwd.parentFile, rel),
            File(cwd.parentFile, "app/$rel"),
        ).firstOrNull { it.isDirectory } ?: error("missing $rel (cwd=$cwd)")
    }

    private fun run(script: File, assets: File): Pair<Int, String> {
        val proc = ProcessBuilder("/bin/sh", script.absolutePath, assets.absolutePath)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            return -1 to "$out\n(timeout)"
        }
        return proc.exitValue() to out
    }

    private fun runNamed(name: String) {
        assumeTrue("needs /bin/sh", File("/bin/sh").canExecute())
        val script = File(repoDir("src/test/shell"), name)
        assertTrue("missing ${script.path}", script.isFile)
        val (rc, out) = run(script, repoDir("src/main/assets"))
        assertTrue("$name rc=$rc\n$out", rc == 0)
    }

    @Test
    fun xfwm4PatchKeepsOtherProperties() = runNamed("xfwm4_no_compositing_test.sh")

    @Test
    fun familyRerunKeepsChosenGpuMode() = runNamed("gpu_mode_default_test.sh")

    @Test
    fun pulseGuestsUseUnixSocketNeverTcp() = runNamed("pulse_unix_socket_test.sh")

    @Test
    fun repairMigratesChrootGuestsToUnixSocketWithoutFollowingLinks() =
        runNamed("pulse_repair_chroot_test.sh")

    @Test
    fun everyShellTestIsWiredHere() {
        val wired = setOf(
            "xfwm4_no_compositing_test.sh",
            "gpu_mode_default_test.sh",
            "pulse_unix_socket_test.sh",
            "pulse_repair_chroot_test.sh",
        )
        val present = repoDir("src/test/shell").listFiles { f -> f.name.endsWith("_test.sh") }
            .orEmpty().map { it.name }.toSet()
        assertTrue("unwired shell tests: ${present - wired}", (present - wired).isEmpty())
    }
}
