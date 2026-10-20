package com.ivarna.fluxlinux.core.root

import com.ivarna.fluxlinux.BuildConfig
import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * M14: root-executed helpers live in a root-only dir and run only after their SHA-256 matches
 * the APK asset. Path/contract checks plus the staging/verification shell snippets executed
 * with the host `/bin/sh` inside a temp dir (su and /data/adb are device-only).
 */
class RootStageM14Test {

    private val tmp: File = Files.createTempDirectory("m14").toFile()

    @After
    fun tearDown() {
        SafeFiles.deleteTree(tmp)
        RootShell.seedAssetShaForTest(ChrootPaths.CHROOT_HELPER_ASSET, null)
    }

    private fun sh(script: String): Int {
        val p = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        return p.waitFor()
    }

    private fun needsHostTools() {
        assumeTrue(File("/bin/sh").canExecute())
        assumeTrue(sh("command -v sha256sum >/dev/null && command -v base64 >/dev/null") == 0)
    }

    // ── paths ────────────────────────────────────────────────────────────────

    @Test
    fun rootExecutedArtifacts_liveInRootOnlyStage() {
        assertEquals("/data/adb/fluxlinux", ChrootPaths.ROOT_STAGE_PARENT)
        assertEquals("/data/adb/fluxlinux/${BuildConfig.APPLICATION_ID}", ChrootPaths.ROOT_STAGE_DIR)
        for (p in listOf(ChrootPaths.CHROOT_HELPER, BusyBoxPaths.RESOLVER_ON_DEVICE)) {
            assertTrue(p, p.startsWith(ChrootPaths.ROOT_STAGE_DIR + "/"))
            assertFalse(p, p.startsWith("/data/local/tmp"))
        }
        // helper's `$(dirname "$0")/resolve_bb.sh` must find the verified sidecar.
        assertEquals(
            File(ChrootPaths.CHROOT_HELPER).parent,
            File(BusyBoxPaths.RESOLVER_ON_DEVICE).parent,
        )
        assertEquals("resolve_bb.sh", File(BusyBoxPaths.RESOLVER_ON_DEVICE).name)
        assertTrue(RootShell.LEGACY_ARTIFACTS.all { it.startsWith("/data/local/tmp/") })
    }

    @Test
    fun stageCommand_createsRootOnlyDir_andCleansLegacyCopies() {
        val cmd = RootShell.buildStageCommand(ChrootPaths.CHROOT_HELPER, "ab", srcWord = "'/x'")
        assertTrue(cmd.contains("chown 0:0 '${ChrootPaths.ROOT_STAGE_PARENT}' '${ChrootPaths.ROOT_STAGE_DIR}'"))
        assertTrue(cmd.contains("chmod 0700 '${ChrootPaths.ROOT_STAGE_PARENT}' '${ChrootPaths.ROOT_STAGE_DIR}'"))
        assertTrue(cmd.contains("umask 077"))
        for (legacy in RootShell.LEGACY_ARTIFACTS) assertTrue(cmd.contains("rm -f") && cmd.contains("'$legacy'"))
    }

