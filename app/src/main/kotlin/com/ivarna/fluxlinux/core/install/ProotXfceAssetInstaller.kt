package com.ivarna.fluxlinux.core.install

import android.content.Context
import android.util.Log
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.system.SafeFiles
import com.ivarna.fluxlinux.core.terminal.TermuxHostPaths
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Host-side XFCE theme/icon/cursor/wallpaper install into the **proot rootfs**.
 *
 * Icons: **Papirus-Dark only** (shipped as `papirus-dark-only.tar.xz` ~300 KB
 * plus `papirus-xfce-categories.tar.xz` extras). Light/dark UI themes both use it.
 * Shipped as `.tar.xz`: aapt2 auto-decompresses `*.gz` assets (renames to
 * `.tar`), which breaks `AssetManager.open("…tar.gz")`.
 *
 * Native host `tar` into the proot rootfs avoids slow proot extract.
 * Skips when theme + icons + cursor are already present.
 */
object ProotXfceAssetInstaller {

    private const val TAG = "ProotXfceAssets"
    private const val GH_BASE =
        "https://github.com/abhay-byte/fluxlinux/releases/download/debian-v1"

    /** Sole icon set we ship / install. */
    const val ICON_NAME = "Papirus-Dark"
    const val ICON_ASSET = "xfce4/icons/papirus-dark-only.tar.xz"
    const val ICON_FILE = "papirus-dark-only.tar.xz"
    const val ICON_CATEGORIES_ASSET = "xfce4/icons/papirus-xfce-categories.tar.xz"
    const val ICON_CATEGORIES_FILE = "papirus-xfce-categories.tar.xz"

    data class Selection(
        val themeName: String,
        val themeAsset: String, // assets/xfce4/theme/…
        val iconName: String,
        val cursorName: String,
        val cursorAsset: String,
        val wallpaperAsset: String,
        val wallpaperFileName: String
    )

    fun selectionFor(theme: String): Selection =
        if (theme.equals("light", ignoreCase = true)) {
            Selection(
                themeName = "Space-light",
                themeAsset = "xfce4/theme/Space-light.tar.xz",
                iconName = ICON_NAME,
                cursorName = "Vimix-cursors",
                cursorAsset = "xfce4/cursor/01-Vimix-cursors.tar.xz",
                wallpaperAsset = "xfce4/wallpaper/fluxlinux-light.png",
                wallpaperFileName = "fluxlinux-light.png"
            )
        } else {
            Selection(
                themeName = "Space-transparency",
                themeAsset = "xfce4/theme/Space-transparency.tar.xz",
                iconName = ICON_NAME,
                cursorName = "Vimix-white-cursors",
                cursorAsset = "xfce4/cursor/02-Vimix-white-cursors.tar.xz",
                wallpaperAsset = "xfce4/wallpaper/fluxlinux-dark.png",
                wallpaperFileName = "fluxlinux-dark.png"
            )
        }

    fun prootRootfs(ctx: Context, prootName: String = "debian"): File =
        File(ctx.filesDir, "usr/var/lib/proot-distro/containers/$prootName/rootfs")

    fun isThemeInstalled(rootfs: File, themeName: String): Boolean {
        val dir = File(rootfs, "usr/share/themes/$themeName")
        return dir.isDirectory && (
            File(dir, "index.theme").isFile ||
                File(dir, "gtk-3.0").isDirectory ||
                File(dir, "xfwm4").isDirectory
            )
    }

    fun isIconInstalled(rootfs: File, iconName: String): Boolean {
        val dir = File(rootfs, "usr/share/icons/$iconName")
        return dir.isDirectory && (
            File(dir, "index.theme").isFile ||
                dir.list()?.isNotEmpty() == true
            )
    }

    fun isCursorInstalled(rootfs: File, cursorName: String): Boolean {
        val dir = File(rootfs, "usr/share/icons/$cursorName")
        return dir.isDirectory && (
            File(dir, "index.theme").isFile ||
                File(dir, "cursors").isDirectory
            )
    }

