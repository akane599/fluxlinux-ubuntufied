package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.ivarna.fluxlinux.core.install.HostBootstrap
import com.ivarna.fluxlinux.core.install.RootfsDownloader
import com.ivarna.fluxlinux.core.system.SafeFiles
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicLong

/**
 * Extracts the host `bootstrap.tar` into the app filesDir as the embedded host PREFIX,
 * then applies the package rewrite + host env SSOT.
 *
 * Local-first (same model as [RootfsDownloader]):
 *  1. APK `assets/bootstrap.tar` (both flavors package it)
 *  2. Verified `$HOME/bootstrap_<applicationId>.tar`
 *  3. GitHub release tag `rootfs` download (repair fallback when the asset is missing)
 *
 * Download only runs from [ensureExtracted], which is always user-initiated
 * (Initialize Host / install).
 *
 * Pass 2 hardening:
 *  - version marker (`home/.fluxlinux/bootstrap.extracted`) + [isExtracted] checks BOTH
 *    marker and `libtermux-exec.so` — a lone file from a corrupt tree is NOT "extracted".
 *  - atomic-ish extract: write to `.bootstrap_extract_tmp/` then promote into filesDir.
 *  - [clearMarker] + [ensureExtracted](force=true) lets callers force a clean re-extract
 *    (e.g. after setup_termux validation failure).
 *
 * @param onProgress called from the background thread with (bytesDone, totalBytes, phaseLabel)
 */
object BootstrapInstaller {

    private const val TAG = "BootstrapInstaller"
    private const val ASSET_NAME = "bootstrap.tar"
    private const val TMP_DIR = ".bootstrap_extract_tmp"

    /** Bump when the extract/rewrite pipeline changes (invalidates old trees). */
    const val EXTRACT_VERSION = 2

    private const val MARKER_REL = "home/.fluxlinux/bootstrap.extracted"

    fun extractMarker(ctx: Context): File =
        File(ctx.filesDir, MARKER_REL)

    fun clearMarker(ctx: Context): Boolean =
        extractMarker(ctx).delete()

    /**
     * True only when the marker exists AND the last-created host file is present.
     * Deliberately strict: `libtermux-exec.so` alone does not prove a complete tree.
     */
    fun isExtracted(ctx: Context): Boolean {
        if (!TermuxHostPaths.termuxExec(ctx).exists()) return false
        val marker = extractMarker(ctx)
        if (!marker.isFile) return false
        val content = marker.readText().trim()
        val (version, pkg) = content.split("|", limit = 2).let { it.getOrNull(0) to it.getOrNull(1) }
        return version == EXTRACT_VERSION.toString() && pkg == TermuxHostPaths.PACKAGE
    }

    fun markExtracted(ctx: Context) {
        val marker = extractMarker(ctx)
        marker.parentFile?.mkdirs()
        marker.writeText("$EXTRACT_VERSION|${TermuxHostPaths.PACKAGE}")
    }