    @Test
    fun sha256Hex_knownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            RootShell.sha256Hex("abc".toByteArray()),
        )
    }

    // ── shell snippets on the host ──────────────────────────────────────────

    @Test
    fun shaMatches_acceptsExactFile_rejectsTamperedAndSymlink() {
        needsHostTools()
        val f = File(tmp, "helper.sh").apply { writeText("echo hi\n") }
        val sha = RootShell.sha256Hex(f.readBytes())
        assertEquals(0, sh("${RootShell.SHA_FN}; ${RootShell.shaMatches(f.path, sha)}"))
        f.appendText("id\n")
        assertNotEquals(0, sh("${RootShell.SHA_FN}; ${RootShell.shaMatches(f.path, sha)}"))
        val link = File(tmp, "link.sh")
        val good = File(tmp, "good.sh").apply { writeText("echo hi\n") }
        Files.createSymbolicLink(link.toPath(), good.toPath())
        assertNotEquals(0, sh("${RootShell.SHA_FN}; ${RootShell.shaMatches(link.path, sha)}"))
        assertNotEquals(0, sh("${RootShell.SHA_FN}; ${RootShell.shaMatches(File(tmp, "missing").path, sha)}"))
    }

    @Test
    fun installSnippet_copiesOnlyWhenHashMatches() {
        needsHostTools()
        val src = File(tmp, "src.sh").apply { writeText("echo real\n") }
        val dest = File(tmp, "dest.sh").apply { writeText("echo old\n") }
        val good = RootShell.sha256Hex(src.readBytes())
        val bad = RootShell.sha256Hex("other".toByteArray())

        val rcBad = sh("${RootShell.SHA_FN}; ${RootShell.installSnippet(dest.path, bad, srcWord = "'${src.path}'")}")
        assertNotEquals(0, rcBad)
        assertEquals("echo old\n", dest.readText())
        assertEquals(listOf("dest.sh", "src.sh"), tmp.list()?.sorted())

        val rcGood = sh("${RootShell.SHA_FN}; ${RootShell.installSnippet(dest.path, good, srcWord = "'${src.path}'")}")
        assertEquals(0, rcGood)
        assertEquals("echo real\n", dest.readText())
    }

    @Test
    fun installSnippet_base64Path() {
        needsHostTools()
        val body = "#!/system/bin/sh\necho 'quoted' \"x\"\n".toByteArray()
        val dest = File(tmp, "resolve_bb.sh")
        val b64 = java.util.Base64.getEncoder().encodeToString(body)
        val rc = sh("${RootShell.SHA_FN}; ${RootShell.installSnippet(dest.path, RootShell.sha256Hex(body), b64 = b64)}")
        assertEquals(0, rc)
        assertTrue(dest.readBytes().contentEquals(body))
    }

    @Test
    fun stageDirSnippet_failsWithoutRoot() {
        needsHostTools()
        assumeTrue(sh("[ \"\$(id -u)\" != 0 ]") == 0)
        val parent = File(tmp, "adb-fluxlinux")
        val dir = File(parent, "pkg")
        // chown 0:0 must fail for a non-root caller → nothing would be staged.
        assertNotEquals(0, sh(RootShell.stageDirSnippet(parent.path, dir.path)))
    }

    // ── scripts ──────────────────────────────────────────────────────────────

    private fun asset(rel: String): String {
        val cwd = File("").absoluteFile
        return listOf(File(cwd, "src/main/assets/$rel"), File(cwd, "app/src/main/assets/$rel"))
            .first { it.isFile }.readText()
    }

    @Test
    fun resolver_neverPinsOrPicksBusyBoxFromShellWritableDir() {
        val r = asset("scripts/chroot/resolve_bb.sh")
        assertFalse(r.contains("BB=/data/local/tmp"))
        assertFalse(r.contains("cp -f \"\$BB\""))
        assertTrue(r.contains("/data/local/tmp/*) return 1"))
    }

    @Test
    fun guiWrappers_runRootStagesFromRootOnlyDir() {
        for ((rel, staged) in listOf(
            "scripts/chroot/start_gui_chroot.sh" to "sh '\$ROOT_GUI_STAGED'",
            "scripts/chroot/stop_gui_chroot.sh" to "sh '\$ROOT_STOP_STAGED'",
        )) {
            val s = asset(rel)
            assertTrue(rel, s.contains("ROOT_STAGE_PARENT=\"/data/adb/fluxlinux\""))
            assertTrue(rel, s.contains("ROOT_STAGE=\"\$ROOT_STAGE_PARENT/\$PKG\""))
            assertTrue(rel, s.contains("chmod 0700 '\$ROOT_STAGE_PARENT' '\$ROOT_STAGE'"))
            assertTrue(rel, s.contains(staged))
            assertTrue(rel, s.contains("FLUX_RESOLVE_BB='\$RESOLVER_STAGED'"))
            assertFalse(rel, Regex("""sh '/data/local/tmp""").containsMatchIn(s))
            assertFalse(rel, s.contains("_TMP=\"/data/local/tmp"))
        }
    }

    @Test
    fun helperCmd_unknownHash_refusesToRun() {
        RootShell.seedAssetShaForTest(ChrootPaths.CHROOT_HELPER_ASSET, null)
        val cmd = RootShell.buildChrootHelperCmd("flux", "ZWNobw==", ChrootPaths.CHROOT_PATH)
        assertTrue(cmd.contains("exit 127"))
        assertFalse(cmd.contains("sh '${ChrootPaths.CHROOT_HELPER}'"))
    }

    @Test
    fun helperCmd_verifiesBeforeRun_andIsValidShell() {
        needsHostTools()
        val sha = "a".repeat(64)
        RootShell.seedAssetShaForTest(ChrootPaths.CHROOT_HELPER_ASSET, sha)
        val cmd = RootShell.buildChrootHelperCmd("flux", "ZWNobw==", ChrootPaths.CHROOT_PATH)
        val check = cmd.indexOf("if ${RootShell.shaMatches(ChrootPaths.CHROOT_HELPER, sha)}; then sh")
        assertTrue(check >= 0)
        assertFalse(cmd.contains("/data/local/tmp/fluxlinux_chroot.sh"))
        val p = ProcessBuilder("/bin/sh", "-n", "-c", cmd).redirectErrorStream(true).start()
        val out = String(p.inputStream.readBytes())
        assertEquals(out, 0, p.waitFor())
    }
}
