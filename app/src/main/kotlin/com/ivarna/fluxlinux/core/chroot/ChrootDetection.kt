package com.ivarna.fluxlinux.core.chroot

import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.core.root.RootShell
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Auto-detection for chroot rootfs at a given path (Debian + Alpine).
 *
 * App SELinux often cannot read `/data/local/tmp` (`shell_data_file`), so bare
 * [File.exists] is a false negative. Detection order:
 * 1. Direct file probe (when policy allows)
 * 2. Root `test -e` probe (SSOT for install/settings)
 * 3. Short TTL cache per path for UI
 */
object ChrootDetection {

    private const val TAG = "ChrootDetection"
    private const val CACHE_TTL_MS = 30_000L

    private data class CacheEntry(val installed: Boolean, val atMs: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    data class Snapshot(
        val installed: Boolean,
        val markerOk: Boolean,
        val dirExists: Boolean,
        val viaRoot: Boolean,
        val rootOk: Boolean,
        /** False when the root probe failed / timed out: app-visible facts only, never cache. */
        val conclusive: Boolean = true,
    )

    fun chrootPath(): String = ChrootPaths.CHROOT_PATH

    fun rootfsDir(path: String = ChrootPaths.CHROOT_PATH): File = File(path)

    fun markerFile(path: String = ChrootPaths.CHROOT_PATH): File =
        File(path, ".flux_configured")

    fun isVisibleToApp(path: String = ChrootPaths.CHROOT_PATH): Boolean {
        return File("$path/.flux_configured").exists() ||
            shellPresentToApp(path) ||
            File(path).isDirectory
    }

    fun isMarkerVisibleToApp(path: String = ChrootPaths.CHROOT_PATH): Boolean =
        markerFile(path).exists()

    fun isDirVisibleToApp(path: String = ChrootPaths.CHROOT_PATH): Boolean =
        rootfsDir(path).isDirectory

    /**
     * True when chroot looks installed. Uses per-path cache; call [invalidate]
     * after install/uninstall.
     *
     * Main thread: app-visible + TTL cache only (never blocks on su).
     * Background: may root-probe when cache is cold.
     */
    fun isInstalled(path: String = ChrootPaths.CHROOT_PATH): Boolean {
        if (isVisibleToApp(path) && (isMarkerVisibleToApp(path) || shellPresentToApp(path))) {
            putCache(path, true)
            return true
        }
        val now = SystemClock.elapsedRealtime()
        val cached = cache[path]
        if (cached != null && now - cached.atMs < CACHE_TTL_MS) {
            return cached.installed
        }

        // Never block the UI thread on su discovery / probes.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return cached?.installed ?: false
        }

        val snap = probe(forceRoot = true, path = path)
        if (snap.conclusive) putCache(path, snap.installed)
        return snap.installed
    }

    fun invalidate() {
        cache.clear()
    }

    fun invalidate(path: String) {
        cache.remove(path)
    }

    /**
     * Record "not installed" so the next main-thread card check does not keep
     * returning a stale true after uninstall (TTL cache never re-probes on UI).
     */
    fun markUninstalled(path: String) {
        putCache(path, false)
    }

    /** Test hook: seed the per-path TTL cache. */
    internal fun putCacheForTest(path: String, installed: Boolean) {
        putCache(path, installed)
    }

