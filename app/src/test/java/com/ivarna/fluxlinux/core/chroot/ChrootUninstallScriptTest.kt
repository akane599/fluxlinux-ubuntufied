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
 * H4 / L5: executes the real `remove_rootfs` of each chroot uninstall script
 * (sourced with FLUX_UNINSTALL_LIB=1) against a temp tree. `$BB` is a stub whose
 * `umount` edits a fake mounts table, so nothing touches real mounts or paths.
 */
class ChrootUninstallScriptTest {

    private lateinit var tmp: File
    private lateinit var root: File
    private lateinit var guest: File
    private lateinit var victim: File
    private lateinit var mounts: File
    private lateinit var bb: File

    private val scripts = listOf(
        "uninstall_guest_chroot.sh",
        "uninstall_debian13_chroot.sh",
        "uninstall_alpine_chroot.sh",
    )

    @Before
    fun setUp() {
        assumeTrue("needs a POSIX sh", File("/bin/sh").canExecute())
        tmp = Files.createTempDirectory("flux-uninstall-test").toFile()
        root = File(tmp, "root").apply { mkdirs() }
        guest = File(root, "chrootX")
        victim = File(root, "victim")
        mounts = File(tmp, "mounts")
        bb = File(tmp, "bb")
        bb.writeText(
            """
            #!/bin/sh
            cmd="${'$'}1"; shift
            case "${'$'}cmd" in
              umount)
                [ "${'$'}1" = "-l" ] && shift
                case "${'$'}1" in ${'$'}STUCK) exit 1 ;; esac
                awk -v t="${'$'}1" '${'$'}2 != t' "${'$'}FAKE_MOUNTS" > "${'$'}FAKE_MOUNTS.new" &&
                  cat "${'$'}FAKE_MOUNTS.new" > "${'$'}FAKE_MOUNTS"
                ;;
              find) exec find "${'$'}@" ;;
              *) exit 127 ;;
            esac
            """.trimIndent() + "\n",
        )
        bb.setExecutable(true)
    }

