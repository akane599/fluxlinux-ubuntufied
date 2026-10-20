package com.ivarna.fluxlinux.core.chroot

import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * M2 / L6: executes functions of `setup_guest_chroot.sh` (sourced with
 * FLUX_SETUP_LIB=1) in a temp dir. `$BB` is a stub: `mount`/`umount` edit a
 * fake mounts table, `tar` runs the host tar. No real mounts are touched.
 */
class ChrootSetupScriptTest {

    private lateinit var tmp: File
    private lateinit var guest: File
    private lateinit var mounts: File
    private lateinit var mountLog: File
    private lateinit var bb: File
    private lateinit var archive: File

    @Before
    fun setUp() {
        assumeTrue("needs a POSIX sh", File("/bin/sh").canExecute())
        tmp = Files.createTempDirectory("flux-setup-test").toFile()
        guest = File(tmp, "chrootX")
        mounts = File(tmp, "mounts").apply { writeText("") }
        mountLog = File(tmp, "mount.log").apply { writeText("") }
        bb = File(tmp, "bb")
        bb.writeText(
            """
            #!/bin/sh
            cmd="${'$'}1"; shift
            case "${'$'}cmd" in
              umount)
                [ "${'$'}1" = "-l" ] && shift
                case "${'$'}1" in ${'$'}STUCK) exit 1 ;; esac
                # Drop one layer per call, like the kernel does for stacked mounts.
                awk -v t="${'$'}1" '!d && ${'$'}2 == t { d = 1; next } { print }' \
                  "${'$'}FAKE_MOUNTS" > "${'$'}FAKE_MOUNTS.new" &&
                  cat "${'$'}FAKE_MOUNTS.new" > "${'$'}FAKE_MOUNTS"
                ;;
              mount)
                echo "mount ${'$'}*" >> "${'$'}MOUNT_LOG"
                for last; do :; done
                echo "x ${'$'}last x rw 0 0" >> "${'$'}FAKE_MOUNTS"
                ;;
              tar) exec tar "${'$'}@" ;;
              *) exit 127 ;;
            esac
            """.trimIndent() + "\n",
        )
        bb.setExecutable(true)

        val src = File(tmp, "src").apply { mkdirs() }
        File(src, "usr/bin").mkdirs()
        File(src, "usr/bin/bash").writeText("#!full")
        File(src, "etc").mkdirs()
        File(src, "etc/os-release").writeText("ID=test")
        archive = File(tmp, "rootfs.tar.gz")
        val tar = ProcessBuilder("tar", "czf", archive.absolutePath, "-C", src.absolutePath, ".")
            .redirectErrorStream(true).start()
        tar.inputStream.readBytes()
        assumeTrue("needs host tar", tar.waitFor(30, TimeUnit.SECONDS) && tar.exitValue() == 0)
    }

    @After
    fun tearDown() {
        if (::tmp.isInitialized) SafeFiles.deleteTree(tmp)
    }

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        return listOf(
            File(cwd, rel),
            File(cwd, "app/$rel"),
            File(cwd.parentFile, rel),
            File(cwd.parentFile, "app/$rel"),
        ).firstOrNull { it.exists() } ?: error("missing $rel (cwd=$cwd)")
    }

    private fun run(snippet: String, stuck: String = "__none__"): Int {
        val script = repoFile("src/main/assets/scripts/chroot/setup_guest_chroot.sh")
        val pb = ProcessBuilder(
            "/bin/sh",
            "-c",
            ". \"$1\"; PROC_MOUNTS=\"\$FAKE_MOUNTS\"; GUESTPATH=\"$2\"; $snippet",
            "sh",
            script.absolutePath,
            guest.absolutePath,
        ).directory(tmp).redirectErrorStream(true)
        pb.environment().apply {
            put("FLUX_SETUP_LIB", "1")
            put("BB", bb.absolutePath)
            put("FAKE_MOUNTS", mounts.absolutePath)
            put("MOUNT_LOG", mountLog.absolutePath)
            put("STUCK", stuck)
            put("ARCHIVE", archive.absolutePath)
        }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("timeout", p.waitFor(60, TimeUnit.SECONDS))
        println(out)
        return p.exitValue()
    }

    @Test
    fun partialExtraction_isReExtracted_andMarked() {
        // Interrupted earlier: bash landed, the rest (etc/os-release) did not.
        File(guest, "usr/bin").mkdirs()
        File(guest, "usr/bin/bash").writeText("#!partial")

        assertEquals(0, run("extract_file \"\$GUESTPATH\" \"\$ARCHIVE\""))

        assertEquals("ID=test", File(guest, "etc/os-release").readText())
        assertEquals("#!full", File(guest, "usr/bin/bash").readText())
        assertTrue(File(guest, ".flux_extract_ok").exists())
    }

    @Test
    fun completedExtraction_isSkipped() {
        File(guest, "usr/bin").mkdirs()
        File(guest, "usr/bin/bash").writeText("#!installed")
        File(guest, ".flux_extract_ok").writeText("sha")

        assertEquals(0, run("extract_file \"\$GUESTPATH\" \"\$ARCHIVE\""))

        assertEquals("#!installed", File(guest, "usr/bin/bash").readText())
        assertFalse(File(guest, "etc/os-release").exists())
    }

    @Test
    fun legacyConfiguredInstall_isSkipped() {
        File(guest, "usr/bin").mkdirs()
        File(guest, "usr/bin/bash").writeText("#!installed")
        File(guest, ".flux_configured").writeText("")

        assertEquals(0, run("extract_file \"\$GUESTPATH\" \"\$ARCHIVE\""))

        assertFalse(File(guest, "etc/os-release").exists())
    }

    @Test
    fun leftoverMount_blocksExtraction() {
        guest.mkdirs()
        mounts.writeText("/dev/fuse ${guest.absolutePath}/sdcard fuse rw 0 0\n")

        val rc = run("extract_file \"\$GUESTPATH\" \"\$ARCHIVE\"", stuck = "*/sdcard")

        assertNotEquals(0, rc)
        assertFalse(File(guest, "etc/os-release").exists())
        assertFalse(File(guest, ".flux_extract_ok").exists())
    }

    @Test
    fun cleanupMounts_loopsUntilStackedLayersAreGone() {
        val g = guest.absolutePath
        mounts.writeText(
            "/dev/fuse $g/sdcard fuse rw 0 0\n" +
                "/dev/fuse $g/sdcard fuse rw 0 0\n" +
                "/dev/fuse $g/sdcard fuse rw 0 0\n" +
                "proc $g/proc proc rw 0 0\n" +
                "/dev/block/x /data ext4 rw 0 0\n",
        )

        assertEquals(0, run("cleanup_mounts"))

        assertEquals("/dev/block/x /data ext4 rw 0 0\n", mounts.readText())
    }

    @Test
    fun bindIfMissing_doesNotStackOnAMountedTarget() {
        val dst = File(guest, "sdcard").absolutePath
        mounts.writeText("/dev/fuse $dst fuse rw 0 0\n")

        assertEquals(0, run("bind_if_missing /sdcard \"$dst\" && mount_type_if_missing proc \"\$GUESTPATH/proc\""))

        val log = mountLog.readText()
        assertFalse("already-mounted target re-bound: $log", log.contains("--bind"))
        assertTrue("missing proc mounted: $log", log.contains("-t proc"))
    }
}