    fun isSelectionInstalled(rootfs: File, sel: Selection): Boolean =
        isThemeInstalled(rootfs, sel.themeName) &&
            isIconInstalled(rootfs, sel.iconName) &&
            isCursorInstalled(rootfs, sel.cursorName)

    /** Outcome of one host-side archive extract. */
    internal enum class Extract {
        OK,

        /** tar ran and failed: the archive is suspect and is re-staged. */
        FAILED,

        /** The host can't extract this format (stock Android tar has no `xz`): left to the guest. */
        UNAVAILABLE,
    }

    /**
     * @return true if selected theme/icons/cursors are present after this call
     *   (already were, or host extract succeeded).
     */
    fun install(
        ctx: Context,
        theme: String,
        prootName: String = "debian",
        onLog: (String) -> Unit = {}
    ): Boolean {
        val app = ctx.applicationContext
        val rootfs = prootRootfs(app, prootName)
        if (!rootfs.isDirectory) {
            onLog("Proot rootfs missing — skip host theme extract")
            return false
        }
        return installInto(
            rootfs = rootfs,
            sel = selectionFor(theme),
            cache = assetCacheDir(app, "flux_xfce_assets"),
            // Guest /tmp maps here with proot --shared-tmp (fallback path for script).
            shared = File(TermuxHostPaths.TMPDIR, "flux_xfce_assets"),
            openAsset = { app.assets.open(it) },
            extract = { archive, dest, onlyPrefix -> hostTarExtract(archive, dest, stripXz = true, onlyPrefix, onLog) },
            onLog = onLog,
        )
    }

    /**
     * [install] without the Android [Context]: [openAsset] reads APK assets and [extract]
     * runs the host tar. Every archive is staged into [shared] (the guest's
     * `/tmp/flux_xfce_assets`) before extraction and stays there whatever the host
     * extract does, so the guest script can always fall back to its own tar.
     */
    internal fun installInto(
        rootfs: File,
        sel: Selection,
        cache: File,
        shared: File,
        openAsset: (String) -> InputStream,
        extract: (archive: File, dest: File, onlyPrefix: String?) -> Extract,
        onLog: (String) -> Unit,
    ): Boolean {
        shared.mkdirs()
        val stager = Stager(cache, shared, openAsset, extract, onLog)
        if (isSelectionInstalled(rootfs, sel)) {
            onLog("Themes/icons already installed (${sel.themeName} + ${sel.iconName}) — skip extract")
            // Still ensure wallpaper if missing (cheap)
            ensureWallpaper(openAsset, rootfs, sel, onLog)
            stageCategoryExtras(stager, rootfs, onLog)
            return true
        }

        var ok = true
        try {
            if (!isThemeInstalled(rootfs, sel.themeName)) {
                onLog("Host-extract theme: ${sel.themeName}")
                val dest = File(rootfs, "usr/share/themes").also { it.mkdirs() }
                if (stager.stageAndExtract(sel.themeAsset, File(sel.themeAsset).name, dest) != Extract.OK) {
                    onLog("Theme ${sel.themeName} not extracted on host")
                    ok = false
                }
            } else {
                onLog("Theme ${sel.themeName} already present — skip")
            }

            if (!isIconInstalled(rootfs, sel.iconName)) {
                onLog("Host-extract icons: $ICON_NAME (dark variant only)")
                val dest = File(rootfs, "usr/share/icons").also { it.mkdirs() }
                // Archive contains only Papirus-Dark/; pass the prefix for safety, retrying a
                // full extract if the path filter fails on some tar builds.
                if (stager.stageAndExtract(ICON_ASSET, ICON_FILE, dest, onlyPrefix = ICON_NAME) != Extract.OK) {
                    onLog("Icons $ICON_NAME not extracted on host")
                    ok = false
                }
            } else {
                onLog("Icons ${sel.iconName} already present — skip")
            }

            if (!isCursorInstalled(rootfs, sel.cursorName)) {
                onLog("Host-extract cursor: ${sel.cursorName}")
                val dest = File(rootfs, "usr/share/icons").also { it.mkdirs() }
                if (stager.stageAndExtract(sel.cursorAsset, File(sel.cursorAsset).name, dest) != Extract.OK) {
                    onLog("Cursor ${sel.cursorName} not extracted on host")
                    ok = false
                }
            } else {
                onLog("Cursor ${sel.cursorName} already present — skip")
            }

            ensureWallpaper(openAsset, rootfs, sel, onLog)
            stageCategoryExtras(stager, rootfs, onLog)
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            onLog("Host XFCE asset install error: ${e.message}")
            ok = false
        }

        val installed = isSelectionInstalled(rootfs, sel)
        if (installed) {
            onLog("Host theme/icon install complete (native extract)")
        } else if (ok) {
            onLog("Host extract finished but selection not fully detected")
        }
        return installed
    }

