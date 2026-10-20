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
                out[f.relativeTo(root).invariantSeparatorsPath] = f.readText()
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

    /**
     * M14: lines/variables that write a file under shell-writable /data/local/tmp or run one
     * from there as root. Chroot rootfs paths (/data/local/tmp/chroot*) are a recorded
     * residual and allowed; rm -f cleanup and case-pattern rejection never match.
     */
    private fun dataLocalTmpWriteOrExec(src: String): List<String> {
        val p = "/data/local/tmp/(?!chroot)"
        val execLit = Regex("""(^|[;&|(`\s/"'])(sh|bash|exec|source|\.)\s+["']?""" + p)
        val writeLit = Regex("""(>>?\s*["']?|\b(cp|mv|install|ln)\b[^\n#]*\s["']?)""" + p)
        val assign = Regex("""\b([A-Za-z_][A-Za-z0-9_]*)=["']?""" + p + """[^\s"';]+""")
        val code = src.lines().filterNot { it.trimStart().startsWith("#") }
        val hits = code.filter { execLit.containsMatchIn(it) || writeLit.containsMatchIn(it) }
            .map { it.trim() }.toMutableList()
        val text = code.joinToString("\n")
        for (name in assign.findAll(text).map { it.groupValues[1] }.toSet()) {
            val use = Regex(
                """(>>?\s*["']?|\b(sh|bash|exec|source|\.)\s+["']?|\b(cp|mv|install|ln)\b[^\n#]*\s["']?)""" +
                    """\$\{?""" + Regex.escape(name) + """\b""",
            )
            if (use.containsMatchIn(text)) hits += "\$$name"
        }
        return hits
    }

    @Test
    fun dataLocalTmpDetector_flagsWriteThenExec_notCleanupOrRootfs() {
        assertTrue(dataLocalTmpWriteOrExec("_h=/data/local/tmp/fluxlinux_chroot.sh\n/system/bin/sh \"\$_h\" x").isNotEmpty())
        assertTrue(dataLocalTmpWriteOrExec("S=\"/data/local/tmp/enter.sh\"\ncat > \"\$S\" <<EOF\nEOF").isNotEmpty())
        assertTrue(dataLocalTmpWriteOrExec("cp -f \"\$A\" /data/local/tmp/stop.sh").isNotEmpty())
        assertTrue(dataLocalTmpWriteOrExec("su -c \"sh /data/local/tmp/start.sh\"").isNotEmpty())
        val allowed = """
            CHROOT_PATH="${'$'}{CHROOT_PATH:-/data/local/tmp/chrootAlpine}"
            mkdir -p "${'$'}CHROOT_PATH/tmp" && echo x > "${'$'}CHROOT_PATH/etc/x"
            LAUNCH_SCRIPTS="/data/local/tmp/a.sh /data/local/tmp/b.sh"
            for s in ${'$'}LAUNCH_SCRIPTS; do rm -f "${'$'}s"; done
            rm -f /data/local/tmp/fluxlinux_chroot.sh 2>/dev/null
            case "${'$'}_c" in /data/local/tmp/*) continue ;; esac
            # M14: never source anything from shell-writable /data/local/tmp.
        """.trimIndent()
        assertTrue(dataLocalTmpWriteOrExec(allowed).toString(), dataLocalTmpWriteOrExec(allowed).isEmpty())
    }

    @Test
    fun noAssetScript_writesThenRunsFilesInDataLocalTmp() {
        // Legacy per-distro chroot setups with no live caller (nothing in the app loads them).
        val legacyNoCaller = setOf(
            "arch/chroot/setup/setup_arch_chroot.sh",
            "debian/chroot/setup/setup_debian13_chroot.sh",
            "debian/chroot/setup/setup_debian_chroot.sh",
        )
        val kotlin = repoFile("src/main/kotlin")
        SafeFiles.walkNoFollow(kotlin) { f, attrs ->
            if (attrs.isRegularFile && f.name.endsWith(".kt")) {
                val src = f.readText()
                assertFalse("$f loads a legacy chroot setup", src.contains("chroot/setup/"))
            }
        }
        // FOLLOW-UP (owned by another remediation worker at the time of writing): these still
        // write root-run compat launchers to /data/local/tmp; drop once moved to the stage.
        val pendingFollowUp = setOf(
            "chroot/setup_debian13_chroot.sh",
            "chroot/setup_alpine_chroot.sh",
        )
        val offenders = allAssetScripts()
            .filterKeys { it !in legacyNoCaller && it !in pendingFollowUp }
            .mapValues { dataLocalTmpWriteOrExec(it.value) }
            .filterValues { it.isNotEmpty() }
        assertTrue("write-then-exec under /data/local/tmp: $offenders", offenders.isEmpty())
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
        // The uninstall marker is a directory (mkdir never follows a planted symlink).
        for ((name, v) in listOf(
            "setup_guest_chroot.sh" to "GUESTPATH",
            "setup_debian13_chroot.sh" to "DEBIANPATH",
            "setup_alpine_chroot.sh" to "ALPINEPATH",
        )) {
            val src = chrootScript(name)
            assertTrue(name, src.contains("rm -rf \"\$$v/.flux_uninstalling\""))
        }
        assertTrue(chrootScript("setup_guest_chroot.sh").contains(".flux_extract_ok"))
    }
}
