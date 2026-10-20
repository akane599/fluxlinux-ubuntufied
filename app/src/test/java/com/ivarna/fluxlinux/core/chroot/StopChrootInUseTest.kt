package com.ivarna.fluxlinux.core.chroot

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
import java.util.concurrent.TimeUnit

/**
 * M16 / H4 review: runs the real `chroot_in_use` of each stop_*_gui.sh (extracted from the
 * script) against a temp rootfs. It must fail closed (report "in use", so the mounts are
 * kept) when usage cannot be determined, and prune dead session registrations.
 */
class StopChrootInUseTest {

    private lateinit var tmp: File
    private lateinit var chroot: File
    private lateinit var bb: File

    private val scripts = mapOf(
        "stop_guest_gui.sh" to "CHROOT_ROOT",
        "stop_debian13_gui.sh" to "DEBIANPATH",
        "stop_alpine_gui.sh" to "CHROOT_ROOT",
    )

    @Before
    fun setUp() {
        assumeTrue("needs a POSIX sh", File("/bin/sh").canExecute())
        tmp = Files.createTempDirectory("flux-stop-test").toFile()
        chroot = File(tmp, "chrootX").apply { mkdirs() }
        bb = File(tmp, "bb").apply {
            writeText("#!/bin/sh\nexit 127\n")
            setExecutable(true)
        }
    }

    @After
    fun tearDown() {
        if (::tmp.isInitialized) {
            SafeFiles.deleteTree(tmp)
        }
    }

    private fun script(name: String): File {
        val rel = "src/main/assets/scripts/chroot/$name"
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel")).first { it.exists() }
    }

    private fun functions(name: String): String {
        val src = script(name).readText()
        return src.substringAfter("# awk from PATH, else the root BusyBox applet.")
            .substringBefore("# --- mount lock (M16)")
    }

    private fun toolsDir(without: Set<String>): File {
        val dir = File(tmp, "tools-" + without.sorted().joinToString("-")).apply { mkdirs() }
        for (t in listOf("awk", "cat", "grep", "ls", "readlink", "rm") - without) {
            val real = listOf("/usr/bin/$t", "/bin/$t").map(::File).firstOrNull { it.canExecute() }
                ?: continue
            File(dir, t).apply {
                writeText("#!/bin/sh\nexec ${real.absolutePath} \"\$@\"\n")
                setExecutable(true)
            }
        }
        return dir
    }

    private fun inUse(name: String, variable: String, path: String): Int {
        val lib = File(tmp, "lib.sh").apply { writeText(functions(name)) }
        val pb = ProcessBuilder(
            "/bin/sh",
            "-c",
            "$variable=\"\$1\"; . \"\$2\"; chroot_in_use",
            "sh",
            chroot.absolutePath,
            lib.absolutePath,
        ).redirectErrorStream(true)
        pb.environment().apply {
            put("BB", bb.absolutePath)
            put("PATH", path)
        }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("timeout", p.waitFor(60, TimeUnit.SECONDS))
        println(out)
        return p.exitValue()
    }

    @Test
    fun noAwkAndNoReadlink_failsClosed() {
        val path = toolsDir(without = setOf("awk", "readlink")).absolutePath
        for ((name, v) in scripts) {
            assertEquals("$name must report in use when it can't tell", 0, inUse(name, v, path))
        }
    }

    @Test
    fun unusedChroot_notInUse_withOrWithoutAwk() {
        for (path in listOf(toolsDir(emptySet()), toolsDir(setOf("awk")))) {
            for ((name, v) in scripts) {
                assertEquals("$name ($path)", 1, inUse(name, v, path.absolutePath))
            }
        }
    }

    @Test
    fun deadSessionRegistration_pruned_liveOneKeepsMounts() {
        val sessions = File(chroot, ".flux_sessions").apply { mkdirs() }
        val path = toolsDir(emptySet()).absolutePath
        for ((name, v) in scripts) {
            // A pid that can't exist (above pid_max) is a dead registration.
            val dead = File(sessions, "99999999").apply { writeText("") }
            assertEquals(name, 1, inUse(name, v, path))
            assertFalse("$name: dead registration pruned", dead.exists())
        }
        // A live fluxlinux_chroot.sh session that hasn't entered the chroot yet.
        val pidFile = File(tmp, "holder.pid")
        val holder = ProcessBuilder(
            "/bin/sh",
            "-c",
            "echo \$\$ > \"\$1.tmp\" && mv \"\$1.tmp\" \"\$1\"; sleep 30; :",
            "fluxlinux_chroot.sh",
            pidFile.absolutePath,
        ).start()
        try {
            val deadline = System.currentTimeMillis() + 10_000
            while (!pidFile.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            File(sessions, pidFile.readText().trim()).writeText("")
            for ((name, v) in scripts) {
                assertEquals("$name: registered session keeps mounts", 0, inUse(name, v, path))
            }
        } finally {
            holder.destroyForcibly()
        }
    }
}