    /**
     * Extract the bootstrap if needed; otherwise re-apply SSOT rewrite + host env.
     *
     * @param force when true, always re-extract (used by recovery paths)
     * @return true when the PREFIX is complete and marked
     *
     * **Container safety (B2):** any existing `usr/var/lib/proot-distro` tree
     * (installed Debian guest) is moved aside before the prefix is replaced and
     * restored afterwards — a re-extract NEVER wipes an installed proot guest.
     * An aside stranded by an interrupted earlier run is recovered first
     * ([recoverStrandedAside]); the aside itself is never deleted.
     */
    fun ensureExtracted(
        ctx: Context,
        force: Boolean = false,
        onProgress: (done: Long, total: Long, phase: String) -> Unit = { _, _, _ -> }
    ): Boolean {
        val recovery = recoverStrandedAside(ctx.filesDir)
        if (recovery == AsideRecovery.FAILED) {
            Log.e(TAG, "preserve: stranded $ASIDE_NAME could not be recovered yet; kept in place")
        }
        if (!force && isExtracted(ctx)) {
            // Happy path (proot-opt-01): the prefix is already extracted AND the
            // package rewrite / host env were applied when markExtracted() ran.
            // Re-walking the whole prefix tree here on every terminal open is the
            // multi-second startup stall — skip it. Old trees predating the W^X
            // rewrite carry an older EXTRACT_VERSION marker → fail isExtracted →
            // full re-extract path below re-applies applyPackageToExtractedPrefix.
            return true
        }
        return try {
            val tmpDir = File(ctx.filesDir, TMP_DIR)
            SafeFiles.deleteTree(tmpDir)
            File(tmpDir, "usr/tmp").mkdirs()
            File(tmpDir, "usr/etc").mkdirs()
            File(ctx.filesDir, "home").mkdirs()

            val source = resolveBootstrapSource(ctx, onProgress) ?: throw IllegalStateException(
                "host bootstrap missing — place ${HostBootstrap.forApplicationId(ctx.packageName).fileName} " +
                    "in the app home directory or allow the GitHub download"
            )
            onProgress(0L, source.totalBytes, "Extracting host bootstrap")
            source.stream.use { input ->
                extractTarStream(
                    input,
                    tmpDir,
                    totalBytes = source.totalBytes,
                    onProgress = onProgress
                )
            }
            onProgress(source.totalBytes, source.totalBytes, "Finalizing host prefix")

            promotePrefixPreservingGuests(ctx.filesDir, tmpDir) {
                val rewritten = TermuxHostPaths.applyPackageToExtractedPrefix(ctx.filesDir, ctx)
                markExtracted(ctx)
                Log.i(TAG, "bootstrap extract done; rewrote $rewritten residual stock paths")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "bootstrap extract failed", e)
            clearMarker(ctx)
            SafeFiles.deleteTree(File(ctx.filesDir, TMP_DIR))
            false
        }
    }

    private data class BootstrapSource(val stream: InputStream, val totalBytes: Long)

    /**
     * APK asset first (zenithblue / older builds), then a SHA-pinned file under
     * `$HOME`, then GitHub `rootfs` download. Network is never used when a
     * verified local copy exists.
     */
    private fun resolveBootstrapSource(
        ctx: Context,
        onProgress: (done: Long, total: Long, phase: String) -> Unit
    ): BootstrapSource? {
        try {
            val stream = ctx.assets.open(ASSET_NAME, AssetManager.ACCESS_STREAMING)
            val total = try {
                ctx.assets.openFd(ASSET_NAME).use { it.length }
            } catch (_: Exception) {
                // Compressed (deflated) asset: openFd is unsupported, but a fresh
                // AssetInputStream reports the remaining uncompressed length.
                try {
                    stream.available().toLong()
                } catch (_: Exception) {
                    0L
                }
            }
            Log.i(TAG, "Using APK asset $ASSET_NAME ($total bytes)")
            return BootstrapSource(stream, total)
        } catch (_: Exception) {
            Log.i(TAG, "APK has no $ASSET_NAME — resolving release archive")
        }

        val pin = HostBootstrap.forApplicationId(ctx.packageName)
        val destDir = TermuxHostPaths.homeDir(ctx)
        val ok = RootfsDownloader.ensurePresent(
            destDir,
            pin,
            isCancelled = { false },
            onProgress = { p ->
                onProgress(
                    p.downloadedBytes,
                    p.totalBytes,
                    "Downloading host bootstrap from GitHub"
                )
            }
        )
        if (!ok) {
            Log.e(TAG, "Host bootstrap download failed for ${pin.fileName}")
            return null
        }
        val file = File(destDir, pin.fileName)
        return BootstrapSource(file.inputStream(), file.length())
    }

    /** Where installed proot guests live inside the host prefix. */
    private const val GUESTS_REL = "usr/var/lib/proot-distro"

    /** Same-FS parking spot for [GUESTS_REL] while `usr/` is replaced. Never deleted. */
    internal const val ASIDE_NAME = ".preserved_proot_distro"

