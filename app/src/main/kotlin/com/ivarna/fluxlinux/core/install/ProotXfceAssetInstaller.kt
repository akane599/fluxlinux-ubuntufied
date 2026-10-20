package com.ivarna.fluxlinux.core.install

import android.content.Context
import android.util.Log
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.system.SafeFiles
import com.ivarna.fluxlinux.core.terminal.TermuxHostPaths
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
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
        val sel = selectionFor(theme)
        if (isSelectionInstalled(rootfs, sel)) {
            onLog("Themes/icons already installed (${sel.themeName} + ${sel.iconName}) — skip extract")
            // Still ensure wallpaper if missing (cheap)
            ensureWallpaper(app, rootfs, sel, onLog)
            stageCategoryExtras(app, rootfs, onLog)
            return true
        }

        val cache = assetCacheDir(app, "flux_xfce_assets")
        // Guest /tmp maps here with proot --shared-tmp (fallback path for script).
        val shared = File(TermuxHostPaths.TMPDIR, "flux_xfce_assets").also { it.mkdirs() }

        var ok = true
        try {
            if (!isThemeInstalled(rootfs, sel.themeName)) {
                onLog("Host-extract theme: ${sel.themeName}")
                val tar = materialize(
                    app, cache, shared,
                    assetPath = sel.themeAsset,
                    fileName = File(sel.themeAsset).name,
                    urlFallback = null,
                    onLog
                )
                val dest = File(rootfs, "usr/share/themes").also { it.mkdirs() }
                if (tar == null || !hostTarExtract(tar, dest, stripXz = true, onlyPrefix = null, onLog)) {
                    tar?.let { discardCached(it, shared) }
                    onLog("Theme extract failed for ${sel.themeName}")
                    ok = false
                }
            } else {
                onLog("Theme ${sel.themeName} already present — skip")
            }

            if (!isIconInstalled(rootfs, sel.iconName)) {
                onLog("Host-extract icons: $ICON_NAME (dark variant only)")
                val iconsTar = materialize(
                    app, cache, shared,
                    assetPath = ICON_ASSET,
                    fileName = ICON_FILE,
                    urlFallback = null,
                    onLog
                )
                val dest = File(rootfs, "usr/share/icons").also { it.mkdirs() }
                when {
                    iconsTar != null && iconsTar.name.endsWith(".tar.xz") -> {
                        // Archive contains only Papirus-Dark/; still pass prefix for safety
                        if (!hostTarExtract(
                                iconsTar, dest,
                                stripXz = true,
                                onlyPrefix = ICON_NAME,
                                onLog
                            )
                        ) {
                            // Retry full extract if path filter fails on some tar builds
                            if (!hostTarExtract(
                                    iconsTar, dest,
                                    stripXz = true,
                                    onlyPrefix = null,
                                    onLog
                                )
                            ) {
                                discardCached(iconsTar, shared)
                                onLog("Icon extract failed for $ICON_NAME")
                                ok = false
                            }
                        }
                    }
                    iconsTar != null &&
                        (iconsTar.name.endsWith(".tar.gz") || iconsTar.name.endsWith(".tgz")) -> {
                        // Archive contains only Papirus-Dark/; still pass prefix for safety
                        if (!hostTarExtract(
                                iconsTar, dest,
                                stripXz = false,
                                onlyPrefix = ICON_NAME,
                                onLog
                            )
                        ) {
                            // Retry full extract if path filter fails on some tar builds
                            if (!hostTarExtract(
                                    iconsTar, dest,
                                    stripXz = false,
                                    onlyPrefix = null,
                                    onLog
                                )
                            ) {
                                discardCached(iconsTar, shared)
                                onLog("Icon extract failed for $ICON_NAME")
                                ok = false
                            }
                        }
                    }
                    else -> {
                        onLog("No Papirus-Dark archive available ($ICON_FILE)")
                        ok = false
                    }
                }
            } else {
                onLog("Icons ${sel.iconName} already present — skip")
            }

            if (!isCursorInstalled(rootfs, sel.cursorName)) {
                onLog("Host-extract cursor: ${sel.cursorName}")
                val tar = materialize(
                    app, cache, shared,
                    assetPath = sel.cursorAsset,
                    fileName = File(sel.cursorAsset).name,
                    urlFallback = null,
                    onLog
                )
                val dest = File(rootfs, "usr/share/icons").also { it.mkdirs() }
                if (tar == null || !hostTarExtract(tar, dest, stripXz = true, onlyPrefix = null, onLog)) {
                    tar?.let { discardCached(it, shared) }
                    onLog("Cursor extract failed for ${sel.cursorName}")
                    ok = false
                }
            } else {
                onLog("Cursor ${sel.cursorName} already present — skip")
            }

            ensureWallpaper(app, rootfs, sel, onLog)
            stageCategoryExtras(app, rootfs, onLog)
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
    private fun stageCategoryExtras(app: Context, rootfs: File, onLog: (String) -> Unit) {
        val cache = assetCacheDir(app, "flux_xfce_assets")
        val shared = File(TermuxHostPaths.TMPDIR, "flux_xfce_assets").also { it.mkdirs() }
        val tar = materialize(
            app, cache, shared,
            assetPath = ICON_CATEGORIES_ASSET,
            fileName = ICON_CATEGORIES_FILE,
            urlFallback = null,
            onLog
        ) ?: return
        val dest = File(rootfs, "usr/share/icons")
        dest.mkdirs()
        if (hostTarExtract(tar, dest, stripXz = true, onlyPrefix = null, onLog)) {
            onLog("Seeded Papirus XFCE category icons")
        } else {
            discardCached(tar, shared)
        }
    }

    private fun ensureWallpaper(
        app: Context,
        rootfs: File,
        sel: Selection,
        onLog: (String) -> Unit
    ) {
        val destDir = File(rootfs, "home/flux/Pictures/Wallpapers").also { it.mkdirs() }
        val dest = File(destDir, sel.wallpaperFileName)
        if (dest.isFile && dest.length() > 10_000) return
        // Optional; atomic so a failed copy never leaves a truncated wallpaper behind.
        if (writeAtomically(dest) { out -> app.assets.open(sel.wallpaperAsset).use { it.copyTo(out) } }) {
            onLog("Wallpaper: ${sel.wallpaperFileName}")
        }
    }

    private fun materialize(
        app: Context,
        cache: File,
        shared: File,
        assetPath: String,
        fileName: String,
        urlFallback: String?,
        onLog: (String) -> Unit
    ): File? {
        // The cache dir is keyed by the APK's lastUpdateTime ([assetCacheDir]) and every
        // file in it is written atomically, so a present file is a complete copy of the
        // current APK's asset. A copy that fails to extract is dropped ([discardCached]).
        val cached = File(cache, fileName)
        if (cached.isFile && cached.length() > 1024) {
            stageShared(cached, shared)
            return cached
        }
        // APK assets (offline)
        val fromAssets = writeAtomically(cached) { out ->
            app.assets.open(assetPath).use { input -> input.copyTo(out) }
        }
        if (fromAssets && cached.isFile && cached.length() > 1024) {
            onLog("Staged $fileName from app assets")
            stageShared(cached, shared)
            return cached
        }
        if (urlFallback != null) {
            onLog("Downloading $fileName…")
            if (httpDownload(urlFallback, cached, onLog)) {
                stageShared(cached, shared)
                return cached
            }
        }
        return cached.takeIf { it.isFile && it.length() > 1024 }
    }

    /** Copy for the guest script's fallback path (`/tmp/flux_xfce_assets`), also atomic. */
    private fun stageShared(cached: File, shared: File) {
        writeAtomically(File(shared, cached.name)) { out -> cached.inputStream().use { it.copyTo(out) } }
    }

    /** Drops a cached archive (and its shared copy) that failed to extract, so the next run re-stages it. */
    private fun discardCached(archive: File, shared: File) {
        archive.delete()
        File(shared, archive.name).delete()
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

    private fun httpDownload(url: String, dest: File, onLog: (String) -> Unit): Boolean {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 120_000
                instanceFollowRedirects = true
            }
            val ok = try {
                writeAtomically(dest) { out -> conn.inputStream.use { it.copyTo(out) } }
            } finally {
                conn.disconnect()
            }
            if (!ok) onLog("Download failed: $url")
            ok && dest.isFile && dest.length() > 1024
        } catch (e: Exception) {
            onLog("Download failed: ${e.message}")
            false
        }
    }

    /**
     * @param onlyPrefix if set, pass as additional tar path args (e.g. "Papirus-Dark")
     */
    private fun hostTarExtract(
        archive: File,
        destDir: File,
        stripXz: Boolean,
        onlyPrefix: String?,
        onLog: (String) -> Unit
    ): Boolean {
        destDir.mkdirs()
        val tarBin = when {
            File("/system/bin/tar").canExecute() -> "/system/bin/tar"
            else -> "tar"
        }
        val args = mutableListOf(tarBin)
        when {
            archive.name.endsWith(".tar.xz") || stripXz && archive.name.endsWith(".xz") -> {
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
            if (code != 0) {
                onLog("tar exit $code: ${out.take(400)}")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            onLog("tar failed: ${e.message}")
            false
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
                )
                if (ok) any = true
            }
            // If path filter failed (archive layout differs), full extract of first tar
            if (!any && tars.isNotEmpty()) {
                any = hostTarExtract(tars.first(), destDir, stripXz = false, onlyPrefix = null, onLog)
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
