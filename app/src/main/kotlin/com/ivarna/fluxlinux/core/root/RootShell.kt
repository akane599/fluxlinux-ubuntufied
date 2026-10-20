package com.ivarna.fluxlinux.core.root

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * RootShell — Singleton for executing commands as root via KernelSU / Magisk su.
 * Ported from termux-lib `RootShellService`.
 *
 * Requirements:
 *  - KernelSU or Magisk must be installed on device.
 *  - The app package must be granted superuser access in the KSU / Magisk manager.
 *
 * All callbacks ([onLine], [onDone]) are dispatched on the main thread.
 */
object RootShell {

    private const val TAG = "RootShell"
    private val executor = Executors.newCachedThreadPool()
    // Lazy so JVM unit tests never touch Looper unless a callback path runs.
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Working su argv prefix, e.g. `["/system/bin/su","-c"]` or `["/system/bin/sh","-c"]` with
     * embedded su. Discovered once via [resolveSuInvocation]; null if root unavailable.
     *
     * IMPORTANT: do NOT gate on File.exists() — KernelSU/Magisk often hide su from the app
     * mount namespace until exec; File.exists() false positives skip the only working path.
     */
    @Volatile
    private var cachedSuInvocation: List<String>? = null

    @Volatile
    private var cachedBusyBoxPath: String? = null

    /** [ensureChrootHelper] succeeded this process (helper + resolver staged). */
    private val chrootHelperReady = AtomicBoolean(false)

    /** Re-stage on next [ensureChrootHelper] (an uninstall script may delete the helper). */
    fun invalidateChrootHelper() = chrootHelperReady.set(false)

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true if a working root shell (su) is available.
     * Runs synchronously — always call from a background thread.
     */
    fun isRootAvailable(): Boolean = resolveSuInvocation() != null

    /**
     * Async root probe. Runs [isRootAvailable] off the UI thread; [onResult] always on main.
     */
    fun probeRootAvailable(forceClearCache: Boolean = false, onResult: (Boolean) -> Unit) {
        executor.execute {
            if (forceClearCache) clearSuCache()
            val ok = try {
                isRootAvailable()
            } catch (_: Exception) {
                false
            }
            mainHandler.post { onResult(ok) }
        }
    }

    /** Result of a blocking root capture (exit code + stdout). */
    data class CaptureResult(val exitCode: Int, val stdout: String)

    /**
     * Stable error key for a [CaptureResult.exitCode] that means the command never really ran:
     * -2 → "timeout", -1 → "su_failed", otherwise null (a real exit status of the command).
     * Callers must not read an empty stdout from these as "nothing found" (L4).
     */
    fun failureReason(exitCode: Int): String? = when (exitCode) {
        -2 -> "timeout"
        -1 -> "su_failed"
        else -> null
    }

    /**
     * Capture stdout of a root command (blocking). Empty string on failure, and the exit code
     * is dropped: probes whose empty output would mean "absent"/"clean" must use
     * [captureResult] + [failureReason] instead.
     * Background thread only. [timeoutMs] > 0 aborts hung su (exit -2).
     */
    fun capture(cmd: String, timeoutMs: Long = 0L): String =
        captureResult(cmd, timeoutMs).stdout