    /**
     * Stage the selected theme/icon/cursor/wallpaper archives into a chroot
     * guest's `/tmp/flux_xfce_assets` (root copy). The guest customization
     * script finds them via its default `FLUX_ASSET_DIR` and extracts them
     * with guest tar (chroot /tmp is the rootfs /tmp — fluxlinux_chroot.sh
     * `ensure_sticky_tmp` never binds host tmp over it).
     *
     * @return true when every asset was staged (or already present).
     */
    fun installToChroot(
        ctx: Context,
        theme: String,
        chrootPath: String,
        onLog: (String) -> Unit = {}
    ): Boolean {
        val app = ctx.applicationContext
        val sel = selectionFor(theme)
        val cache = assetCacheDir(app, "flux_chroot_assets")
        val assets = listOf(
            sel.themeAsset to File(sel.themeAsset).name,
            ICON_ASSET to ICON_FILE,
            ICON_CATEGORIES_ASSET to ICON_CATEGORIES_FILE,
            sel.cursorAsset to File(sel.cursorAsset).name,
            sel.wallpaperAsset to sel.wallpaperFileName
        )
        val staged = mutableListOf<Pair<File, String>>()
        var allOk = true
        for ((asset, name) in assets) {
            val f = File(cache, name)
            if (!f.isFile || f.length() < 1024) {
                val copied = writeAtomically(f) { out ->
                    app.assets.open(asset).use { input -> input.copyTo(out) }
                }
                if (!copied) {
                    onLog("chroot asset missing: $asset")
                    allOk = false
                    continue
                }
            }
            if (f.isFile && f.length() >= 1024) {
                staged.add(f to name)
            }
        }
        if (staged.isEmpty()) return false
        val dst = "$chrootPath/tmp/flux_xfce_assets"
        val cmd = buildString {
            append("mkdir -p '$dst' && chmod 1777 '$dst' 2>/dev/null; ")
            for ((f, name) in staged) {
                append("cp -f '${f.absolutePath}' '$dst/$name' && chmod 644 '$dst/$name'; ")
            }
            append("echo STAGED_OK")
        }
        return try {
            val out = RootShell.capture(cmd, timeoutMs = 60_000L)
            if (out.contains("STAGED_OK")) {
                onLog("chroot assets staged at $dst")
                allOk
            } else {
                onLog("chroot root copy failed: $out")
                false
            }
        } catch (e: Exception) {
            onLog("chroot asset stage error: ${e.message}")
            false
        }
    }

    /** Stage + extract XFCE category extras onto Papirus-Dark (existing + new). */
    private fun stageCategoryExtras(stager: Stager, rootfs: File, onLog: (String) -> Unit) {
        val dest = File(rootfs, "usr/share/icons").also { it.mkdirs() }
        if (stager.stageAndExtract(ICON_CATEGORIES_ASSET, ICON_CATEGORIES_FILE, dest) == Extract.OK) {
            onLog("Seeded Papirus XFCE category icons")
        }
    }