    /** Tree moves used by the preserve/restore steps; injectable so tests can force failures. */
    internal class TreeOps(
        val move: (src: File, dst: File) -> Boolean = { src, dst -> src.renameTo(dst) },
        // Links (guest ~/storage -> shared storage) are copied as links, never followed.
        val copy: (src: File, dst: File) -> Boolean = { src, dst -> SafeFiles.copyTree(src, dst, overwrite = true) },
        val now: () -> Long = System::currentTimeMillis
    )

    internal enum class AsideRecovery { NONE, RESTORED, KEPT_AWAY, DEFERRED, FAILED }

    private fun existsNoFollow(f: File): Boolean = Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    /**
     * True when anything other than an empty real directory sits at [GUESTS_REL].
     * An empty placeholder dir is removed (a populated tree is never touched) so a
     * restore can rename into place.
     */
    private fun liveGuestsPresent(filesDir: File): Boolean {
        val live = File(filesDir, GUESTS_REL)
        if (Files.isDirectory(live.toPath(), LinkOption.NOFOLLOW_LINKS) && live.list()?.isEmpty() == true) {
            live.delete()
        }
        return existsNoFollow(live)
    }

    /**
     * Recovers a [ASIDE_NAME] stranded by an interrupted re-extract, before anything
     * is deleted or extracted:
     *  - no live guest tree, prefix present → move the aside back into place;
     *  - no prefix at all (`usr/` being re-created) → [AsideRecovery.DEFERRED]: the
     *    aside stays put and [promotePrefixPreservingGuests] restores it after extract;
     *  - live tree AND aside → neither is deleted; the aside is renamed to
     *    `ASIDE_NAME.<timestamp>` so a later preserve can never clobber it.
     */
    internal fun recoverStrandedAside(filesDir: File, ops: TreeOps = TreeOps()): AsideRecovery {
        if (!existsNoFollow(File(filesDir, ASIDE_NAME))) return AsideRecovery.NONE
        if (liveGuestsPresent(filesDir)) {
            return if (moveAsideAway(filesDir, ops) != null) AsideRecovery.KEPT_AWAY else AsideRecovery.FAILED
        }
        if (!Files.isDirectory(File(filesDir, "usr").toPath(), LinkOption.NOFOLLOW_LINKS)) {
            Log.w(TAG, "preserve: stranded $ASIDE_NAME found; restoring after the prefix is re-created")
            return AsideRecovery.DEFERRED
        }
        return if (restoreAside(filesDir, ops)) {
            Log.i(TAG, "preserve: restored stranded $ASIDE_NAME into $GUESTS_REL")
            AsideRecovery.RESTORED
        } else {
            AsideRecovery.FAILED
        }
    }

    /**
     * Replaces `filesDir/usr` with the extracted `tmpDir/usr` while keeping installed
     * guests: they are moved aside, the prefix is promoted, then they are moved back
     * (also restoring a [AsideRecovery.DEFERRED] aside). [onComplete] (apply rewrite +
     * mark extracted) runs only once the guests are fully back in place.
     *
     * @throws IllegalStateException when the prefix is invalid, the guests cannot be
     * preserved, or the copy-back is incomplete — the aside is then left in place for
     * the next run's [recoverStrandedAside] and nothing is marked.
     */
    internal fun promotePrefixPreservingGuests(
        filesDir: File,
        tmpDir: File,
        ops: TreeOps = TreeOps(),
        onComplete: () -> Unit
    ) {
        val tmpUsr = File(tmpDir, "usr")
        val dstUsr = File(filesDir, "usr")
        if (!File(tmpUsr, "lib/libtermux-exec.so").isFile) {
            throw IllegalStateException("extract missing usr/lib/libtermux-exec.so")
        }
        val preserved = preserveProotDistro(filesDir, ops)
        if (preserved == null && existsNoFollow(File(filesDir, GUESTS_REL))) {
            // Preservation failed but guests are still inside usr/: never delete them.
            throw IllegalStateException("could not preserve $GUESTS_REL; aborting re-extract")
        }
        SafeFiles.deleteTree(dstUsr)
        if (!tmpUsr.renameTo(dstUsr)) {
            // Same-FS rename should not fail; fall back to copy.
            SafeFiles.copyTree(tmpUsr, dstUsr, overwrite = true)
            SafeFiles.deleteTree(tmpUsr)
        }
        SafeFiles.deleteTree(tmpDir)

        if (existsNoFollow(File(filesDir, ASIDE_NAME))) {
            if (!restoreAside(filesDir, ops)) {
                throw IllegalStateException("guest copy-back incomplete; kept $ASIDE_NAME for recovery")
            }
            Log.i(TAG, "preserved installed proot-distro containers across re-extract")
        }
        onComplete()
    }