    /**
     * Capture stdout + exit code of a root command (blocking).
     * Background thread only.
     *
     * The root shell reports its pid first (see [wrapWithPidMarker]); on timeout or cancel the
     * whole root process tree is killed through a fresh su ([killRootTree]) instead of only the
     * su client, so tar/apt/dpkg children cannot keep running or hold the output pipe (H3).
     * Cancel = [processHolder] no longer holds this job's Process (the owner cleared or
     * replaced it, e.g. `getAndSet(null)?.destroyForcibly()`); prefer [cancelRootJob].
     *
     * @param timeoutMs 0 = wait forever; >0 kills the job tree after timeout (exitCode -2).
     * @param onLine optional live line callback (reader thread) for install UIs.
     * @param processHolder optional live Process for cancel.
     * @return [CaptureResult] with exitCode -1 if no su / exception, -2 on timeout.
     */
    fun captureResult(
        cmd: String,
        timeoutMs: Long = 0L,
        onLine: ((String) -> Unit)? = null,
        processHolder: AtomicReference<Process?>? = null
    ): CaptureResult {
        val inv = resolveSuInvocation() ?: return CaptureResult(-1, "")
        return try {
            val args = buildSuArgs(inv, wrapWithPidMarker(cmd))
            val pb = ProcessBuilder(args).redirectErrorStream(true).start()
            try { pb.outputStream.close() } catch (_: Exception) {}
            val rootPid = AtomicReference<RootPid?>(null)
            // The holder gets a Process whose destroyForcibly() kills the root tree first, so
            // owners that only ever call destroyForcibly() on the holder still stop the job.
            val job = RootJobProcess(pb, rootPid)
            processHolder?.set(job)
            try {
                val sb = StringBuilder()
                val readerThread = Thread {
                    try {
                        pb.inputStream.bufferedReader().use { reader ->
                            var first = true
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val l = line ?: continue
                                if (first) {
                                    first = false
                                    val marker = parsePidMarker(l)
                                    if (marker != null) {
                                        rootPid.set(marker)
                                        continue
                                    }
                                }
                                synchronized(sb) { sb.append(l).append('\n') }
                                if (onLine != null) {
                                    try {
                                        onLine(l)
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }.also { it.isDaemon = true; it.start() }

                val deadline = if (timeoutMs > 0L) System.nanoTime() + timeoutMs * 1_000_000 else Long.MAX_VALUE
                var cancelled = false
                var timedOut = false
                while (!pb.waitFor(POLL_MS, TimeUnit.MILLISECONDS)) {
                    if (processHolder != null && processHolder.get() !== job) {
                        cancelled = true
                        break
                    }
                    if (System.nanoTime() - deadline >= 0) {
                        timedOut = true
                        break
                    }
                }
                if (!cancelled && processHolder != null && processHolder.get() !== job) {
                    // Owner destroyed the su client: the root side may still be running.
                    cancelled = true
                }
                if (timedOut || cancelled) {
                    job.killTreeThenDestroy()
                    try {
                        pb.waitFor(2, TimeUnit.SECONDS)
                        readerThread.join(1_000)
                    } catch (_: Exception) {
                    }
                    val partial = synchronized(sb) { sb.toString() }
                    if (timedOut) {
                        Log.w(TAG, "capture timeout after ${timeoutMs}ms cmd=${cmd.take(80)}")
                        return CaptureResult(-2, partial)
                    }
                    Log.w(TAG, "capture cancelled cmd=${cmd.take(80)}")
                    val code = try { pb.exitValue() } catch (_: Exception) { -1 }
                    return CaptureResult(if (code == 0) -1 else code, partial)
                }
                try {
                    readerThread.join(5_000)
                } catch (_: Exception) {
                }
                val code = try {
                    pb.exitValue()
                } catch (_: Exception) {
                    -1
                }
                CaptureResult(code, synchronized(sb) { sb.toString() })
            } finally {
                processHolder?.compareAndSet(job, null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "capture failed: ${e.message}")
            CaptureResult(-1, "")
        }
    }

    /**
     * Cancel a root job whose Process came from a [captureResult] holder: kill its whole root
     * process tree via a fresh su, then the su client. Safe on any Process (others are just
     * destroyed). Blocking (one short su call): background thread only. Plain
     * `holder.getAndSet(null)?.destroyForcibly()` does the same asynchronously.
     */
    fun cancelRootJob(process: Process?) {
        when (process) {
            null -> Unit
            is RootJobProcess -> process.killTreeThenDestroy()
            else -> process.destroyForcibly()
        }
    }

    /**
     * su client wrapper handed out through `processHolder`. [destroyForcibly] / [destroy]
     * never block the caller (may be the main thread): the root tree kill runs on [executor]
     * and the su client is destroyed right after it, so children cannot be orphaned first.
     */
    private class RootJobProcess(
        private val delegate: Process,
        private val rootPid: AtomicReference<RootPid?>,
    ) : Process() {
        private val killed = AtomicBoolean(false)

        fun killTreeThenDestroy() {
            if (killed.compareAndSet(false, true)) killRootTree(rootPid.get())
            delegate.destroyForcibly()
        }

        override fun destroyForcibly(): Process {
            try {
                executor.execute { killTreeThenDestroy() }
            } catch (_: Exception) {
                delegate.destroyForcibly()
            }
            return this
        }

        override fun destroy() {
            destroyForcibly()
        }

        override fun getOutputStream(): java.io.OutputStream = delegate.outputStream
        override fun getInputStream(): java.io.InputStream = delegate.inputStream
        override fun getErrorStream(): java.io.InputStream = delegate.errorStream
        override fun waitFor(): Int = delegate.waitFor()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = delegate.waitFor(timeout, unit)
        override fun exitValue(): Int = delegate.exitValue()
        override fun isAlive(): Boolean = delegate.isAlive
    }

    // ── H3: root process-tree tracking ──────────────────────────────────────

    private const val POLL_MS = 200L
    internal const val ROOT_PID_MARKER = "__FLUX_ROOT_PID__"

    /** Root shell identity: pid plus `/proc/pid/stat` starttime (guards against pid reuse). */
    internal data class RootPid(val pid: Int, val startTime: String)

    /**
     * Prefix [cmd] so the root shell first prints `__FLUX_ROOT_PID__ <pid> <starttime>`.
     * Builtins only (read/set/echo): no fork, works in mksh, toybox/busybox sh and dash.
     */
    internal fun wrapWithPidMarker(cmd: String): String =
        "{ read -r _fs < /proc/\$\$/stat; } 2>/dev/null; _fs=\${_fs##*) }; set -- \$_fs; " +
            "echo \"$ROOT_PID_MARKER \$\$ \${20}\"; set --; unset _fs; $cmd"

    internal fun parsePidMarker(line: String): RootPid? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2 || parts[0] != ROOT_PID_MARKER) return null
        val pid = parts[1].toIntOrNull()?.takeIf { it > 1 } ?: return null
        return RootPid(pid, parts.getOrNull(2)?.takeIf { s -> s.all { it.isDigit() } }.orEmpty())
    }

    /**
     * Root script: SIGSTOP then SIGKILL [p] and every descendant (ppid walk over /proc), plus
     * its process group / session members when the root shell leads them (Magisk-style setsid),
     * which also catches children re-parented to init. No-op when the pid is gone or its
     * starttime changed. Never signals a group the shell does not lead (under KernelSU the root
     * shell can share the app's process group).
     */
    internal fun buildTreeKillScript(p: RootPid): String = """
        P=${p.pid}; ST='${p.startTime}'
        { read -r s < /proc/${'$'}P/stat; } 2>/dev/null || exit 0
        s=${'$'}{s##*) }; set -- ${'$'}s
        [ -z "${'$'}ST" ] || [ "${'$'}{20}" = "${'$'}ST" ] || exit 0
        G=""; [ "${'$'}3" = "${'$'}P" ] && G=${'$'}P
        S=""; [ "${'$'}4" = "${'$'}P" ] && S=${'$'}P
        all=" ${'$'}P "; front="${'$'}P"
        while [ -n "${'$'}front" ]; do
          next=""
          for f in /proc/[0-9]*/stat; do
            { read -r s < "${'$'}f"; } 2>/dev/null || continue
            q=${'$'}{f#/proc/}; q=${'$'}{q%/stat}
            [ "${'$'}q" = "${'$'}${'$'}" ] && continue
            case "${'$'}all" in *" ${'$'}q "*) continue ;; esac
            s=${'$'}{s##*) }; set -- ${'$'}s
            hit=""
            case " ${'$'}front " in *" ${'$'}2 "*) hit=1 ;; esac
            [ -n "${'$'}G" ] && [ "${'$'}3" = "${'$'}G" ] && hit=1
            [ -n "${'$'}S" ] && [ "${'$'}4" = "${'$'}S" ] && hit=1
            if [ -n "${'$'}hit" ]; then all="${'$'}all${'$'}q "; next="${'$'}next ${'$'}q"; fi
          done
          front=${'$'}next
        done
        kill -STOP ${'$'}all 2>/dev/null
        kill -9 ${'$'}all 2>/dev/null
        echo "killed:${'$'}all"
    """.trimIndent()

    /** Kill a tracked root job tree through a fresh su (never wrapped, bounded). */
    private fun killRootTree(rootPid: RootPid?) {
        if (rootPid == null) return
        val inv = cachedSuInvocation ?: return
        val code = runCommand(buildSuArgs(inv, buildTreeKillScript(rootPid)), onLine = null, timeoutMs = 10_000L)
        Log.i(TAG, "killRootTree pid=${rootPid.pid} exit=$code")
    }

    /**
     * Execute a shell command as root.
     * [onLine] is called on the Main thread for each stdout/stderr line.
     * [onDone] is called on the Main thread with the exit code.
     */
    fun execute(
        cmd: String,
        onLine: (String) -> Unit = {},
        onDone: (Int) -> Unit = {}
    ) {
        executor.execute {
            val inv = resolveSuInvocation()
            val code = if (inv == null) {
                mainHandler.post { onLine("[RootShell] ERROR: no working su binary") }
                -1
            } else {
                runCommand(buildSuArgs(inv, wrapWithPidMarker(cmd)), onLine, tracked = true)
            }
            mainHandler.post { onDone(code) }
        }
    }

    /** Default bound for [executeSync]: staging / probe commands finish in well under this. */
    const val SYNC_TIMEOUT_MS = 30_000L

    /**
     * Execute a shell command as root synchronously (blocking).
     * Must be called from a background thread. Returns the exit code
     * (-1 no su / exception, -2 when [timeoutMs] expired and the process was killed).
     */
    fun executeSync(cmd: String, timeoutMs: Long = SYNC_TIMEOUT_MS): Int {
        val inv = resolveSuInvocation() ?: return -1
        return runCommand(
            buildSuArgs(inv, wrapWithPidMarker(cmd)),
            onLine = null,
            timeoutMs = timeoutMs,
            tracked = true,
        )
    }

    /**
     * Execute a shell script file as root.
     * [scriptPath] must be an absolute path to a script on the device.
     */
    fun executeScript(
        scriptPath: String,
        onLine: (String) -> Unit = {},
        onDone: (Int) -> Unit = {}
    ) {
        execute("sh \"$scriptPath\"", onLine, onDone)
    }

    /**
     * Copy an asset to the app-private `staged_scripts/` dir ([stageAsset]),
     * then run it as root.
     */
    fun executeScriptAsset(
        context: Context,
        assetName: String,
        onLine: (String) -> Unit = {},
        onDone: (Int) -> Unit = {}
    ) {
        executor.execute {
            val stagedPath = stageAsset(context, assetName)
            if (stagedPath == null) {
                mainHandler.post {
                    onLine("[RootShell] ERROR: Failed to stage asset '$assetName'")
                    onDone(-1)
                }
                return@execute
            }
            val inv = resolveSuInvocation()
            val code = if (inv == null) {
                mainHandler.post { onLine("[RootShell] ERROR: no working su binary") }
                -1
            } else {
                runCommand(
                    buildSuArgs(inv, wrapWithPidMarker("sh \"$stagedPath\"")),
                    onLine,
                    tracked = true,
                )
            }
            mainHandler.post { onDone(code) }
        }
    }

    /** Serialises su discovery so concurrent callers never raise parallel grant prompts (L7). */
    private val suResolveLock = Any()

    /** Outcome of one su probe; [TIMEOUT] usually means a grant dialog is still pending. */
    internal enum class SuProbe { OK, DENIED, TIMEOUT }

    /**
     * Discover a working su invocation. Tries absolute paths without File.exists(),
     * plus `sh -c` wrappers. Caches the first success. Background thread only.
     *
     * Single-flight: concurrent callers wait for the in-flight discovery and reuse its result.
     * A probe timeout stops the walk (the root manager is most likely showing a grant dialog;
     * trying the next binary would only raise another prompt). Nothing is cached on failure,
     * so the next call after the user answers probes again.
     */
    fun resolveSuInvocation(): List<String>? {
        cachedSuInvocation?.let { return it }
        synchronized(suResolveLock) {
            cachedSuInvocation?.let { return it }
            val found = walkSuTrials(SU_TRIALS, ::trySuProbe)
            if (found != null) {
                cachedSuInvocation = found
                Log.i(TAG, "resolveSuInvocation OK: $found")
            } else {
                Log.w(TAG, "resolveSuInvocation: no working su")
            }
            return found
        }
    }

    private val SU_TRIALS: List<List<String>> = listOf(
        // Direct (KernelSU / Magisk common)
        listOf("/system/bin/su", "-c"),
        listOf("/system/xbin/su", "-c"),
        listOf("/sbin/su", "-c"),
        listOf("/debug_ramdisk/su", "-c"),
        listOf("su", "-c"),
        // Magisk sometimes wants uid first: su 0 -c 'cmd'
        listOf("/system/bin/su", "0", "-c"),
        listOf("su", "0", "-c"),
        // sh -c wrapper form
        listOf("/system/bin/sh", "-c", "SU_WRAP:/system/bin/su"),
        listOf("/system/bin/sh", "-c", "SU_WRAP:su"),
        listOf("/system/bin/sh", "-c", "SU_WRAP:/debug_ramdisk/su"),
    )

    /**
     * Pure trial walk: first [SuProbe.OK] wins, [SuProbe.DENIED] moves on,
     * [SuProbe.TIMEOUT] stops the walk with null.
     */
    internal fun walkSuTrials(
        trials: List<List<String>>,
        probe: (List<String>) -> SuProbe,
    ): List<String>? {
        for (trial in trials) {
            when (probe(trial)) {
                SuProbe.OK -> return trial
                SuProbe.DENIED -> continue
                SuProbe.TIMEOUT -> {
                    Log.w(TAG, "su probe timed out on $trial; not trying further binaries")
                    return null
                }
            }
        }
        return null
    }

    /** Clear cached su (e.g. after user grants root in manager). */
    fun clearSuCache() {
        cachedSuInvocation = null
    }

    /**
     * Test-only hook: seed the discovered su invocation so JVM unit tests can
     * exercise argv builders without probing device `su` binaries.
     */
    fun seedSuInvocationForTest(invocation: List<String>?) {
        cachedSuInvocation = invocation
    }

    /**
     * Resolve a root-capable BusyBox via the staged shell resolver (su only).
     * Does not stage the sidecar — callers must [ensureBusyBoxResolver] first.
     * The resolver is only sourced after its SHA-256 matches the APK asset (M14).
     * Background thread only. Caches the first success.
     */
    fun resolveBusyBox(): String? {
        cachedBusyBoxPath?.let { return it }
        val sha = expectedSha(BusyBoxPaths.RESOLVER_ASSET) ?: run {
            Log.w(TAG, "resolveBusyBox: resolver hash unknown (ensureBusyBoxResolver not run)")
            return null
        }
        val pkg = com.ivarna.fluxlinux.BuildConfig.APPLICATION_ID
        val r = BusyBoxPaths.RESOLVER_ON_DEVICE
        val probe =
            "export FLUX_PACKAGE='$pkg'; ${SHA_FN}; " +
                "if ${shaMatches(r, sha)}; then " +
                ". '$r'; " +
                "resolve_bb && printf '%s\\n' \"\$BB\" && exit 0; " +
                "fi; exit 1"
        val result = captureResult(probe, timeoutMs = 8_000L)
        if (result.exitCode != 0) return null
        val path = result.stdout.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.startsWith("/") }
        if (path.isNullOrEmpty() || path.startsWith(LEGACY_SHELL_DIR)) return null
        cachedBusyBoxPath = path
        return path
    }

    /**
     * Async BusyBox probe. Runs [resolveBusyBox] off the UI thread; [onResult] on main.
     */
    fun probeBusyBox(forceClearCache: Boolean = false, onResult: (String?) -> Unit) {
        executor.execute {
            if (forceClearCache) clearBusyBoxCache()
            val path = try {
                resolveBusyBox()
            } catch (_: Exception) {
                null
            }
            mainHandler.post { onResult(path) }
        }
    }

    /** Cache only — never probes. */
    fun cachedBusyBox(): String? = cachedBusyBoxPath

    fun clearBusyBoxCache() {
        cachedBusyBoxPath = null
    }

    fun seedBusyBoxForTest(path: String?) {
        cachedBusyBoxPath = path
    }

    /**
     * Stage [BusyBoxPaths.RESOLVER_ASSET] to [BusyBoxPaths.RESOLVER_ON_DEVICE] (root-only
     * [ChrootPaths.ROOT_STAGE_DIR]) and verify its SHA-256 against the APK asset (M14).
     */
    fun ensureBusyBoxResolver(ctx: Context): Boolean =
        stageVerified(ctx, BusyBoxPaths.RESOLVER_ASSET, BusyBoxPaths.RESOLVER_ON_DEVICE)

    /**
     * Single shell snippet that runs [cmd] as root — for TerminalSession / `sh -c` only.
     * Escapes [cmd] in single quotes so `;` `&&` `$` inside the guest chain stay intact.
     */
    fun shellRootCommand(cmd: String): String {
        val escaped = cmd.replace("'", "'\\''")
        val inv = cachedSuInvocation ?: resolveSuInvocation()
        return when {
            inv != null && inv.size >= 3 && inv[2].startsWith("SU_WRAP:") -> {
                val suBin = inv[2].removePrefix("SU_WRAP:")
                "$suBin -c '$escaped'"
            }
            inv != null && inv.isNotEmpty() && inv.last() == "-c" -> {
                val prefix = inv.dropLast(1).joinToString(" ")
                "$prefix -c '$escaped'"
            }
            else -> "/system/bin/su -c '$escaped'"
        }
    }

    /**
     * Stage [ChrootPaths.CHROOT_HELPER] (root-only [ChrootPaths.ROOT_STAGE_DIR]) from assets
     * when missing or when its SHA-256 differs from the APK asset (M14). Uses [RootShell]
     * su discovery (not hard-coded `/system/bin/su`).
     * Safe to call from a background thread; may briefly invoke su.
     *
     * Lives here (not in ChrootCommandBuilder) so RootShell stays su-only and the
     * RootShell ↔ ChrootCommandBuilder cycle stays broken (plan §2.5).
     */
    @Synchronized // warm-up (TerminalToolSelector) and session prep may race on the cp
    fun ensureChrootHelper(ctx: Context): Boolean {
        if (chrootHelperReady.get()) return true
        return try {
            val bbOk = ensureBusyBoxResolver(ctx)
            val ok = stageVerified(ctx, ChrootPaths.CHROOT_HELPER_ASSET, ChrootPaths.CHROOT_HELPER)
            if (!ok) {
                Log.w(TAG, "ensureChrootHelper: stage/verify failed")
                return false
            }
            chrootHelperReady.set(bbOk)
            true
        } catch (e: Exception) {
            Log.w(TAG, "ensureChrootHelper failed: ${e.message}")
            false
        }
    }

    // ── M14: root-only staging with SHA-256 verification ────────────────────

    /** Old shell-writable staging dir (uid 2000 owns it): never execute anything from it. */
    private const val LEGACY_SHELL_DIR = "/data/local/tmp/"

    /** Expected SHA-256 per asset, computed from the APK (the signed APK is the trust root). */
    private val assetShaCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    @Volatile
    private var appContext: Context? = null

    internal fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun assetSha256(ctx: Context, asset: String): String? {
        appContext = ctx.applicationContext ?: ctx
        assetShaCache[asset]?.let { return it }
        return try {
            val sha = sha256Hex(ctx.assets.open(asset).use { it.readBytes() })
            assetShaCache[asset] = sha
            sha
        } catch (e: Exception) {
            Log.w(TAG, "assetSha256 $asset failed: ${e.message}")
            null
        }
    }

    /** Test-only: seed an asset's expected hash (JVM tests have no AssetManager). */
    internal fun seedAssetShaForTest(asset: String, sha: String?) {
        if (sha == null) assetShaCache.remove(asset) else assetShaCache[asset] = sha
    }

    private fun expectedSha(asset: String): String? =
        assetShaCache[asset] ?: appContext?.let { assetSha256(it, asset) }

    /**
     * `_fsha FILE` prints the file's SHA-256 (toybox, then root-manager BusyBox fallbacks;
     * never a BusyBox from a shell-writable dir).
     */
    internal const val SHA_FN =
        "_fsha() { for _t in sha256sum 'toybox sha256sum' '/data/adb/magisk/busybox sha256sum' " +
            "'/data/adb/ksu/bin/busybox sha256sum' '/data/adb/ap/bin/busybox sha256sum'; do " +
            "_o=\$(\$_t \"\$1\" 2>/dev/null) && [ -n \"\$_o\" ] && { echo \"\${_o%% *}\"; return 0; }; " +
            "done; return 1; }"

    /** Shell condition: [file] exists, is not a symlink, and hashes to [sha]. Needs [SHA_FN]. */
    internal fun shaMatches(file: String, sha: String): String =
        "{ [ -f '$file' ] && [ ! -L '$file' ] && [ \"\$(_fsha '$file')\" = '$sha' ]; }"

    /**
     * Create [ChrootPaths.ROOT_STAGE_DIR] (and its parent) as root:root 0700 and refuse
     * symlinks. Fails (non-zero) when not run as root, so nothing is staged in a dir another
     * uid could write.
     */
    internal fun stageDirSnippet(
        parent: String = ChrootPaths.ROOT_STAGE_PARENT,
        dir: String = ChrootPaths.ROOT_STAGE_DIR,
    ): String =
        "( umask 077; mkdir -p '$dir' ) && [ ! -L '$parent' ] && [ ! -L '$dir' ] && " +
            "chown 0:0 '$parent' '$dir' && chmod 0700 '$parent' '$dir'"

    /**
     * Write [dest] from a source file ([srcWord], an already-quoted shell word) or from
     * [b64] via a same-dir temp file, verify the temp's SHA-256 and only then rename it in
     * place (a running `sh helper` never sees a half-written file). Needs [SHA_FN].
     */
    internal fun installSnippet(dest: String, sha: String, srcWord: String? = null, b64: String? = null): String {
        val write = when {
            srcWord != null -> "cp -f $srcWord \"\$_T\""
            b64 != null -> "echo '$b64' | base64 -d > \"\$_T\""
            else -> error("installSnippet needs srcWord or b64")
        }
        return "{ _T='$dest.stage.'\$\$; rm -f \"\$_T\"; $write && chmod 0600 \"\$_T\" && " +
            "[ \"\$(_fsha \"\$_T\")\" = '$sha' ] && mv -f \"\$_T\" '$dest' || " +
            "{ rm -f \"\$_T\"; false; }; }"
    }

    /** Pre-M14 copies in /data/local/tmp; removed (never executed) once the new stage works. */
    internal val LEGACY_ARTIFACTS: List<String> = listOf(
        ChrootPaths.LEGACY_CHROOT_HELPER,
        BusyBoxPaths.LEGACY_RESOLVER,
        BusyBoxPaths.LEGACY_PINNED,
    )

    internal fun buildStageCommand(dest: String, sha: String, srcWord: String? = null, b64: String? = null): String =
        "$SHA_FN; ${stageDirSnippet()} && ${installSnippet(dest, sha, srcWord, b64)} && " +
            "{ rm -f ${LEGACY_ARTIFACTS.joinToString(" ") { "'$it'" }} 2>/dev/null; true; }"

    /**
     * Ensure [dest] (inside the root-only stage dir) holds exactly [asset]: no-op when the
     * hash already matches, else copy from the app-private staged copy, else stream base64.
     */
    private fun stageVerified(ctx: Context, asset: String, dest: String): Boolean {
        return try {
            val sha = assetSha256(ctx, asset) ?: return false
            if (captureResult("$SHA_FN; ${shaMatches(dest, sha)}", timeoutMs = 8_000L).exitCode == 0) {
                return true
            }
            val staged = stageAsset(ctx, asset)
            var code = if (staged != null) {
                executeSync(buildStageCommand(dest, sha, srcWord = "'${staged.replace("'", "'\\''")}'"))
            } else {
                -1
            }
            if (code != 0) {
                // su may be unable to read app-private storage: stream the bytes instead.
                val bytes = ctx.assets.open(asset).use { it.readBytes() }
                val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                code = executeSync(buildStageCommand(dest, sha, b64 = b64))
            }
            if (code != 0) {
                Log.w(TAG, "stageVerified $asset -> $dest failed exit=$code")
                return false
            }
            Log.i(TAG, "stageVerified $asset -> $dest")
            true
        } catch (e: Exception) {
            Log.w(TAG, "stageVerified $asset failed: ${e.message}")
            false
        }
    }

    /**
     * Run a command inside the Debian 13 chroot as [user] via SSOT helper.
     * Pass [context] so the helper is staged from assets when missing.
     */
    fun executeInChroot(
        cmd: String,
        user: String = "flux",
        chrootPath: String = ChrootPaths.CHROOT_PATH,
        onLine: (String) -> Unit = {},
        onDone: (Int) -> Unit = {},
        context: Context? = null
    ) {
        context?.let { ensureChrootHelper(it) }
        val u = if (user == "root") "root" else "flux"
        val b64 = android.util.Base64.encodeToString(
            cmd.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        execute(buildChrootHelperCmd(u, b64, chrootPath), onLine, onDone)
    }

    /**
     * Blocking capture inside chroot (bg thread only). Prefer for probe/status.
     * Pass [context] to stage helper from assets when missing.
     */
    fun captureInChroot(
        cmd: String,
        user: String = "flux",
        chrootPath: String = ChrootPaths.CHROOT_PATH,
        timeoutMs: Long = 60_000L,
        context: Context? = null,
        onLine: ((String) -> Unit)? = null,
        processHolder: AtomicReference<Process?>? = null
    ): CaptureResult {
        context?.let { ensureChrootHelper(it) }
        val u = if (user == "root") "root" else "flux"
        val b64 = android.util.Base64.encodeToString(
            cmd.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP
        )
        return captureResult(
            buildChrootHelperCmd(u, b64, chrootPath),
            timeoutMs,
            onLine = onLine,
            processHolder = processHolder
        )
    }

    /**
     * `sh helper b64 …` from the root-only stage, only after the helper's SHA-256 matches the
     * APK asset (M14). Self-heal: when missing/mismatched, re-stage from the app-private copies
     * (verified before the rename). Without a known hash it refuses to run (exit 127).
     */
    internal fun buildChrootHelperCmd(user: String, b64: String, chrootPath: String): String {
        val helper = ChrootPaths.CHROOT_HELPER
        val pkg = com.ivarna.fluxlinux.BuildConfig.APPLICATION_ID
        val prefix = "/data/data/$pkg/files/usr"
        val hostTmp = "$prefix/tmp"
        val bb = cachedBusyBox()
        val fluxBb = if (!bb.isNullOrEmpty()) "export FLUX_BB='$bb'; " else ""
        val sha = expectedSha(ChrootPaths.CHROOT_HELPER_ASSET)
        // Always pin package paths so zenithblue (and future flavors) do not fall
        // back to the script's ivarna defaults when FLUX_* is unset.
        //
        // Android /system/bin/sh rejects `VAR=val if …; then` (assignment before
        // compound command → "syntax error: unexpected 'then'"). Use export +
        // semicolons so the if-list is a normal compound statement.
        val env = "${fluxBb}export FLUX_PACKAGE='$pkg' FLUX_PREFIX='$prefix' " +
            "FLUX_HOST_TMP='$hostTmp' FLUX_CHROOT='$chrootPath'; "
        if (sha == null) {
            return env + "echo '[RootShell] helper hash unknown — open a chroot session once to stage $helper' >&2; exit 127"
        }
        return env + "$SHA_FN; " +
            "if ! ${shaMatches(helper, sha)}; then ${stageDirSnippet()} && " +
            "for _s in " +
            "/data/data/$pkg/files/staged_scripts/fluxlinux_chroot.sh " +
            "/data/data/$pkg/files/home/fluxlinux_chroot.sh; do " +
            "[ -f \"\$_s\" ] && ${installSnippet(helper, sha, srcWord = "\"\$_s\"")} && break; " +
            "done; fi; " +
            "if ${shaMatches(helper, sha)}; then sh '$helper' b64 --user $user -- $b64; " +
            "else echo '[RootShell] $helper missing or failed SHA-256 check — reinstall chroot or open a chroot session once' >&2; exit 127; fi"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildSuArgs(invocation: List<String>, cmd: String): List<String> {
        if (invocation.size >= 3 && invocation[2].startsWith("SU_WRAP:")) {
            val suBin = invocation[2].removePrefix("SU_WRAP:")
            val escaped = cmd.replace("'", "'\\''")
            return listOf(invocation[0], invocation[1], "$suBin -c '$escaped'")
        }
        return invocation + cmd
    }

    private fun trySuProbe(invocation: List<String>): SuProbe {
        return try {
            val args = buildSuArgs(invocation, "id")
            Log.d(TAG, "trySuProbe: $args")
            val pb = ProcessBuilder(args).redirectErrorStream(true).start()
            try { pb.outputStream.close() } catch (_: Exception) {}
            // Read first (small output) — avoids pipe deadlock; process exits on deny quickly.
            val outFuture = executor.submit<String> {
                pb.inputStream.bufferedReader().use { it.readText() }
            }
            // Hard timeout: a hanging su prompt must not block app/test threads.
            val finished = pb.waitFor(2, TimeUnit.SECONDS)
            if (!finished) {
                pb.destroyForcibly()
                Log.w(TAG, "trySuProbe timeout: $args")
                return SuProbe.TIMEOUT
            }
            val out = try {
                outFuture.get(1, TimeUnit.SECONDS)
            } catch (_: Exception) {
                ""
            }
            val code = pb.exitValue()
            Log.d(TAG, "trySuProbe exit=$code out=${out.trim().take(120)}")
            if (code == 0 && out.contains("uid=0")) SuProbe.OK else SuProbe.DENIED
        } catch (e: Exception) {
            Log.w(TAG, "trySuProbe fail: ${e.message}")
            SuProbe.DENIED
        }
    }

    /**
     * Streams stdout+stderr from a ProcessBuilder command, returns exit code.
     * [onLine] (main thread) may be null for silent sync calls. [timeoutMs] > 0 bounds the
     * wait: on expiry the process is destroyed and -2 is returned (M15).
     */
    private fun runCommand(
        args: List<String>,
        onLine: ((String) -> Unit)?,
        timeoutMs: Long = 0L,
        tracked: Boolean = false,
    ): Int {
        Log.d(TAG, "runCommand: ${args.joinToString(" ").take(200)}")
        return try {
            val pb = ProcessBuilder(args)
                .redirectErrorStream(true)
                .start()
            try { pb.outputStream.close() } catch (_: Exception) {}

            val rootPid = AtomicReference<RootPid?>(null)
            val reader = Thread {
                try {
                    BufferedReader(InputStreamReader(pb.inputStream)).use { r ->
                        var first = tracked
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            if (first) {
                                first = false
                                val marker = parsePidMarker(l)
                                if (marker != null) {
                                    rootPid.set(marker)
                                    continue
                                }
                            }
                            Log.d(TAG, l)
                            if (onLine != null) mainHandler.post { onLine(l) }
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }
            val finished = if (timeoutMs > 0L) {
                pb.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                pb.waitFor()
                true
            }
            if (!finished) {
                if (tracked) killRootTree(rootPid.get())
                pb.destroyForcibly()
                reader.join(500)
                Log.w(TAG, "runCommand timeout after ${timeoutMs}ms")
                return -2
            }
            // Grandchildren may keep the pipe open; bound the drain.
            reader.join(5_000)
            val code = pb.exitValue()
            Log.d(TAG, "runCommand exit=$code")
            code
        } catch (e: Exception) {
            Log.e(TAG, "runCommand exception: ${e.message}")
            if (onLine != null) mainHandler.post { onLine("[RootShell] Exception: ${e.message}") }
            -1
        }
    }

    /**
     * Copy an asset to [filesDir]/staged_scripts/, make executable, return absolute path or null.
     * Background thread only when followed by root I/O.
     */
    fun stageAsset(context: Context, assetName: String): String? {
        return try {
            val dir = File(context.filesDir, "staged_scripts")
            dir.mkdirs()
            dir.setExecutable(true, false)
            dir.setReadable(true, false)

            val scriptFile = File(dir, File(assetName).name)
            context.assets.open(assetName).use { input ->
                FileOutputStream(scriptFile).use { output -> input.copyTo(output) }
            }
            scriptFile.setExecutable(true, false)
            scriptFile.setReadable(true, false)
            Log.d(TAG, "Staged asset $assetName → ${scriptFile.absolutePath}")
            scriptFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "stageAsset failed for $assetName: ${e.message}")
            null
        }
    }
}
