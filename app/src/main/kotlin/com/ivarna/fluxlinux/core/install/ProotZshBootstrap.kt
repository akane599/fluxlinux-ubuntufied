package com.ivarna.fluxlinux.core.install

import android.content.Context
import android.util.Log
import com.ivarna.fluxlinux.core.system.SafeFiles
import com.ivarna.fluxlinux.core.terminal.GroupKillingProcess
import com.ivarna.fluxlinux.core.terminal.HostCommandBuilder
import com.ivarna.fluxlinux.core.terminal.ProcessGroupLauncher
import com.ivarna.fluxlinux.core.terminal.TermuxHostPaths
import com.ivarna.fluxlinux.core.terminal.ZshPluginSet
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host-side Oh My Zsh + essential plugins into the proot rootfs.
 *
 * Guest-side `rm -rf ~/.oh-my-zsh` and `curl | sh` install under proot often
 * hang for a long time (slow unlink of partial clones; unbounded network).
 * Native [SafeFiles.deleteTree] (never follows links) + host `git` (PREFIX) is
 * much more reliable.
 */
object ProotZshBootstrap {

    private const val TAG = "ProotZshBootstrap"
    private const val OMZ_URL = "https://github.com/ohmyzsh/ohmyzsh.git"
    private const val APT_TIMEOUT_SEC = 600L
    private const val THEME_URL =
        "https://raw.githubusercontent.com/zakaziko99/agnosterzak-ohmyzsh-theme/master/agnosterzak.zsh-theme"

    fun omzDir(rootfs: File): File = File(rootfs, "home/flux/.oh-my-zsh")

    fun isOmzInstalled(rootfs: File): Boolean =
        File(omzDir(rootfs), "oh-my-zsh.sh").isFile

    /**
     * @return true when [oh-my-zsh.sh] is present after this call.
     */
    fun install(
        ctx: Context,
        prootName: String = "debian",
        onLog: (String) -> Unit = {}
    ): Boolean {
        val app = ctx.applicationContext
        val rootfs = ProotXfceAssetInstaller.prootRootfs(app, prootName)
        if (!rootfs.isDirectory) {
            onLog("Proot rootfs missing — skip host Oh My Zsh")
            return false
        }
        val omz = omzDir(rootfs)
        if (isOmzInstalled(rootfs)) {
            onLog("Oh My Zsh already installed — skip")
            ensurePlugins(app, omz, prootName, onLog)
            return true
        }

        // Corrupt / partial: remove natively (do NOT rm under proot — that hangs)
        if (omz.exists()) {
            onLog("Removing incomplete Oh My Zsh (native delete)…")
            val ok = SafeFiles.deleteTree(omz)
            if (!ok && omz.exists()) {
                onLog("WARN: could not fully delete ${omz.absolutePath}")
            }
        }

        val git = File(app.filesDir, "usr/bin/git")
        if (!git.isFile) {
            onLog("Host git not found — guest will try short-timeout install")
            return false
        }

        onLog("Host git clone Oh My Zsh (shallow)…")
        val cloneOk = hostGitClone(app, OMZ_URL, omz, timeoutSec = 180, onLog)
        if (!cloneOk || !isOmzInstalled(rootfs)) {
            onLog("Oh My Zsh host clone failed or incomplete")
            // Leave no half tree for guest rm to thrash on
            if (omz.exists() && !isOmzInstalled(rootfs)) {
                SafeFiles.deleteTree(omz)
            }
            return false
        }
        onLog("Oh My Zsh ready")
        ensurePlugins(app, omz, prootName, onLog)
        return true
    }

    /** Once per process: a failed (offline) attempt waits for the next app start. */
    private val depsAttempted = AtomicBoolean(false)

    /**
     * Existing Ubuntu proot installs whose `.zshrc` carries the managed
     * [ZshPluginSet] block: clone missing external plugins (host git) and
     * install missing guest apt packages, on a daemon thread so opening a
     * terminal never waits. Cheap host stat checks gate it; the zshrc block
     * already skips whatever is still missing.
     */
    fun ensurePluginDepsAsync(ctx: Context, prootName: String) {
        if (!ZshPluginSet.appliesTo(prootName) || depsAttempted.get()) return
        val app = ctx.applicationContext
        val rootfs = ProotXfceAssetInstaller.prootRootfs(app, prootName)
        if (!isOmzInstalled(rootfs)) return
        val omz = omzDir(rootfs)
        val missingPlugins = ZshPluginSet.missingExternalPlugins(
            File(omz, "custom/plugins"), File(omz, "plugins"), prootName
        )
        val missingPkgs = ZshPluginSet.missingAptPackages(rootfs)
        if (missingPlugins.isEmpty() && missingPkgs.isEmpty()) return
        // A user who customized the plugin line did not ask for these packages.
        if (!ZshPluginSet.isManaged(File(rootfs, "home/flux/.zshrc"))) return
        if (!depsAttempted.compareAndSet(false, true)) return
        Thread({
            val log: (String) -> Unit = { Log.i(TAG, it) }
            try {
                if (missingPlugins.isNotEmpty() && File(app.filesDir, "usr/bin/git").isFile) {
                    val plugins = File(omz, "custom/plugins").apply { mkdirs() }
                    missingPlugins.forEach { (name, url) ->
                        clonePluginIfMissing(app, File(plugins, name), url, log)
                    }
                }
                if (missingPkgs.isNotEmpty() && File(TermuxHostPaths.PROOT_DISTRO).isFile) {
                    installGuestAptPackages(app, prootName, missingPkgs, log)
                }
            } catch (e: Exception) {
                Log.w(TAG, "zsh plugin deps failed: ${e.message}")
            }
        }, "flux-zsh-deps").apply { isDaemon = true }.start()
    }

