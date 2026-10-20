package com.ivarna.fluxlinux.core.desktop

import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Locks chroot desktop readiness (guest marker only) and host X/VirGL cleanup (M12). */
class ChrootStartGuiContractTest {

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel"), File(cwd.parentFile, rel))
            .firstOrNull { it.isFile } ?: error("missing $rel (cwd=$cwd)")
    }

    private fun chroot(name: String) = repoFile("src/main/assets/scripts/chroot/$name")

    private val hostName = "start_gui_chroot.sh"
    private val host by lazy { chroot(hostName).readText() }

    /** Guest launcher -> desktop command the user shell checks before exec. */
    private val guests = mapOf(
        "start_guest_gui.sh" to "startxfce4",
        "start_alpine_gui.sh" to "startxfce4",
        "start_debian13_gui.sh" to "\$FLUX_DE_CMD",
    )

    @Test
    fun host_trapsXAndVirglBeforeStartingThem() {
        val trap = host.indexOf("trap _flux_cleanup EXIT")
        assertTrue(trap > 0)
        assertTrue(trap < host.indexOf("virgl_test_server_android --socket-path"))
        assertTrue(trap < host.indexOf("/system/bin/app_process"))
        assertTrue(host.contains("VIRGL_PID=\$!"))
        assertTrue(host.contains("XSERVER_PID=\$!"))
        assertTrue(host.contains("kill \"\$XSERVER_PID\""))
        assertTrue(host.contains("kill \"\$VIRGL_PID\""))
        assertTrue(host.contains("trap 'exit 143' TERM"))
        // The guest runs as a child (no exec), so the trap covers the whole session.
        assertFalse(host.contains("exec \"\$SU_BIN\""))
        // Preflight fails before any host side effect.
        val preflight = host.indexOf("chroot not found at")
        assertTrue(preflight in 1 until host.indexOf("pkill -f termux-x11"))
    }

    @Test
    fun guests_printReadyMarkerOnlyAfterDesktopCheck_andBeforeExec() {
        for ((name, de) in guests) {
            val src = chroot(name).readText()
            assertEquals("$name: one marker", 1, Regex("echo FLUX_DESKTOP_READY").findAll(src).count())
            val marker = src.indexOf("echo FLUX_DESKTOP_READY")
            val check = src.indexOf("if ! command -v $de >/dev/null 2>&1; then")
            assertTrue("$name: DE check", check > 0)
            assertTrue("$name: marker after DE check", marker > check)
            assertTrue("$name: marker before exec", marker < src.indexOf("exec dbus-", marker))
            assertTrue("$name: marker waits for the DE pid", src.contains("kill -0 \\\$\\\$ 2>/dev/null && echo FLUX_DESKTOP_READY"))
            assertFalse("$name: no host-socket readiness line", src.contains("host X0 socket ready"))
            assertFalse(DesktopLauncher.isDesktopReadyLine("FluxLinux: host X0 socket present"))
        }
    }

    @Test
    fun scripts_parseUnderBashShAndBusybox() {
        val shells = listOf(listOf("/bin/bash", "-n"), listOf("/bin/sh", "-n"), listOf("/usr/bin/busybox", "sh", "-n"))
            .filter { File(it[0]).canExecute() }
        assumeTrue(shells.isNotEmpty())
        for (name in guests.keys + hostName) {
            for (sh in shells) {
                val (code, out) = run(sh + chroot(name).path)
                assertEquals("${sh.joinToString(" ")} $name: $out", 0, code)
            }
        }
    }

    @Test
    fun host_missingChroot_failsBeforeAnyHostSideEffect() {
        val bash = File("/bin/bash")
        assumeTrue(bash.canExecute())
        val dir = Files.createTempDirectory("startchroot")
        try {
            val prefix = dir.resolve("usr").toFile().apply { mkdirs() }
            val (code, out) = run(
                listOf(bash.path, chroot(hostName).path, "ubuntu_chroot"),
                mapOf(
                    "PATH" to "/usr/bin:/bin",
                    "TERMUX__PREFIX" to prefix.path,
                    "TERMUX__HOME" to dir.resolve("home").toString(),
                    "TERMUX_APP__PACKAGE_NAME" to "com.example.none",
                    "CHROOT_PATH" to dir.resolve("no-such-chroot").toString(),
                ),
            )
            assertEquals(out, 1, code)
            assertTrue(out, out.contains("chroot not found at"))
            assertFalse(out, out.contains("Starting termux-x11"))
            assertFalse(out, out.contains("VirGL"))
        } finally {
            SafeFiles.deleteTree(dir.toFile())
        }
    }

    private fun run(cmd: List<String>, env: Map<String, String>? = null): Pair<Int, String> {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        if (env != null) pb.environment().apply { clear(); putAll(env) }
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue("timed out: $cmd", p.waitFor(30, TimeUnit.SECONDS))
        return p.exitValue() to out
    }
}