    /**
     * Move [GUESTS_REL] aside (same-FS rename) so a re-extract can restore installed
     * guests. An existing aside is never deleted: it is renamed away first. Returns
     * null when nothing to preserve, or when the copy fallback failed (the caller
     * then refuses to delete `usr/`).
     */
    private fun preserveProotDistro(filesDir: File, ops: TreeOps): File? {
        val src = File(filesDir, GUESTS_REL)
        if (!existsNoFollow(src)) return null
        val aside = File(filesDir, ASIDE_NAME)
        if (existsNoFollow(aside) && moveAsideAway(filesDir, ops) == null) {
            throw IllegalStateException("could not move existing $ASIDE_NAME away; aborting re-extract")
        }
        if (ops.move(src, aside)) return aside
        // Rename failed (unlikely on same FS) — copy so we never lose the guest.
        if (ops.copy(src, aside)) return aside
        Log.e(TAG, "preserve: could not copy proot-distro aside")
        return null
    }

    /**
     * Moves [ASIDE_NAME] back to [GUESTS_REL]. Never merges over a populated live
     * tree. Returns true only when the guests are completely back in place; on an
     * incomplete copy the aside is kept as the only complete copy.
     */
    private fun restoreAside(filesDir: File, ops: TreeOps): Boolean {
        val aside = File(filesDir, ASIDE_NAME)
        val live = File(filesDir, GUESTS_REL)
        if (liveGuestsPresent(filesDir)) {
            Log.e(TAG, "preserve: $GUESTS_REL already populated; keeping ${aside.absolutePath}")
            return false
        }
        live.parentFile?.mkdirs()
        if (ops.move(aside, live)) return true
        Log.w(TAG, "preserve: renameTo failed, copying proot-distro back")
        if (ops.copy(aside, live)) {
            SafeFiles.deleteTree(aside)
            return true
        }
        Log.e(TAG, "preserve: copy back incomplete; keeping ${aside.absolutePath}")
        return false
    }

    /** Renames [ASIDE_NAME] to a unique `ASIDE_NAME.<timestamp>`; null when that fails. */
    private fun moveAsideAway(filesDir: File, ops: TreeOps): File? {
        val stamp = ops.now()
        var dest = File(filesDir, "$ASIDE_NAME.$stamp")
        var n = 1
        while (existsNoFollow(dest)) dest = File(filesDir, "$ASIDE_NAME.$stamp.${n++}")
        if (!ops.move(File(filesDir, ASIDE_NAME), dest)) {
            Log.e(TAG, "preserve: could not rename $ASIDE_NAME to ${dest.name}; kept in place")
            return null
        }
        Log.w(TAG, "preserve: earlier $ASIDE_NAME kept as ${dest.absolutePath} (live guests also present)")
        return dest
    }