    private fun installGuestAptPackages(
        app: Context,
        prootName: String,
        packages: List<String>,
        onLog: (String) -> Unit
    ) {
        onLog("Guest apt (best-effort): ${packages.joinToString(" ")}")
        val guest = ZshPluginSet.aptInstallScript(packages)
        val script = "exec python ${TermuxHostPaths.PROOT_DISTRO} login $prootName -- sh -c '$guest'"
        val code = runHostBash(app, script, APT_TIMEOUT_SEC, onLog)
        onLog(
            if (code == 0) "Guest zsh plugin packages ready"
            else "Guest zsh plugin packages incomplete (exit $code) — plugins stay skipped"
        )
    }

    private fun ensurePlugins(
        app: Context,
        omz: File,
        prootName: String,
        onLog: (String) -> Unit
    ) {
        val custom = File(omz, "custom")
        val plugins = File(custom, "plugins")
        val themes = File(custom, "themes")
        plugins.mkdirs()
        themes.mkdirs()

        ZshPluginSet.externalPluginsFor(prootName).forEach { (name, url) ->
            clonePluginIfMissing(app, File(plugins, name), url, onLog)
        }
        // Do NOT install zsh-autocomplete (known 30s+ startup under proot)

        val themeFile = File(themes, "agnosterzak.zsh-theme")
        if (!themeFile.isFile || themeFile.length() < 50) {
            onLog("Fetching agnosterzak theme…")
            httpGetToFile(THEME_URL, themeFile, onLog)
        }
    }

    private fun clonePluginIfMissing(
        app: Context,
        dest: File,
        url: String,
        onLog: (String) -> Unit
    ) {
        if (dest.isDirectory && dest.list()?.isNotEmpty() == true) {
            onLog("Plugin ${dest.name} already present — skip")
            return
        }
        if (dest.exists()) SafeFiles.deleteTree(dest)
        onLog("Host clone plugin ${dest.name}…")
        if (!hostGitClone(app, url, dest, timeoutSec = 90, onLog)) {
            onLog("Plugin ${dest.name} clone failed — skip")
            SafeFiles.deleteTree(dest)
        }
    }

    private fun hostGitClone(
        app: Context,
        url: String,
        dest: File,
        timeoutSec: Long,
        onLog: (String) -> Unit
    ): Boolean {
        dest.parentFile?.mkdirs()
        // Quote paths for shell
        val destQ = dest.absolutePath.replace("'", "'\\''")
        val script =
            "set -e; " +
                "export GIT_TERMINAL_PROMPT=0; " +
                "export GIT_HTTP_LOW_SPEED_LIMIT=1000; " +
                "export GIT_HTTP_LOW_SPEED_TIME=30; " +
                "rm -rf '$destQ'; " +
                "git clone --depth 1 --single-branch --quiet '$url' '$destQ'"
        val code = runHostBash(app, script, timeoutSec, onLog)
        if (code != 0) {
            if (code != null) onLog("git clone exit $code: $url")
            SafeFiles.deleteTree(dest)
            return false
        }
        return dest.isDirectory
    }

    /**
     * Run [script] with host libbash + host env; output goes to logcat.
     * @return exit code, or null on timeout (process killed) / launch error.
     */
    private fun runHostBash(
        app: Context,
        script: String,
        timeoutSec: Long,
        onLog: (String) -> Unit
    ): Int? {
        val bash = TermuxHostPaths.libBash(app).absolutePath
        return try {
            // Own process group: a timeout must also kill git/apt children, not just bash (H3).
            val pb = ProcessBuilder(*ProcessGroupLauncher.wrap(bash, arrayOf(bash, "-c", script)))
            HostCommandBuilder.applyTo(app, pb, forceHostSetup = false)
            pb.redirectErrorStream(true)
            runCatching { pb.directory(File(TermuxHostPaths.BIN)) }
            val proc = GroupKillingProcess(pb.start())
            runCatching { proc.outputStream.close() }
            Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        if (line.isNotBlank()) Log.d(TAG, line)
                    }
                } catch (_: Exception) {
                }
            }.also { it.isDaemon = true; it.start() }
            if (proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                proc.exitValue()
            } else {
                proc.destroyForcibly()
                onLog("host command timed out after ${timeoutSec}s")
                null
            }
        } catch (e: Exception) {
            onLog("host command error: ${e.message}")
            null
        }
    }

    private fun httpGetToFile(url: String, dest: File, onLog: (String) -> Unit) {
        try {
            dest.parentFile?.mkdirs()
            val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            conn.inputStream.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            conn.disconnect()
        } catch (e: Exception) {
            onLog("theme download failed: ${e.message}")
            dest.delete()
        }
    }
}