    fun probe(
        forceRoot: Boolean = true,
        path: String = ChrootPaths.CHROOT_PATH
    ): Snapshot {
        val markerApp = isMarkerVisibleToApp(path)
        val dirApp = isDirVisibleToApp(path)
        val shellApp = shellPresentToApp(path)

        if (!forceRoot) {
            return Snapshot(
                installed = markerApp || shellApp,
                markerOk = markerApp,
                dirExists = dirApp || shellApp,
                viaRoot = false,
                rootOk = false
            )
        }

        if (!RootShell.isRootAvailable()) {
            val installed = markerApp || shellApp
            return Snapshot(
                installed = installed,
                markerOk = markerApp,
                dirExists = dirApp,
                viaRoot = false,
                rootOk = false
            )
        }

        val root = path
        val cap = RootShell.captureResult(
            "M=0; D=0; S=0; " +
                "[ -e '$root/.flux_configured' ] && M=1; " +
                "[ -d '$root' ] && D=1; " +
                // Alpine: bin/sh -> /bin/busybox (absolute); -e fails on host.
                "{ [ -L '$root/bin/sh' ] || [ -e '$root/bin/sh' ] || " +
                "[ -x '$root/bin/busybox' ] || [ -e '$root/usr/bin/bash' ] || " +
                "[ -e '$root/usr/bin/sh' ] || [ -e '$root/sbin/apk' ]; } && S=1; " +
                "echo MARKER=\$M DIR=\$D SHELL=\$S",
            timeoutMs = 8_000L
        )
        val flags = parseRootProbe(cap.exitCode, cap.stdout)
        if (flags == null) {
            // su timeout / failure (L4): report app-visible facts, flagged inconclusive so
            // isInstalled() does not cache a false "not installed" for 30 s.
            Log.w(TAG, "probe inconclusive exit=${cap.exitCode} out=${cap.stdout.take(120)}")
            return Snapshot(
                installed = markerApp || shellApp,
                markerOk = markerApp,
                dirExists = dirApp,
                viaRoot = false,
                rootOk = cap.exitCode != -1,
                conclusive = false
            )
        }
        val marker = markerApp || flags.marker
        val dir = dirApp || flags.dir
        val shell = shellApp || flags.shell

        // A leftover empty directory after uninstall is not an install.
        val installed = marker || shell
        putCache(path, installed)
        return Snapshot(
            installed = installed,
            markerOk = marker,
            dirExists = dir || shell,
            viaRoot = true,
            rootOk = true
        )
    }

    fun isXfceInstalled(chrootPath: String = ChrootPaths.CHROOT_PATH): Boolean {
        val xfce = "$chrootPath/usr/bin/startxfce4"
        val xfceSbin = "$chrootPath/usr/sbin/startxfce4"
        if (File(xfce).exists() || File(xfceSbin).exists()) return true
        if (!RootShell.isRootAvailable()) return false
        // Avoid blocking main thread
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        return try {
            RootShell.capture(
                "if [ -e '$xfce' ] || [ -e '$xfceSbin' ]; then echo YES; else echo NO; fi",
                timeoutMs = 8_000L
            ).contains("YES")
        } catch (_: Exception) {
            false
        }
    }

    /** Root probe flags; null when the probe did not run cleanly (non-zero exit / no line). */
    internal data class RootProbeFlags(val marker: Boolean, val dir: Boolean, val shell: Boolean)

    internal fun parseRootProbe(exitCode: Int, out: String): RootProbeFlags? {
        if (exitCode != 0) return null
        val line = out.lineSequence().map { it.trim() }.lastOrNull { it.contains("MARKER=") }
            ?: return null
        var marker = false
        var dir = false
        var shell = false
        line.split(Regex("\\s+")).forEach { tok ->
            when {
                tok.startsWith("MARKER=") -> marker = tok.removePrefix("MARKER=") == "1"
                tok.startsWith("DIR=") -> dir = tok.removePrefix("DIR=") == "1"
                tok.startsWith("SHELL=") -> shell = tok.removePrefix("SHELL=") == "1"
            }
        }
        return RootProbeFlags(marker, dir, shell)
    }

    private fun shellPresentToApp(path: String = ChrootPaths.CHROOT_PATH): Boolean {
        // Mirror TerminalLauncher.guestRootfsHasShell for Alpine absolute symlinks.
        val sh = File("$path/bin/sh")
        if (sh.exists()) return true
        try {
            if (java.nio.file.Files.isSymbolicLink(sh.toPath())) return true
        } catch (_: Exception) {
        }
        return File("$path/bin/busybox").isFile ||
            File("$path/usr/bin/bash").exists() ||
            File("$path/usr/bin/sh").exists() ||
            File("$path/sbin/apk").isFile
    }

    private fun putCache(path: String, installed: Boolean) {
        cache[path] = CacheEntry(installed, SystemClock.elapsedRealtime())
    }
}
