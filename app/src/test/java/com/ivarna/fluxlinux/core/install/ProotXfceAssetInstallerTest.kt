package com.ivarna.fluxlinux.core.install

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException

class ProotXfceAssetInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun selection_dark_uses_papirusDark_and_transparency() {
        val sel = ProotXfceAssetInstaller.selectionFor("dark")
        assertEquals("Space-transparency", sel.themeName)
        assertEquals("Papirus-Dark", sel.iconName)
        assertEquals(ProotXfceAssetInstaller.ICON_NAME, sel.iconName)
        assertEquals("Vimix-white-cursors", sel.cursorName)
        assertTrue(sel.wallpaperFileName.contains("dark"))
    }

    @Test
    fun selection_light_also_uses_papirusDark_only() {
        val sel = ProotXfceAssetInstaller.selectionFor("light")
        assertEquals("Space-light", sel.themeName)
        // No full Papirus / Papirus-Light — dark variant only for all themes
        assertEquals("Papirus-Dark", sel.iconName)
        assertEquals(ProotXfceAssetInstaller.ICON_NAME, sel.iconName)
        assertEquals("Vimix-cursors", sel.cursorName)
    }

    @Test
    fun isThemeInstalled_requires_marker() {
        val root = tmp.newFolder("rootfs")
        val themeDir = File(root, "usr/share/themes/Space-transparency").also { it.mkdirs() }
        assertFalse(ProotXfceAssetInstaller.isThemeInstalled(root, "Space-transparency"))
        File(themeDir, "gtk-3.0").mkdirs()
        assertTrue(ProotXfceAssetInstaller.isThemeInstalled(root, "Space-transparency"))
    }

    @Test
    fun isIconInstalled_detects_index_or_nonempty() {
        val root = tmp.newFolder("rootfs2")
        val iconDir = File(root, "usr/share/icons/Papirus-Dark").also { it.mkdirs() }
        assertFalse(ProotXfceAssetInstaller.isIconInstalled(root, "Papirus-Dark"))
        File(iconDir, "index.theme").writeText("[Icon Theme]\nName=Papirus-Dark\n")
        assertTrue(ProotXfceAssetInstaller.isIconInstalled(root, "Papirus-Dark"))
    }

    @Test
    fun icon_asset_must_not_end_in_gz_aapt2_rule() {
        // aapt2 auto-decompresses *.gz assets (renames to *.tar) — asset paths
        // opened via AssetManager must never end in .gz.
        assertFalse(ProotXfceAssetInstaller.ICON_ASSET.endsWith(".gz"))
        assertFalse(ProotXfceAssetInstaller.ICON_FILE.endsWith(".gz"))
        assertTrue(ProotXfceAssetInstaller.ICON_FILE.endsWith(".tar.xz"))
        assertFalse(ProotXfceAssetInstaller.ICON_CATEGORIES_ASSET.endsWith(".gz"))
        assertTrue(ProotXfceAssetInstaller.ICON_CATEGORIES_FILE.endsWith(".tar.xz"))
    }

    @Test
    fun isSelectionInstalled_all_or_nothing() {
        val root = tmp.newFolder("rootfs3")
        val sel = ProotXfceAssetInstaller.selectionFor("dark")
        assertFalse(ProotXfceAssetInstaller.isSelectionInstalled(root, sel))
        File(root, "usr/share/themes/${sel.themeName}/xfwm4").mkdirs()
        File(root, "usr/share/icons/${sel.iconName}/index.theme").also {
            it.parentFile!!.mkdirs()
            it.writeText("x")
        }
        File(root, "usr/share/icons/${sel.cursorName}/cursors").mkdirs()
        assertTrue(ProotXfceAssetInstaller.isSelectionInstalled(root, sel))
    }

    // ---- L19: atomic, APK-versioned asset cache ------------------------------------

    @Test
    fun writeAtomically_failedWriteLeavesNoPartialFileAndKeepsOld() {
        val dir = tmp.newFolder("cache")
        val dest = File(dir, "theme.tar.xz")
        assertFalse(
            ProotXfceAssetInstaller.writeAtomically(dest) { out ->
                out.write(ByteArray(2048))
                throw java.io.IOException("truncated")
            },
        )
        assertFalse("no truncated cache file", dest.exists())
        assertFalse("temp file removed", File(dir, "theme.tar.xz.tmp").exists())

        dest.writeText("complete")
        assertFalse(ProotXfceAssetInstaller.writeAtomically(dest) { throw java.io.IOException("x") })
        assertEquals("complete", dest.readText())
    }

    @Test
    fun writeAtomically_successReplacesDest() {
        val dest = File(tmp.newFolder("cache2"), "icons.tar.xz").apply { writeText("old") }
        assertTrue(ProotXfceAssetInstaller.writeAtomically(dest) { it.write("new".toByteArray()) })
        assertEquals("new", dest.readText())
        assertFalse(File(dest.parentFile, "icons.tar.xz.tmp").exists())
    }

    @Test
    fun versionedCacheDir_dropsFilesFromAnotherApkVersion() {
        val parent = tmp.newFolder("cacheRoot")
        val dir = ProotXfceAssetInstaller.versionedCacheDir(parent, "flux_xfce_assets", 100L)
        val cached = File(dir, "theme.tar.xz").apply { writeText("v100") }

        // Same APK: the cache is reused.
        assertEquals(dir, ProotXfceAssetInstaller.versionedCacheDir(parent, "flux_xfce_assets", 100L))
        assertTrue(cached.isFile)

        // Updated APK: stale archives are dropped.
        val again = ProotXfceAssetInstaller.versionedCacheDir(parent, "flux_xfce_assets", 200L)
        assertTrue(again.isDirectory)
        assertFalse(cached.exists())
    }

    // ---- host xz missing must never strip the guest's fallback copies ----------------

    private val dark = ProotXfceAssetInstaller.selectionFor("dark")

    /** Every archive [installInto] stages for the dark selection, by file name. */
    private val darkArchives = listOf(
        File(dark.themeAsset).name,
        ProotXfceAssetInstaller.ICON_FILE,
        ProotXfceAssetInstaller.ICON_CATEGORIES_FILE,
        File(dark.cursorAsset).name,
    )

    private fun apkBytes(name: String) = ByteArray(4096) { (it + name.length).toByte() }

    private fun openAsset(path: String) =
        File(path).name.takeIf { it in darkArchives }?.let { ByteArrayInputStream(apkBytes(it)) }
            ?: throw FileNotFoundException(path)

    private fun installDark(
        root: File,
        extract: (File, File, String?) -> ProotXfceAssetInstaller.Extract,
    ): Boolean = ProotXfceAssetInstaller.installInto(
        rootfs = File(root, "rootfs").apply { mkdirs() },
        sel = dark,
        cache = File(root, "cache").apply { mkdirs() },
        shared = File(root, "shared"),
        openAsset = ::openAsset,
        extract = extract,
        onLog = {},
    )

    @Test
    fun hostWithoutXz_keepsEveryGuestCopyAndCache() {
        val root = tmp.newFolder("noxz")
        val installed = installDark(root) { _, _, _ -> ProotXfceAssetInstaller.Extract.UNAVAILABLE }

        assertFalse("nothing was extracted on the host", installed)
        for (name in darkArchives) {
            val shared = File(root, "shared/$name")
            assertTrue("guest copy kept: $name", shared.isFile)
            assertArrayEquals(name, apkBytes(name), shared.readBytes())
            assertTrue("cache kept (not corrupt): $name", File(root, "cache/$name").isFile)
        }
    }

    @Test
    fun failedExtract_keepsGuestCopyAndRestagesCorruptCache() {
        val root = tmp.newFolder("corrupt")
        val icon = ProotXfceAssetInstaller.ICON_FILE
        val cached = File(root, "cache/$icon").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(4096) { 7 })
        }
        val seen = mutableListOf<String>()
        // tar "fails" only on an archive that differs from the APK asset.
        val extract = { archive: File, _: File, _: String? ->
            seen += archive.name
            if (archive.readBytes().contentEquals(apkBytes(archive.name))) {
                ProotXfceAssetInstaller.Extract.OK
            } else {
                ProotXfceAssetInstaller.Extract.FAILED
            }
        }

        installDark(root, extract)
        assertFalse("corrupt cache dropped", cached.exists())
        val shared = File(root, "shared/$icon")
        assertTrue("guest copy kept after a failed extract", shared.isFile)
        assertArrayEquals("guest copy re-staged from the APK", apkBytes(icon), shared.readBytes())

        seen.clear()
        installDark(root, extract)
        assertArrayEquals("next run re-stages the cache", apkBytes(icon), cached.readBytes())
        assertTrue("icons extracted on the next run", icon in seen)
        assertTrue(File(root, "shared/$icon").isFile)
    }

    @Test
    fun isMissingXz_detectsToyboxExecFailureOnly() {
        assertTrue(ProotXfceAssetInstaller.isMissingXz(127, ""))
        assertTrue(ProotXfceAssetInstaller.isMissingXz(1, "tar: exec xz: No such file or directory"))
        assertTrue(ProotXfceAssetInstaller.isMissingXz(1, "sh: xz: not found"))
        assertFalse(ProotXfceAssetInstaller.isMissingXz(2, "xz: (stdin): Compressed data is corrupt"))
        assertFalse(ProotXfceAssetInstaller.isMissingXz(1, "tar: short read"))
    }
}
