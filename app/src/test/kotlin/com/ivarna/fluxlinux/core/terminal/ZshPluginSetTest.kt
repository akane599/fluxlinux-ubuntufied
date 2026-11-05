package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ZshPluginSetTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("zsh-plugin-set").toFile()
    }

    @After
    fun tearDown() {
        SafeFiles.deleteTree(tmp)
    }

    private val legacyZshrc = """
        export ZSH="${'$'}{ZSH:-${'$'}HOME/.oh-my-zsh}"
        if [ -f "${'$'}ZSH/oh-my-zsh.sh" ]; then
          ZSH_THEME="agnosterzak"
          plugins=(git zsh-autosuggestions zsh-syntax-highlighting)
          source "${'$'}ZSH/oh-my-zsh.sh"
        fi
        alias ll='ls -l'

    """.trimIndent()

    @Test
    fun plugins_exactOrder() {
        assertEquals(
            listOf(
                "git", "sudo", "extract", "colored-man-pages", "copypath", "copyfile",
                "command-not-found", "fzf", "zoxide", "fzf-tab",
                "zsh-autosuggestions", "zsh-syntax-highlighting", "history-substring-search",
            ),
            ZshPluginSet.PLUGINS,
        )
        val block = ZshPluginSet.managedBlock()
        assertTrue(block.contains("for _flux_p in ${ZshPluginSet.PLUGINS.joinToString(" ")}; do"))
    }

    @Test
    fun managedBlock_guardsOptionalToolsAndBindsArrows() {
        val block = ZshPluginSet.managedBlock()
        assertTrue(block.contains("fzf|fzf-tab) (( \$+commands[fzf] )) || continue ;;"))
        assertTrue(block.contains("zoxide) (( \$+commands[zoxide] )) || continue ;;"))
        assertTrue(block.contains("command-not-found) [[ -x /usr/lib/command-not-found"))
        assertTrue(block.contains("bindkey '^[[A' history-substring-search-up"))
        assertTrue(block.contains("bindkey '^[[B' history-substring-search-down"))
        assertTrue(block.contains("bindkey \"\${terminfo[kcuu1]}\" history-substring-search-up"))
        assertTrue(block.contains("bindkey \"\${terminfo[kcud1]}\" history-substring-search-down"))
        assertTrue(block.lines().filter { it.isNotEmpty() }.all { it.startsWith("  ") })
    }

    @Test
    fun upgrade_replacesManagedLineAndKeepsRest() {
        val out = ZshPluginSet.upgradeManagedZshrc(legacyZshrc)!!
        assertTrue(out.contains(ZshPluginSet.MARKER))
        assertFalse(out.contains(ZshPluginSet.LEGACY_PLUGINS_LINE))
        assertEquals(1, out.lines().count { it.trim() == ZshPluginSet.OMZ_SOURCE_LINE })
        assertTrue(out.contains("  ZSH_THEME=\"agnosterzak\"\n  # flux-zsh-plugins v1"))
        assertTrue(out.contains("  fi\nfi\nalias ll='ls -l'\n"))
        assertTrue(out.endsWith("alias ll='ls -l'\n"))
    }

    @Test
    fun upgrade_customizedLineUntouched() {
        val custom = legacyZshrc.replace(
            ZshPluginSet.LEGACY_PLUGINS_LINE,
            "plugins=(git docker zsh-autosuggestions zsh-syntax-highlighting)",
        )
        assertNull(ZshPluginSet.upgradeManagedZshrc(custom))
        // Managed line moved away from the source line → also user-edited.
        val moved = legacyZshrc.replace("  source \"", "  echo hi\n  source \"")
        assertTrue(moved.contains("echo hi"))
        assertNull(ZshPluginSet.upgradeManagedZshrc(moved))

        val zshrc = File(tmp, ".zshrc").apply { writeText(custom) }
        assertEquals(ZshPluginSet.UpgradeResult.UNCHANGED, ZshPluginSet.upgradeZshrcFile(zshrc))
        assertEquals(custom, zshrc.readText())
        assertFalse(File(tmp, ".zshrc.flux-bak").exists())
    }

    @Test
    fun upgradeFile_backsUpAndIsIdempotent() {
        val zshrc = File(tmp, ".zshrc").apply { writeText(legacyZshrc) }
        assertEquals(ZshPluginSet.UpgradeResult.UPGRADED, ZshPluginSet.upgradeZshrcFile(zshrc))
        assertEquals(legacyZshrc, File(tmp, ".zshrc.flux-bak").readText())
        val first = zshrc.readText()
        assertTrue(ZshPluginSet.isManaged(zshrc))

        assertNull(ZshPluginSet.upgradeManagedZshrc(first))
        assertEquals(ZshPluginSet.UpgradeResult.UNCHANGED, ZshPluginSet.upgradeZshrcFile(zshrc))
        assertEquals(first, zshrc.readText())
    }

    // A block written before `sudo` joined the set (2026-11-05): the unedited list gains it.
    private val v1ForLine = "for _flux_p in git extract colored-man-pages copypath copyfile " +
        "command-not-found fzf zoxide fzf-tab zsh-autosuggestions zsh-syntax-highlighting " +
        "history-substring-search; do"

    private fun v1Zshrc(): String {
        val current = ZshPluginSet.upgradeManagedZshrc(legacyZshrc)!!
        val line = "for _flux_p in ${ZshPluginSet.PLUGINS.joinToString(" ")}; do"
        assertEquals(1, current.lines().count { it.trim() == line })
        return current.replace(line, v1ForLine)
    }

    @Test
    fun upgrade_unEditedOlderBlockGetsTheCurrentList() {
        val v1 = v1Zshrc()
        val out = ZshPluginSet.upgradeManagedZshrc(v1)!!
        assertEquals(ZshPluginSet.upgradeManagedZshrc(legacyZshrc), out)
        assertEquals(v1.lines().size, out.lines().size)
        assertTrue(out.contains("  for _flux_p in git sudo extract "))
        // Current block: nothing to do.
        assertNull(ZshPluginSet.upgradeManagedZshrc(out))
    }

    @Test
    fun upgrade_editedOlderListUntouched() {
        val edited = v1Zshrc().replace(v1ForLine, v1ForLine.replace(" extract ", " docker extract "))
        assertNull(ZshPluginSet.upgradeManagedZshrc(edited))
        val zshrc = File(tmp, ".zshrc").apply { writeText(edited) }
        assertEquals(ZshPluginSet.UpgradeResult.UNCHANGED, ZshPluginSet.upgradeZshrcFile(zshrc))
        assertEquals(edited, zshrc.readText())
    }

    @Test
    fun upgradeKey_bumpedSoUpgradedInstallsRunOnceMore() {
        assertEquals("guest_zsh_plugins_v2_ubuntu", ZshPluginSet.upgradeKey("ubuntu"))
    }

    @Test
    fun upgradeFile_neverFollowsSymlink() {
        val victim = File(tmp, "victim").apply { writeText(legacyZshrc) }
        val zshrc = File(tmp, ".zshrc")
        Files.createSymbolicLink(zshrc.toPath(), victim.toPath())
        assertEquals(ZshPluginSet.UpgradeResult.SKIPPED, ZshPluginSet.upgradeZshrcFile(zshrc))
        assertEquals(legacyZshrc, victim.readText())
    }

    @Test
    fun customizationScript_ubuntuBlockMatchesKotlin() {
        val script = repoFile("src/main/assets/scripts/common/setup/setup_customization_xfce.sh")
            .readText()
        assertTrue(
            "setup_customization_xfce.sh drifted from ZshPluginSet.managedBlock()",
            script.contains("<< 'ZSHEOF'\n" + ZshPluginSet.managedBlock() + "ZSHEOF\n"),
        )
        // Other distros sharing the script keep the legacy line.
        assertTrue(script.contains("  " + ZshPluginSet.LEGACY_PLUGINS_LINE + "\n"))
        ZshPluginSet.APT_PACKAGES.keys.forEach { pkg -> assertTrue(script.contains(pkg)) }
        assertTrue(script.contains(ZshPluginSet.EXTERNAL_PLUGINS.getValue("fzf-tab").url))
    }

    @Test
    fun chrootHelper_profileBlockMatchesKotlin() {
        val helper = repoFile("src/main/assets/scripts/chroot/fluxlinux_chroot.sh").readText()
        assertTrue(
            "fluxlinux_chroot.sh drifted from ZshPluginSet.managedBlock()",
            helper.contains("  ZSH_DISABLE_COMPFIX=true\n" + ZshPluginSet.managedBlock() + "fi\n"),
        )
        assertFalse(helper.contains(ZshPluginSet.LEGACY_PLUGINS_LINE))
    }

    @Test
    fun chrootZshrcUpgrade_parseRead() {
        val text = legacyZshrc
        val b64 = java.util.Base64.getEncoder().encodeToString(text.toByteArray())
        val ok = ChrootZshrcUpgrade.parseRead(
            0, "noise\n${ChrootZshrcUpgrade.BEGIN}\n$b64\n${ChrootZshrcUpgrade.END}\n",
        )
        assertEquals(ChrootZshrcUpgrade.ReadResult.Text(text), ok)
        assertEquals(
            ChrootZshrcUpgrade.ReadResult.Skip,
            ChrootZshrcUpgrade.parseRead(0, "${ChrootZshrcUpgrade.SKIP}\n"),
        )
        assertEquals(ChrootZshrcUpgrade.ReadResult.Failed, ChrootZshrcUpgrade.parseRead(1, ""))
        assertEquals(
            ChrootZshrcUpgrade.ReadResult.Failed,
            ChrootZshrcUpgrade.parseRead(0, "${ChrootZshrcUpgrade.BEGIN}\n@@@\n${ChrootZshrcUpgrade.END}"),
        )
        assertEquals(
            ChrootZshrcUpgrade.ReadResult.Failed,
            ChrootZshrcUpgrade.parseRead(0, "${ChrootZshrcUpgrade.BEGIN}\n$b64\n"),
        )
    }

    /** The chroot read/write scripts run against a temp home with /bin/sh (root-free). */
    @Test
    fun chrootZshrcUpgrade_scriptsRoundTripAndRefuseSymlinks() {
        org.junit.Assume.assumeTrue(File("/bin/sh").canExecute())
        val home = File(tmp, "home/akane").apply { mkdirs() }
        val zshrc = File(home, ".zshrc").apply { writeText(legacyZshrc) }
        fun sh(script: String): Pair<Int, String> {
            val p = ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start()
            val out = String(p.inputStream.readBytes())
            return p.waitFor() to out
        }
        val (rc, out) = sh(ChrootZshrcUpgrade.readScript(home.absolutePath))
        val read = ChrootZshrcUpgrade.parseRead(rc, out)
        assertEquals(ChrootZshrcUpgrade.ReadResult.Text(legacyZshrc), read)
        val upgraded = ZshPluginSet.upgradeManagedZshrc(legacyZshrc) ?: error("no upgrade")
        val sha = com.ivarna.fluxlinux.core.root.RootShell.sha256Hex(legacyZshrc.toByteArray())

        // Stale hash: nothing written.
        val (staleRc, _) = sh(ChrootZshrcUpgrade.writeScript(home.absolutePath, "0".repeat(64), upgraded))
        assertEquals(4, staleRc)
        assertEquals(legacyZshrc, zshrc.readText())

        val (wrc, wout) = sh(ChrootZshrcUpgrade.writeScript(home.absolutePath, sha, upgraded))
        assertEquals(wout, 0, wrc)
        assertTrue(wout.contains(ChrootZshrcUpgrade.WRITTEN))
        assertEquals(upgraded, zshrc.readText())
        assertEquals(legacyZshrc, File(home, ".zshrc.flux-bak").readText())

        // Symlinked .zshrc: skipped by both scripts, victim untouched.
        val victim = File(tmp, "victim").apply { writeText(legacyZshrc) }
        zshrc.delete()
        Files.createSymbolicLink(zshrc.toPath(), victim.toPath())
        val (src, sout) = sh(ChrootZshrcUpgrade.readScript(home.absolutePath))
        assertEquals(ChrootZshrcUpgrade.ReadResult.Skip, ChrootZshrcUpgrade.parseRead(src, sout))
        assertEquals(3, sh(ChrootZshrcUpgrade.writeScript(home.absolutePath, sha, upgraded)).first)
        assertEquals(legacyZshrc, victim.readText())
    }

    @Test
    fun sources_arePinnedToFullCommits() {
        val pins = ZshPluginSet.EXTERNAL_PLUGINS.values + ZshPluginSet.OMZ
        pins.forEach { pin ->
            assertTrue("full SHA: $pin", Regex("[0-9a-f]{40}").matches(pin.commit))
            assertTrue("https: $pin", pin.url.startsWith("https://"))
        }
        assertTrue(Regex("[0-9a-f]{40}").matches(ZshPluginSet.AGNOSTERZAK_COMMIT))
        assertTrue(Regex("[0-9a-f]{64}").matches(ZshPluginSet.AGNOSTERZAK_SHA256))
        assertTrue(ZshPluginSet.AGNOSTERZAK_URL.contains("/${ZshPluginSet.AGNOSTERZAK_COMMIT}/"))
        assertFalse(ZshPluginSet.AGNOSTERZAK_URL.contains("/master/"))
    }

    @Test
    fun appliesTo_ubuntuOnly() {
        assertTrue(ZshPluginSet.appliesTo("ubuntu"))
        assertTrue(ZshPluginSet.appliesTo("ubuntu_chroot"))
        assertFalse(ZshPluginSet.appliesTo("debian"))
        assertFalse(ZshPluginSet.appliesTo("kali"))
        assertFalse(ZshPluginSet.appliesTo(null))
        assertEquals(
            listOf("zsh-autosuggestions", "zsh-syntax-highlighting"),
            ZshPluginSet.externalPluginsFor("fedora").keys.toList(),
        )
        assertTrue("fzf-tab" in ZshPluginSet.externalPluginsFor("ubuntu"))
    }

    @Test
    fun missingDeps_detectedFromRootfs() {
        val rootfs = File(tmp, "rootfs")
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/fzf").writeText("")
        assertEquals(
            listOf("zoxide", "command-not-found"),
            ZshPluginSet.missingAptPackages(rootfs),
        )
        val custom = File(tmp, "custom/plugins").apply { mkdirs() }
        val bundled = File(tmp, "plugins").apply { mkdirs() }
        File(bundled, "zsh-autosuggestions").mkdirs()
        File(custom, "zsh-syntax-highlighting").mkdirs()
        File(custom, "zsh-syntax-highlighting/x.zsh").writeText("")
        assertEquals(
            listOf("fzf-tab"),
            ZshPluginSet.missingExternalPlugins(custom, bundled, "ubuntu").keys.toList(),
        )
        val script = ZshPluginSet.aptInstallScript(listOf("zoxide", "command-not-found"))
        assertFalse("host wraps it in single quotes", script.contains("'"))
        assertTrue(script.contains("for _p in zoxide command-not-found; do"))
    }

    @Test
    fun repairIfNeeded_ubuntu_upgradesCachedLegacyProfileOnce() {
        val home = guestHome("ubuntu")
        val zshrc = File(home, ".zshrc").apply { writeText(legacyZshrc) }
        val ctx = FakeContext(tmp, "$tmp/jni")
        // Profile pass already cached on an existing install.
        ctx.getSharedPreferences(GuestRepairCache.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(GuestRepairCache.zshrcKey("ubuntu"), true).apply()

        GuestZshrcRepair.repairIfNeeded(ctx, "proot", "ubuntu")
        val upgraded = zshrc.readText()
        assertTrue(upgraded.contains(ZshPluginSet.MARKER))
        assertEquals(legacyZshrc, File(home, ".zshrc.flux-bak").readText())

        GuestZshrcRepair.repairIfNeeded(ctx, "proot", "ubuntu")
        assertEquals(upgraded, zshrc.readText())
    }

    @Test
    fun repairIfNeeded_debian_keepsLegacyLine() {
        val home = guestHome("debian")
        val zshrc = File(home, ".zshrc").apply { writeText(legacyZshrc) }
        val ctx = FakeContext(tmp, "$tmp/jni")
        ctx.getSharedPreferences(GuestRepairCache.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(GuestRepairCache.zshrcKey("debian"), true).apply()
        GuestZshrcRepair.repairIfNeeded(ctx, "proot", "debian")
        assertEquals(legacyZshrc, zshrc.readText())
    }

    @Test
    fun repairIfNeeded_ubuntu_createsProfileWithManagedBlock() {
        val home = guestHome("ubuntu")
        GuestZshrcRepair.repairIfNeeded(FakeContext(tmp, "$tmp/jni"), "proot", "ubuntu")
        val text = File(home, ".zshrc").readText()
        assertTrue(text.contains(ZshPluginSet.managedBlock()))
        assertFalse(text.contains(ZshPluginSet.LEGACY_PLUGINS_LINE))
        assertFalse(GuestZshrcRepair.needsRepair(text))
        assertFalse(File(home, ".zshrc.flux-bak").exists())
    }

    private fun guestHome(name: String): File =
        File(tmp, "usr/var/lib/proot-distro/containers/$name/rootfs/home/flux").apply { mkdirs() }

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel"))
            .firstOrNull { it.isFile } ?: error("missing $rel (cwd=$cwd)")
    }
}