    @After
    fun tearDown() {
        if (::tmp.isInitialized) {
            SafeFiles.deleteTree(tmp)
        }
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

    private fun script(name: String) = repoFile("src/main/assets/scripts/chroot/$name")

    private fun seedGuest() {
        File(guest, "sdcard").mkdirs()
        File(guest, "proc").mkdirs()
        File(guest, "usr/bin").mkdirs()
        File(guest, "usr/bin/bash").writeText("#!")
        File(guest, ".flux_configured").writeText("ok")
        File(guest, "sdcard/photo.jpg").writeText("user data")
        victim.mkdirs()
        File(victim, "keep").writeText("keep")
    }

    /** Runs remove_rootfs in [name]; `stuck` is a sh case pattern for umount to fail on. */
    private fun removeRootfs(
        name: String,
        stuck: String = "__none__",
        mv: File? = null,
        path: String? = null,
        preCmd: String = "",
        call: String = "remove_rootfs \"\$2\"",
    ): Int {
        val stage = File(tmp, "stage").apply { mkdirs() }
        val pathPrefix = mv?.parentFile?.absolutePath?.let { "$it:" } ?: ""
        val pb = ProcessBuilder(
            "/bin/sh",
            "-c",
            ". \"$1\"; PROC_MOUNTS=\"\$FAKE_MOUNTS\"; PROBE_BASE=\"\$FAKE_STAGE\"; $preCmd $call",
            "sh",
            script(name).absolutePath,
            guest.absolutePath,
        ).directory(tmp).redirectErrorStream(true)
        pb.environment().apply {
            put("FLUX_UNINSTALL_LIB", "1")
            put("BB", bb.absolutePath)
            put("FAKE_MOUNTS", mounts.absolutePath)
            put("FAKE_STAGE", stage.absolutePath)
            put("STUCK", stuck)
            put("PATH", path ?: (pathPrefix + (get("PATH") ?: "/usr/bin:/bin")))
        }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("timeout", p.waitFor(60, TimeUnit.SECONDS))
        println(out)
        return p.exitValue()
    }

    /** A PATH dir of exec wrappers for the host tools the scripts use, minus [without]. */
    private fun toolsDir(without: Set<String>): File {
        val dir = File(tmp, "tools").apply { mkdirs() }
        val tools = listOf(
            "awk", "cat", "dirname", "find", "grep", "kill", "ls", "mkdir", "mv",
            "readlink", "rm", "sleep", "sort",
        )
        for (t in tools - without) {
            val real = listOf("/usr/bin/$t", "/bin/$t").map(::File).firstOrNull { it.canExecute() }
                ?: continue
            File(dir, t).apply {
                writeText("#!/bin/sh\nexec ${real.absolutePath} \"\$@\"\n")
                setExecutable(true)
            }
        }
        return dir
    }

    private fun tombs(): List<File> =
        root.listFiles().orEmpty().filter { it.name.startsWith("chrootX.deleting.") }

    @Test
    fun stuckMount_nothingDeleted_andFailureReported() {
        for (name in scripts) {
            seedGuest()
            mounts.writeText("/dev/fuse ${guest.absolutePath}/sdcard fuse rw 0 0\n")

            val rc = removeRootfs(name, stuck = "*/sdcard")

            assertNotEquals("$name must fail while a mount remains", 0, rc)
            assertEquals("user data", File(guest, "sdcard/photo.jpg").readText())
            assertTrue(File(guest, ".flux_uninstalling").exists())
            assertFalse(File(guest, ".flux_configured").exists())
            assertTrue("no tombstone before unmount succeeds", tombs().isEmpty())
            SafeFiles.deleteTree(root)
            root.mkdirs()
        }
    }

    @Test
    fun mountReappearingUnderTombstone_isNotDeleted() {
        // A racing ensure_mounts binds /sdcard after the unmount pass; the rename
        // carries that mount into the tombstone. A PATH-stubbed mv reports it.
        val stubs = File(tmp, "stubs").apply { mkdirs() }
        val mv = File(stubs, "mv")
        mv.writeText(
            """
            #!/bin/sh
            /bin/mv "${'$'}@" || exit ${'$'}?
            printf '/dev/fuse %s/sdcard fuse rw 0 0\n' "${'$'}2" >> "${'$'}FAKE_MOUNTS"
            """.trimIndent() + "\n",
        )
        mv.setExecutable(true)
        for (name in scripts) {
            seedGuest()
            mounts.writeText("")

            val rc = removeRootfs(name, stuck = "*.deleting.*/sdcard", mv = mv)

            assertNotEquals("$name must fail while the tombstone has a mount", 0, rc)
            val tomb = tombs().single()
            assertEquals("user data", File(tomb, "sdcard/photo.jpg").readText())
            assertFalse(guest.exists())
            SafeFiles.deleteTree(root)
            root.mkdirs()
        }
    }

    @Test
    fun stackedMounts_unmountedThenTreeRemoved() {
        for (name in scripts) {
            seedGuest()
            val g = guest.absolutePath
            mounts.writeText(
                "/dev/fuse $g/sdcard fuse rw 0 0\n" +
                    "/dev/fuse $g/sdcard fuse rw 0 0\n" +
                    "proc $g/proc proc rw 0 0\n" +
                    "/dev/block/x ${root.absolutePath} ext4 rw 0 0\n",
            )

            val rc = removeRootfs(name)

            assertEquals("$name should succeed", 0, rc)
            assertFalse(guest.exists())
            assertTrue(tombs().isEmpty())
            assertEquals("keep", File(victim, "keep").readText())
            // Only mounts beneath the rootfs were touched.
            assertEquals(
                "/dev/block/x ${root.absolutePath} ext4 rw 0 0\n",
                mounts.readText(),
            )
            SafeFiles.deleteTree(root)
            root.mkdirs()
        }
    }

    @Test
    fun staleTombstoneWithMount_keptAndFailureReported() {
        for (name in scripts) {
            seedGuest()
            val stale = File(root, "chrootX.deleting.42")
            File(stale, "sdcard").mkdirs()
            File(stale, "sdcard/photo.jpg").writeText("user data")
            mounts.writeText("/dev/fuse ${stale.absolutePath}/sdcard fuse rw 0 0\n")

            val rc = removeRootfs(name, stuck = "*.deleting.42/sdcard")

            assertNotEquals("$name: L5 failure while a tombstone remains", 0, rc)
            assertFalse("fresh rootfs still removed", guest.exists())
            assertEquals("user data", File(stale, "sdcard/photo.jpg").readText())
            SafeFiles.deleteTree(root)
            root.mkdirs()
        }
    }

    @Test
    fun awkMissing_failsClosed_nothingDeleted() {
        // H4 review: without awk (Android 8 /system/bin) mounts_under used to print
        // nothing, so the rootfs was renamed and deleted with its binds attached.
        val noAwk = toolsDir(without = setOf("awk")).absolutePath
        for (name in scripts) {
            seedGuest()
            mounts.writeText("/dev/fuse ${guest.absolutePath}/sdcard fuse rw 0 0\n")

            val rc = removeRootfs(name, path = noAwk)

            assertNotEquals("$name must fail when mounts cannot be listed", 0, rc)
            assertEquals("user data", File(guest, "sdcard/photo.jpg").readText())
            assertTrue("no tombstone", tombs().isEmpty())
            assertEquals(
                "mount table untouched",
                "/dev/fuse ${guest.absolutePath}/sdcard fuse rw 0 0\n",
                mounts.readText(),
            )
            SafeFiles.deleteTree(root)
            root.mkdirs()
        }
    }

    @Test
    fun deleteTree_refusesWhileMountStillListed_evenWithDestructiveFind() {
        // A find that would delete straight through mounts (BusyBox ignores -xdev
        // under -depth/-delete): the grep-based /proc/mounts check must stop it.
        val fakeFind = File(tmp, "fakefind").apply {
            writeText("#!/bin/sh\nrm -rf \"\$1\"\n")
            setExecutable(true)
        }
        for (name in scripts) {
            seedGuest()
            val tomb = File(root, "chrootX.deleting.7")
            check(guest.renameTo(tomb))
            mounts.writeText("/dev/fuse ${tomb.absolutePath}/sdcard fuse rw 0 0\n")

            val rc = removeRootfs(
                name,
                call = "FIND='${fakeFind.absolutePath}'; delete_tree '${tomb.absolutePath}'",
            )

            assertNotEquals("$name must refuse the delete", 0, rc)
            assertEquals("user data", File(tomb, "sdcard/photo.jpg").readText())
            SafeFiles.deleteTree(root)
            root.mkdirs()
        }
    }

    @Test
    fun plantedMarkerSymlink_andExistingTombstoneName_areNotFollowed() {
        for (name in scripts) {
            seedGuest()
            // A symlinked marker must not truncate its target.
            Files.createSymbolicLink(
                File(guest, ".flux_uninstalling").toPath(),
                File(victim, "keep").toPath(),
            )
            mounts.writeText("")
            // Pre-create this run's tombstone name: mv would move the rootfs into it.
            val rc = removeRootfs(name, preCmd = "mkdir \"\$2.deleting.\$\$\";")

            assertNotEquals("$name must refuse an existing tombstone name", 0, rc)
            assertEquals("keep", File(victim, "keep").readText())
            assertTrue(File(guest, ".flux_uninstalling").isDirectory)
            assertTrue("rootfs not moved", File(guest, "sdcard/photo.jpg").exists())
            SafeFiles.deleteTree(root)
            root.mkdirs()
        }
    }

    @Test
    fun scripts_reportFailureResultAndExitStatus() {
        for (name in scripts) {
            val src = script(name).readText()
            assertTrue(name, src.contains("result=\${_result}"))
            assertTrue(name, src.contains("_result=failure"))
            assertFalse(
                "$name: no unbounded rm -rf of the rootfs",
                Regex("rm -rf \"\\$(GUESTPATH|DEBIANPATH|ALPINEPATH)\"").containsMatchIn(src),
            )
            assertTrue(name, src.contains("-xdev -depth -delete"))
            assertTrue(name, src.contains("for _f in /system/bin/find find \"\${BB:+\$BB find}\""))
            assertFalse(name, src.contains("never descends"))
        }
    }
}