    private fun ensureWallpaper(
        openAsset: (String) -> InputStream,
        rootfs: File,
        sel: Selection,
        onLog: (String) -> Unit
    ) {
        val destDir = File(rootfs, "home/flux/Pictures/Wallpapers").also { it.mkdirs() }
        val dest = File(destDir, sel.wallpaperFileName)
        if (dest.isFile && dest.length() > 10_000) return
        // Optional; atomic so a failed copy never leaves a truncated wallpaper behind.
        if (writeAtomically(dest) { out -> openAsset(sel.wallpaperAsset).use { it.copyTo(out) } }) {
            onLog("Wallpaper: ${sel.wallpaperFileName}")
        }
    }

    /**
     * Stages APK archives into the versioned host [cache] and the guest's [shared] dir,
     * then host-extracts them.
     */
    private class Stager(
        private val cache: File,
        private val shared: File,
        private val openAsset: (String) -> InputStream,
        private val extract: (archive: File, dest: File, onlyPrefix: String?) -> Extract,
        private val onLog: (String) -> Unit,
    ) {
        /**
         * Stages [assetPath] as [fileName] and extracts it into [dest]; with [onlyPrefix],
         * a filtered extract is tried first, then a full one. On [Extract.FAILED] the cached
         * copy is dropped so the next run re-stages it, and the shared copy is refreshed
         * from the APK, never removed: the guest script extracts it itself.
         */
        fun stageAndExtract(assetPath: String, fileName: String, dest: File, onlyPrefix: String? = null): Extract {
            val tar = materialize(assetPath, fileName) ?: run {
                onLog("No archive available ($fileName)")
                return Extract.FAILED
            }
            var result = if (onlyPrefix != null) extract(tar, dest, onlyPrefix) else Extract.FAILED
            if (result == Extract.FAILED) result = extract(tar, dest, null)
            if (result == Extract.FAILED) {
                onLog("Host extract failed for $fileName — re-staging it")
                tar.delete()
                writeAtomically(File(shared, fileName)) { out -> openAsset(assetPath).use { it.copyTo(out) } }
            }
            return result
        }

        private fun materialize(assetPath: String, fileName: String): File? {
            // The cache dir is keyed by the APK's lastUpdateTime ([assetCacheDir]) and every
            // file in it is written atomically, so a present file is a complete copy of the
            // current APK's asset. A copy that fails to extract is dropped ([stageAndExtract]).
            val cached = File(cache, fileName)
            if (!(cached.isFile && cached.length() > 1024)) {
                // APK assets (offline)
                val fromAssets = writeAtomically(cached) { out ->
                    openAsset(assetPath).use { input -> input.copyTo(out) }
                }
                if (!(fromAssets && cached.isFile && cached.length() > 1024)) return null
                onLog("Staged $fileName from app assets")
            }
            // Copy for the guest script's fallback path (`/tmp/flux_xfce_assets`), also atomic.
            writeAtomically(File(shared, fileName)) { out -> cached.inputStream().use { it.copyTo(out) } }
            return cached
        }
    }

    private const val CACHE_STAMP = ".apk_last_update"

    private fun assetCacheDir(app: Context, name: String): File {
        val stamp = runCatching {
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        return versionedCacheDir(app.cacheDir, name, stamp)
    }

    /**
     * `parent/name`, emptied when its stamp differs from [stamp] (the APK's lastUpdateTime),
     * so an app update with new theme assets never reuses archives cached from the old APK.
     */
    internal fun versionedCacheDir(parent: File, name: String, stamp: Long): File {
        val dir = File(parent, name)
        val stampFile = File(dir, CACHE_STAMP)
        val current = runCatching { stampFile.readText().trim() }.getOrNull()
        if (current != stamp.toString()) {
            SafeFiles.deleteTree(dir)
            dir.mkdirs()
            writeAtomically(stampFile) { it.write(stamp.toString().toByteArray()) }
        } else {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Writes [dest] through a sibling `.tmp` file and an atomic rename, so an interrupted or
     * failed write never leaves a truncated [dest]. Returns false (and removes the temp
     * file) when [write] or the rename fails; an existing [dest] is then left untouched.
     */
    internal fun writeAtomically(dest: File, write: (OutputStream) -> Unit): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                write(out)
                out.flush()
                out.fd.sync()
            }
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: Exception) {
            Log.w(TAG, "write ${dest.name} failed: ${e.message}")
            tmp.delete()
            false
        }
    }

