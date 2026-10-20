package com.ivarna.fluxlinux.core.install

import com.ivarna.fluxlinux.core.system.SafeFiles
import com.ivarna.fluxlinux.core.terminal.ZshPluginSet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Host-side Oh My Zsh / plugin / theme fetches are pinned (L11). */
class ProotZshBootstrapTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("proot-zsh-bootstrap").toFile()
    }

    @After
    fun tearDown() {
        SafeFiles.deleteTree(tmp)
    }

    @Test
    fun cloneScript_fetchesPinnedCommitOnly() {
        val pin = ZshPluginSet.OMZ
        val script = ProotZshBootstrap.pinnedCloneScript(pin, "/data/x/home/flux/.oh-my-zsh")
        assertFalse("no moving-branch clone", script.contains("git clone"))
        assertTrue(script.contains("fetch -q --depth 1 '${pin.url}' ${pin.commit};"))
        assertTrue(script.contains("checkout -q --detach FETCH_HEAD"))
        assertTrue(script.contains("rev-parse HEAD)\" = ${pin.commit};"))
        assertTrue(script.startsWith("set -e; "))
    }

    @Test(expected = IllegalArgumentException::class)
    fun cloneScript_rejectsBranchNames() {
        ProotZshBootstrap.pinnedCloneScript(ZshPluginSet.GitPin(ZshPluginSet.OMZ.url, "master"), "/tmp/x")
    }

    @Test
    fun cloneScript_quotesDestination() {
        val script = ProotZshBootstrap.pinnedCloneScript(ZshPluginSet.OMZ, "/tmp/it's here")
        assertTrue(script.contains("rm -rf '/tmp/it'\\''s here';"))
    }

    /** Runs the generated script with the system git against a local repo. */
    @Test
    fun cloneScript_checksOutExactlyThePinnedCommit() {
        assumeTrue("git + bash on PATH", runs("git", "--version") && runs("bash", "--version"))
        val upstream = File(tmp, "upstream").apply { mkdirs() }
        git(upstream, "init", "-q")
        File(upstream, "oh-my-zsh.sh").writeText("first\n")
        git(upstream, "add", ".")
        git(upstream, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "one")
        val pinned = gitOut(upstream, "rev-parse", "HEAD")
        File(upstream, "oh-my-zsh.sh").writeText("moved on\n")
        git(upstream, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qam", "two")

        val url = "https://example.invalid/ohmyzsh.git"
        val dest = File(tmp, "dest")
        val script = ProotZshBootstrap.pinnedCloneScript(ZshPluginSet.GitPin(url, pinned), dest.path)
            .replace(url, upstream.path)
        assertEquals(0, bash(script))
        assertEquals("first\n", File(dest, "oh-my-zsh.sh").readText())
        assertEquals(pinned, gitOut(dest, "rev-parse", "HEAD"))

        // Unknown commit: the fetch fails, so the script exits non-zero.
        val missing = ProotZshBootstrap.pinnedCloneScript(ZshPluginSet.GitPin(url, "0".repeat(40)), dest.path)
            .replace(url, upstream.path)
        assertTrue(bash(missing) != 0)
    }

    @Test
    fun acceptIfSha256_movesOnlyMatchingDownloads() {
        val dest = File(tmp, "agnosterzak.zsh-theme").apply { writeText("old") }
        val good = File(tmp, "good.partial").apply { writeText("theme body\n") }
        val bad = File(tmp, "bad.partial").apply { writeText("tampered\n") }
        val sha = ProotZshBootstrap.sha256Hex(good)
        assertEquals(64, sha.length)

        assertFalse(ProotZshBootstrap.acceptIfSha256(bad, dest, sha))
        assertEquals("old", dest.readText())
        assertTrue(ProotZshBootstrap.acceptIfSha256(good, dest, sha))
        assertEquals("theme body\n", dest.readText())
        assertFalse(good.exists())
        assertFalse(ProotZshBootstrap.acceptIfSha256(File(tmp, "absent"), dest, sha))
    }

    @Test
    fun sha256Hex_matchesKnownVector() {
        val f = File(tmp, "abc").apply { writeText("abc") }
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ProotZshBootstrap.sha256Hex(f),
        )
    }

    /** Isolated from the developer's git config (signing, hooks, default branch). */
    private fun proc(vararg cmd: String): ProcessBuilder = ProcessBuilder(*cmd).apply {
        environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
        environment()["GIT_CONFIG_NOSYSTEM"] = "1"
    }

    private fun runs(vararg cmd: String): Boolean = runCatching {
        val p = proc(*cmd).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    private fun git(dir: File, vararg args: String) {
        assertTrue("git ${args.joinToString(" ")}", runs("git", "-C", dir.path, *args))
    }

    private fun gitOut(dir: File, vararg args: String): String {
        val p = proc("git", "-C", dir.path, *args).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(30, TimeUnit.SECONDS)
        return out
    }

    private fun bash(script: String): Int {
        val p = proc("bash", "-c", script).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        return p.exitValue()
    }
}
