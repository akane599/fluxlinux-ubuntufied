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

/** Locks the proot start_gui.sh readiness/cleanup (M12), bwrap shim guard (L23) and perms pass (L24). */
class ProotStartGuiContractTest {

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel"), File(cwd.parentFile, rel))
            .firstOrNull { it.isFile } ?: error("missing $rel (cwd=$cwd)")
    }

    private val startRel = "src/main/assets/scripts/debian/proot/start/start_gui.sh"
    private val stopRel = "src/main/assets/scripts/debian/proot/stop/stop_gui.sh"
    private val start by lazy { repoFile(startRel).readText() }

    /** The single-quoted script handed to the guest shell by proot-distro login. */
    private val guest by lazy {
        val open = "\$GUEST_SHELL -c '"
        val from = start.indexOf(open) + open.length
        val to = start.indexOf("\n  '\n  GUEST_RC=\$?")
        assertTrue("guest block not found", from > open.length && to > from)
        start.substring(from, to)
    }

    @Test
    fun readiness_prootUsesGuestMarkerOnly_chrootKeepsLegacyMarkers() {
        val hostLine = "FluxLinux: host X0 socket ready (X server PID=123)"
        assertFalse(DesktopLauncher.isDesktopReadyLine(hostLine, "proot"))
        assertTrue(DesktopLauncher.isDesktopReadyLine("FLUX_DESKTOP_READY", "proot"))
        assertTrue(DesktopLauncher.isDesktopReadyLine("  FLUX_DESKTOP_READY\r", "proot"))
        assertFalse(DesktopLauncher.isDesktopReadyLine("echo FLUX_DESKTOP_READY failed", "proot"))
        assertFalse(DesktopLauncher.isDesktopReadyLine("FluxLinux: startxfce4 found in guest", "proot"))
        // FOLLOW-UP: chroot scripts still announce only the host socket.
        assertTrue(DesktopLauncher.isDesktopReadyLine(hostLine, "chroot"))
        assertTrue(DesktopLauncher.isDesktopReadyLine("FluxLinux: host X0 socket ready", "chroot"))
        assertTrue(DesktopLauncher.isDesktopReadyLine("FLUX_DESKTOP_READY", "chroot"))
    }

    @Test
    fun startGui_checksDesktopBeforeLaunchingX_andCleansUpOnExit() {
        val deCheck = start.indexOf("setup incomplete (\$DE_CMD missing)")
        assertTrue(deCheck > 0)
        assertTrue("DE check must precede killing/launching X", deCheck < start.indexOf("pkill -f termux-x11"))
        assertTrue(deCheck < start.indexOf("/system/bin/app_process"))
        assertTrue(deCheck < start.indexOf("virgl_test_server_android --socket-path"))
        val trap = start.indexOf("trap _flux_cleanup EXIT")
        assertTrue(trap in 1 until start.indexOf("/system/bin/app_process"))
        assertTrue(start.contains("VIRGL_PID=\$!"))
        assertTrue(start.contains("XSERVER_PID=\$!"))
        assertTrue(start.contains("kill \"\$XSERVER_PID\""))
        assertTrue(start.contains("kill \"\$VIRGL_PID\""))
    }

    @Test
    fun guest_emitsReadyMarkerOnlyBeforeExecingTheDesktop() {
        val marker = guest.indexOf("echo FLUX_DESKTOP_READY")
        assertTrue(marker > 0)
        assertEquals("one marker only", 1, Regex("echo FLUX_DESKTOP_READY").findAll(start).count())
        assertTrue(marker > guest.indexOf("command -v \$FLUX_DE_CMD"))
        assertTrue(marker < guest.indexOf("exec dbus-run-session -- \$FLUX_DE_CMD"))
        assertTrue("marker must wait for the DE pid", guest.contains("kill -0 \\\$\\\$"))
    }

    @Test
    fun guest_bwrapShimCarriesMarker_andGuardMatchesIt() {
        val heredoc = guest.substringAfter("<< \"BWRAP_EOF\"").substringBefore("BWRAP_EOF\n")
        assertTrue("setup scripts grep this marker", heredoc.contains("FluxLinux proot"))
        assertTrue("guard below greps this", heredoc.contains("bwrap-shim"))
        assertTrue(guest.contains("if ! grep -q \"bwrap-shim\" /usr/bin/bwrap"))
        assertTrue(guest.contains("[ ! -x /usr/bin/bwrap.real ]"))
        // The compiled shim the guard must recognise also contains the marker.
        assertTrue(
            repoFile("src/main/assets/scripts/common/setup/bwrap-proot-shim.c").readText()
                .contains("bwrap-shim: no command"),
        )
    }

    @Test
    fun guest_homePermsRunOnceBehindMarker_withoutWorldWritable() {
        assertFalse(start.contains("chmod -R 777"))
        assertTrue(guest.contains("chmod -R u+rwX /home/flux/.config"))
        val gate = guest.indexOf("!= \"\$(_perm_sig)\" ]")
        assertTrue(gate > 0)
        assertTrue(gate < guest.indexOf("chown -R \"\$_home_uid:\$_home_gid\" /home/flux"))
        assertTrue(guest.contains("_perm_sig >\"\$_perm_mark\""))
    }

    @Test
    fun scripts_parseUnderBashAndSh() {
        val bash = File("/bin/bash")
        assumeTrue(bash.canExecute())
        val dir = Files.createTempDirectory("startgui")
        try {
            val guestFile = dir.resolve("guest.sh").toFile().apply { writeText(guest) }
            for (f in listOf(repoFile(startRel), repoFile(stopRel), guestFile)) {
                assertEquals("bash -n ${f.name}", 0, run(listOf(bash.path, "-n", f.path)).first)
            }
            // The guest falls back to /bin/sh when the rootfs has no bash.
            File("/bin/sh").takeIf { it.canExecute() }?.let { sh ->
                assertEquals("sh -n guest", 0, run(listOf(sh.path, "-n", guestFile.path)).first)
            }
        } finally {
            SafeFiles.deleteTree(dir.toFile())
        }
    }

    @Test
    fun startGui_missingDesktop_failsBeforeAnyHostSideEffect() {
        val bash = File("/bin/bash")
        assumeTrue(bash.canExecute())
        val dir = Files.createTempDirectory("startgui")
        try {
            val prefix = dir.resolve("usr").toFile()
            File(prefix, "var/lib/proot-distro/containers/ubuntu/rootfs/usr/bin").mkdirs()
            val (code, out) = run(
                listOf(bash.path, repoFile(startRel).path, "ubuntu"),
                mapOf(
                    "PATH" to "/usr/bin:/bin",
                    "TERMUX__PREFIX" to prefix.path,
                    "TERMUX__HOME" to dir.resolve("home").toString(),
                    "TERMUX_APP__PACKAGE_NAME" to "com.example.none",
                ),
            )
            assertEquals(out, 1, code)
            assertTrue(out, out.contains("setup incomplete (startxfce4 missing)"))
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