    /**
     * Stock Android toybox `tar -J` execs an external `xz`, which the device lacks. Probed by
     * the first `.xz` extract; once missing, host `.xz` extracts are skipped for this process.
     */
    @Volatile
    private var hostXzMissing = false

    /** toybox reports `tar: exec xz: No such file or directory` (rc 127 from the child). */
    internal fun isMissingXz(exitCode: Int, output: String): Boolean =
        exitCode == 127 || output.contains("exec xz") || Regex("""\bxz: (not found|No such file)""").containsMatchIn(output)

    /**
     * @param onlyPrefix if set, pass as additional tar path args (e.g. "Papirus-Dark")
     */
    private fun hostTarExtract(
        archive: File,
        destDir: File,
        stripXz: Boolean,
        onlyPrefix: String?,
        onLog: (String) -> Unit
    ): Extract {
        val xz = archive.name.endsWith(".tar.xz") || stripXz && archive.name.endsWith(".xz")
        if (xz && hostXzMissing) return Extract.UNAVAILABLE
        destDir.mkdirs()
        val tarBin = when {
            File("/system/bin/tar").canExecute() -> "/system/bin/tar"
            else -> "tar"
        }
        val args = mutableListOf(tarBin)
        when {
            xz -> {
                args += listOf("-xJf", archive.absolutePath, "-C", destDir.absolutePath)
            }
            archive.name.endsWith(".tar.gz") || archive.name.endsWith(".tgz") -> {
                args += listOf("-xzf", archive.absolutePath, "-C", destDir.absolutePath)
            }
            else -> {
                args += listOf("-xf", archive.absolutePath, "-C", destDir.absolutePath)
            }
        }
        if (!onlyPrefix.isNullOrBlank()) {
            // Only extract the selected icon theme tree
            args += onlyPrefix
        }
        return try {
            val pb = ProcessBuilder(args)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            when {
                code == 0 -> Extract.OK
                xz && isMissingXz(code, out) -> {
                    hostXzMissing = true
                    onLog("Host tar has no xz — leaving .tar.xz archives to the guest")
                    Extract.UNAVAILABLE
                }
                else -> {
                    onLog("tar exit $code: ${out.take(400)}")
                    Extract.FAILED
                }
            }
        } catch (e: Exception) {
            onLog("tar failed: ${e.message}")
            Extract.FAILED
        }
    }

    /** Legacy icons.zip → nested tar.gz; extract only [onlyIcon] if present. */
    private fun hostUnzipNestedTars(
        zip: File,
        destDir: File,
        onlyIcon: String,
        onLog: (String) -> Unit
    ): Boolean {
        val tmp = File(zip.parentFile, "icons_unpack").also {
            SafeFiles.deleteTree(it)
            it.mkdirs()
        }
        return try {
            val pb = ProcessBuilder("unzip", "-q", "-o", zip.absolutePath, "-d", tmp.absolutePath)
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) return false
            val tars = mutableListOf<File>()
            SafeFiles.walkNoFollow(tmp) { f, attrs ->
                if (attrs.isRegularFile &&
                    (f.name.endsWith(".tar.gz") || f.name.endsWith(".tar.xz") || f.name.endsWith(".tgz"))
                ) {
                    tars += f
                }
            }
            var any = false
            for (t in tars) {
                val ok = hostTarExtract(
                    t, destDir,
                    stripXz = t.name.endsWith(".xz"),
                    onlyPrefix = onlyIcon,
                    onLog
                ) == Extract.OK
                if (ok) any = true
            }
            // If path filter failed (archive layout differs), full extract of first tar
            if (!any && tars.isNotEmpty()) {
                any = hostTarExtract(tars.first(), destDir, stripXz = false, onlyPrefix = null, onLog) == Extract.OK
            }
            any
        } catch (e: Exception) {
            onLog("unzip icons failed: ${e.message}")
            false
        } finally {
            SafeFiles.deleteTree(tmp)
        }
    }
}
