package com.ivarna.fluxlinux.core.chroot

import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source contracts for chroot mount/uninstall lifecycle fixes (H4, M13, M15, M16). */
class ChrootLifecycleContractTest {

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        return listOf(
            File(cwd, rel),
            File(cwd, "app/$rel"),
            File(cwd.parentFile, rel),
            File(cwd.parentFile, "app/$rel"),
        ).firstOrNull { it.exists() } ?: error("missing $rel (cwd=$cwd)")
    }

    private fun chrootScript(name: String) =
        repoFile("src/main/assets/scripts/chroot/$name").readText()

    /** Every regular `*.sh` under assets/scripts, keyed by its path relative to that dir. */
    private fun allAssetScripts(): Map<String, String> {
        val root = repoFile("src/main/assets/scripts")
        val out = sortedMapOf<String, String>()
        SafeFiles.walkNoFollow(root) { f, attrs ->
            if (attrs.isRegularFile && f.name.endsWith(".sh")) {
                out[f.relativeTo(root).path] = f.readText()
            }
        }
        assertTrue("asset script sweep found too few files", out.size > 50)
        return out
    }

    @Test
    fun ensureMounts_refusesUninstallingOrUnconfiguredTreeBeforeAnyMount() {
        val src = chrootScript("fluxlinux_chroot.sh")
        val body = src.substringAfter("ensure_mounts() {").substringBefore("\n}\n")
        val guard = body.indexOf(".flux_uninstalling")
        val configured = body.indexOf(".flux_configured")
        val firstMkdir = body.indexOf("mkdir -p")
        val firstMount = body.indexOf("mount")
        assertTrue("uninstalling guard missing", guard >= 0)
        assertTrue("configured guard missing", configured >= 0)
        assertTrue("guards must precede mkdir/mount", guard < firstMkdir && configured < firstMkdir)
        assertTrue(guard < firstMount)
    }

    @Test
    fun startScripts_neverChangeSelinuxMode() {
        for (name in listOf("start_guest_gui.sh", "start_debian13_gui.sh", "start_alpine_gui.sh")) {
            val src = chrootScript(name)
            assertFalse("$name must not call setenforce", src.contains("setenforce "))
        }
    }

    @Test
    fun noAssetScript_switchesSelinuxToPermissive() {
        // M13: a chroot guest shares the device kernel, so setenforce anywhere (host start
        // script, guest family setup, legacy generated launcher) drops all of Android's MAC.
        val call = Regex("""\bsetenforce\s+(0|[Pp]ermissive)\b""")
        val offenders = allAssetScripts().filterValues { call.containsMatchIn(it) }.keys
        assertTrue("setenforce 0 in: $offenders", offenders.isEmpty())
    }

    @Test
    fun stopScripts_keepMountsWhileChrootInUse() {
        for (name in listOf("stop_guest_gui.sh", "stop_debian13_gui.sh", "stop_alpine_gui.sh")) {
            val src = chrootScript(name)
            val guard = src.indexOf("&& chroot_in_use; then")
            val procUmount = src.indexOf("/proc\" \\")
            assertTrue("$name: in-use guard missing", guard >= 0)
            assertTrue("$name: guard must precede the /proc unmount", guard < procUmount)
            assertTrue(src.contains("keeping its mounts"))
        }
    }

    @Test
    fun uninstallSession_chrootStagingRunsOffMain_andSetsUninstallingFlag() {
        val src = repoFile(
            "src/main/kotlin/com/ivarna/fluxlinux/core/terminal/UninstallSessionFactory.kt",
        ).readText()
        val executor = src.indexOf("TerminalLauncher.executor.execute")
        val staging = src.indexOf("chrootUninstallCommand(appCtx")
        val post = src.indexOf("TerminalLauncher.mainHandler.post")
        assertTrue(executor in 0 until staging)
        assertTrue(staging < post)
        assertTrue(src.contains("uninstallingChroots.add(chrootPath)"))
        assertTrue(src.contains("release()"))

        val desktop = repoFile(
            "src/main/kotlin/com/ivarna/fluxlinux/core/desktop/DesktopLauncher.kt",
        ).readText()
        assertTrue(desktop.contains("isChrootUninstalling(path)"))

        val main = repoFile("src/main/kotlin/com/ivarna/fluxlinux/MainActivity.kt").readText()
        assertTrue(main.contains(".openUninstallSession(this@MainActivity, distro) { opened ->"))
    }

    @Test
    fun chrootScripts_neverSourceOrRunBusyBoxFromDataLocalTmp() {
        val names = listOf(
            "fluxlinux_chroot.sh",
            "setup_guest_chroot.sh", "setup_debian13_chroot.sh", "setup_alpine_chroot.sh",
            "start_guest_gui.sh", "start_debian13_gui.sh", "start_alpine_gui.sh",
            "stop_guest_gui.sh", "stop_debian13_gui.sh", "stop_alpine_gui.sh",
            "uninstall_guest_chroot.sh", "uninstall_debian13_chroot.sh", "uninstall_alpine_chroot.sh",
        )
        for (name in names) {
            val src = chrootScript(name)
            assertFalse("$name: legacy pinned busybox", src.contains("/data/local/tmp/flux_busybox"))
            assertFalse("$name: legacy resolver", src.contains("/data/local/tmp/fluxlinux_resolve_bb.sh"))
            assertFalse(
                "$name: helper default in /data/local/tmp",
                src.contains("HELPER=\"\${HELPER:-/data/local/tmp/") ||
                    src.contains("HELPER=\"/data/local/tmp/fluxlinux_chroot.sh\"") ||
                    src.contains("HELPER=/data/local/tmp/fluxlinux_chroot.sh"),
            )
        }
    }

    @Test
    fun chrootGuests_usePulseUnixSocketThroughHostTmp() {
        val names = listOf(
            "fluxlinux_chroot.sh", "setup_debian13_chroot.sh",
            "start_guest_gui.sh", "start_debian13_gui.sh", "start_alpine_gui.sh",
        )
        for (name in names) {
            val src = chrootScript(name)
            assertFalse("$name: anonymous TCP pulse", src.contains("PULSE_SERVER=tcp:"))
            assertTrue(name, src.contains("PULSE_SERVER=unix:/mnt/host-tmp/pulse-native"))
        }
        // The socket is reached through the host-tmp bind; keep it.
        assertTrue(chrootScript("fluxlinux_chroot.sh").contains("bind_if_missing \"\$FLUX_HOST_TMP\" \"\$FLUX_CHROOT/mnt/host-tmp\""))
    }

    @Test
    fun setupClearsStaleUninstallingMarkerWhenConfigured() {
        val src = chrootScript("setup_guest_chroot.sh")
        assertTrue(src.contains("rm -f \"\$GUESTPATH/.flux_uninstalling\""))
        assertTrue(src.contains(".flux_extract_ok"))
    }
}
