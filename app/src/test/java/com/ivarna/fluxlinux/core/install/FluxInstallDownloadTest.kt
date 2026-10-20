package com.ivarna.fluxlinux.core.install

import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * [L25] flux_install.sh's fallback download is SHA-verified before it is moved
 * into the rootfs cache, and generic `rootfs.tar.*` names are never picked up.
 */
class FluxInstallDownloadTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("flux-install-dl").toFile()
    }

    @After
    fun tearDown() {
        SafeFiles.deleteTree(dir)
    }

    private fun script(): String {
        val cwd = File("").absoluteFile
        val f = listOf(cwd, File(cwd, "app"), cwd.parentFile, File(cwd.parentFile, "app"))
            .map { File(it, "src/main/assets/scripts/debian/proot/setup/flux_install.sh") }
            .first { it.isFile }
        return f.readText()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Runs resolve_rootfs_archive with a PATH-stubbed curl that downloads [payload]. */
    private fun resolve(payload: String, pinnedSha: String): Pair<Int, String> {
        assumeTrue(File("/bin/bash").canExecute() && File("/usr/bin/sha256sum").canExecute())
        val src = script()
        val fns = src.substring(src.indexOf("resolve_rootfs_archive() {"), src.indexOf("echo \"FluxLinux: Installing \$DISTRO...\""))
        val bin = File(dir, "bin").apply { mkdirs() }
        File(bin, "curl").apply {
            writeText("#!/bin/bash\nwhile [ \$# -gt 0 ]; do [ \"\$1\" = -o ] && { printf '%s' '$payload' > \"\$2\"; exit 0; }; shift; done\nexit 1\n")
            setExecutable(true)
        }
        val home = File(dir, "home").apply { mkdirs() }
        val harness = File(dir, "h.sh")
        harness.writeText(
            "set -u\nexport PATH='$bin':/usr/bin:/bin\nPREFIX='$dir/usr'\nHOME='$home'\n" +
                "ROOTFS_NAME=ubuntu.tar.xz\nROOTFS_URL=https://example.invalid/u.tar.xz\nROOTFS_SHA256='$pinnedSha'\n" +
                "$fns\nresolve_rootfs_archive\n",
        )
        val p = ProcessBuilder("/bin/bash", harness.absolutePath).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(10, TimeUnit.SECONDS))
        return p.exitValue() to out
    }

    private val cached get() = File(dir, "usr/var/lib/proot-distro/cache/rootfs/ubuntu.tar.xz")

    @Test
    fun badDownload_isNeverMovedIntoCache() {
        val (exit, out) = resolve("tampered", sha256("good".toByteArray()))
        assertEquals(out, 1, exit)
        assertFalse(cached.exists())
        assertFalse(File(cached.path + ".partial").exists())
    }

    @Test
    fun goodDownload_isCached() {
        val (exit, out) = resolve("good", sha256("good".toByteArray()))
        assertEquals(out, 0, exit)
        assertEquals("good", cached.readText())
    }

    @Test
    fun genericRootfsNames_areNotCandidates() {
        val src = script()
        val candidates = src.substringAfter("for candidate in").substringBefore("    do")
        assertFalse(candidates, candidates.contains("rootfs.tar.xz"))
        assertFalse(candidates, candidates.contains("rootfs.tar.gz"))
    }
}