    /** Streaming tar extractor (POSIX ustar). Entries rooted at usr/ or data/data/<pkg>/files/. */
    private fun extractTarStream(
        inputStream: InputStream,
        targetDir: File,
        totalBytes: Long,
        onProgress: (done: Long, total: Long, phase: String) -> Unit
    ) {
        val buffer = ByteArray(512)
        val doneBytes = AtomicLong(0L)
        val stripPrefix = "data/data/${TermuxHostPaths.PACKAGE}/files/"

        fun report(bytes: Long) {
            if (totalBytes > 0L && bytes - doneBytes.get() >= 1024L * 1024L) {
                doneBytes.set(bytes)
                onProgress(bytes, totalBytes, "Extracting host bootstrap")
            }
        }

        var bytesReadTotal = 0L
        var pendingLongName: String? = null
        while (true) {
            var bytesRead = 0
            while (bytesRead < 512) {
                val r = inputStream.read(buffer, bytesRead, 512 - bytesRead)
                if (r == -1) break
                bytesRead += r
            }
            if (bytesRead < 512) break

            var allZero = true
            for (b in buffer) {
                if (b != 0.toByte()) { allZero = false; break }
            }
            if (allZero) break

            fun parseString(offset: Int, length: Int): String {
                var len = 0
                while (len < length && buffer[offset + len] != 0.toByte()) { len++ }
                return String(buffer, offset, len, Charsets.UTF_8)
            }

            var name: String
            val prefix = parseString(345, 155)
            if (pendingLongName != null) {
                name = pendingLongName!!
                pendingLongName = null
            } else {
                name = parseString(0, 100)
                if (prefix.isNotEmpty()) name = "$prefix/$name"
            }

            val sizeStr = parseString(124, 12).trim()
            val size = try { sizeStr.toLong(8) } catch (e: Exception) { 0L }
            val type = buffer[156].toInt().toChar()
            val linkName = parseString(157, 100)

            if (type == 'L') {
                val longNameBytes = ByteArray(size.toInt())
                var longNameRead = 0
                while (longNameRead < size) {
                    val r = inputStream.read(longNameBytes, longNameRead, (size - longNameRead).toInt())
                    if (r == -1) break
                    longNameRead += r
                }
                pendingLongName = String(longNameBytes, 0, longNameRead, Charsets.UTF_8).trimEnd('\u0000')
                val longNamePadding = ((512 - (size % 512)) % 512).toInt()
                if (longNamePadding > 0) skipBytes(inputStream, longNamePadding.toLong())
                continue
            }

            val relPath = name
                .replaceFirst("^$stripPrefix".toRegex(), "")
                .trimStart('/')
            if (relPath.isEmpty()) {
                val dataBlocks = Math.ceil(size.toDouble() / 512.0).toLong()
                skipBytes(inputStream, dataBlocks * 512L)
                continue
            }

            val outFile = File(targetDir, relPath)

            if (type == '5') {
                outFile.mkdirs()
            } else if (type == '2') {
                outFile.parentFile?.mkdirs()
                try {
                    java.nio.file.Files.createSymbolicLink(
                        outFile.toPath(),
                        java.nio.file.Paths.get(linkName)
                    )
                } catch (e: Exception) {
                    try {
                        val linkTarget = File(outFile.parentFile, linkName)
                        if (linkTarget.exists() && linkTarget.isFile) {
                            linkTarget.copyTo(outFile, overwrite = true)
                        }
                    } catch (_: Exception) {}
                }
            } else if (type == '0' || type == '\u0000') {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                    var remaining = size
                    val copyBuf = ByteArray(8192)
                    while (remaining > 0) {
                        val toRead = Math.min(copyBuf.size.toLong(), remaining).toInt()
                        val r = inputStream.read(copyBuf, 0, toRead)
                        if (r == -1) break
                        fos.write(copyBuf, 0, r)
                        remaining -= r
                        bytesReadTotal += r
                        report(bytesReadTotal)
                    }
                }
                val padding = ((512 - (size % 512)) % 512).toInt()
                if (padding > 0) {
                    skipBytes(inputStream, padding.toLong())
                }
                val modeStr = parseString(100, 8).trim()
                try {
                    val mode = modeStr.toInt(8)
                    if ((mode and 73) != 0) {
                        outFile.setExecutable(true, false)
                    }
                } catch (_: Exception) {}
            } else {
                val dataBlocks = Math.ceil(size.toDouble() / 512.0).toLong()
                skipBytes(inputStream, dataBlocks * 512L)
            }
        }
    }

    private fun skipBytes(inputStream: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = Math.min(buf.size.toLong(), remaining).toInt()
            val r = inputStream.read(buf, 0, toRead)
            if (r == -1) break
            remaining -= r
        }
    }
}
