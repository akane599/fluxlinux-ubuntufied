package com.ivarna.fluxlinux.core.terminal

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Cancel handle for a streamed shell process. */
class ShellJob(private val process: Process) {
    @Volatile
    var cancelled: Boolean = false
        private set

    fun cancel() {
        cancelled = true
        try {
            process.destroyForcibly()
        } catch (_: Exception) {
        }
    }
}

/**
 * Runs a job as its own process group so cancel/timeout reaches every descendant
 * (proot-distro → proot → tar/apt/git), not only the direct child (H3).
 *
 * The job is started by a small bash wrapper with job control on (`set -m`), which
 * puts it in a new process group whose id is the job's pid. No `setsid` binary is
 * needed (none is guaranteed on the host prefix, and `$PREFIX/bin` cannot be argv0
 * under W^X): the wrapper is the same nativeLibraryDir `libbash.so`. On SIGTERM the
 * wrapper SIGKILLs the whole group and exits 143; otherwise it exits with the
 * job's status. Job-status chatter from `wait` goes to /dev/null.
 */
internal object ProcessGroupLauncher {
    const val WRAPPER =
        "trap 'kill -9 -- -\"\$pid\" 2>/dev/null; exit 143' TERM HUP INT; " +
            "set -m; \"\$@\" & pid=\$!; wait \"\$pid\" 2>/dev/null"

    /** `bash -c WRAPPER flux-job cmd...` — [bash] must be the host libbash (or /bin/bash in tests). */
    fun wrap(bash: String, cmd: Array<String>): Array<String> =
        arrayOf(bash, "-c", WRAPPER, "flux-job", *cmd)
}

/**
 * A [Process] started via [ProcessGroupLauncher] whose [destroy]/[destroyForcibly]
 * kill the job's whole process group: SIGTERM makes the wrapper SIGKILL the group;
 * a wrapper that does not exit within [graceMs] is SIGKILLed, and the output stream
 * is then closed so a reader blocked by an escaped descendant (e.g. a daemon that
 * called setsid and still holds the pipe) unblocks. Non-blocking: escalation runs on
 * a daemon thread, so callers may cancel from the main thread.
 *
 * Root jobs can reuse this by passing a custom [killGroup].
 */
class GroupKillingProcess(
    private val delegate: Process,
    private val graceMs: Long = 2_000L,
    private val killGroup: (Process) -> Unit = { it.destroy() },
) : Process() {
    private val killed = AtomicBoolean(false)

    /** True once a kill was requested (readers treat a closed stream as cancellation). */
    val isKilled: Boolean get() = killed.get()

    override fun getOutputStream(): OutputStream = delegate.outputStream
    override fun getInputStream(): InputStream = delegate.inputStream
    override fun getErrorStream(): InputStream = delegate.errorStream
    override fun waitFor(): Int = delegate.waitFor()
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = delegate.waitFor(timeout, unit)
    override fun exitValue(): Int = delegate.exitValue()
    override fun isAlive(): Boolean = delegate.isAlive

    override fun destroy() {
        if (!killed.compareAndSet(false, true)) return
        runCatching { killGroup(delegate) }
        Thread({
            try {
                if (!delegate.waitFor(graceMs, TimeUnit.MILLISECONDS)) {
                    delegate.destroyForcibly()
                    delegate.waitFor(graceMs, TimeUnit.MILLISECONDS)
                }
                // Group is dead; give the reader a moment to drain to EOF, then make
                // sure it cannot stay blocked on a pipe an escaped process still holds.
                Thread.sleep(graceMs)
                runCatching { delegate.inputStream.close() }
            } catch (_: InterruptedException) {
                delegate.destroyForcibly()
            }
        }, "flux-group-kill").apply { isDaemon = true }.start()
    }

    override fun destroyForcibly(): Process {
        destroy()
        return this
    }
}

/** Runs shell commands with proper environment, supporting both proot and chroot.
 *  Ported from termux-lib `ShellCommandRunner`. */
object ShellCommandRunner {

    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Host ET_DYN tools (libbash.so / libproot.so) live under /data/app or /data/data.
     * Exec'd directly: the kernel resolves PT_INTERP (arm64 → linker64), and on
     * x86_64 hosts with NDK translation the native bridge engages only for direct
     * execs — prepending /system/bin/linker64 would invoke the x86_64 linker and
     * reject the arm64 ELF (EM_AARCH64 vs EM_X86_64).
     */
    private fun adjustHostCmd(cmd: Array<String>): Array<String> {
        return cmd
    }

    /**
     * Host commands run under the NDK translation layer on x86_64 devices; the
     * translation runner resolves the exec'd binary's argv[0] with realpath()
     * against the process CWD. Point the CWD at $PREFIX/bin so bare command
     * names (mkdir, cp, …) resolve to the prefix's applet symlinks.
     */
    private fun setHostCwd(pb: ProcessBuilder, cmd: Array<String>) {
        if (cmd.isEmpty()) return
        val exe = cmd[0]
        val underAppData =
            exe.startsWith("/data/data/") ||
                exe.startsWith("/data/app/") ||
                exe.startsWith("/data/user/")
        if (underAppData) {
            runCatching { pb.directory(File(TermuxHostPaths.BIN)) }
        }
    }

    /** Runs a command and returns its exit code. Output is consumed but not returned. */
    fun run(ctx: Context, cmd: Array<String>, envMap: Map<String, String>? = null): Int {
        val pb = ProcessBuilder(*adjustHostCmd(cmd)); setHostCwd(pb, cmd)
        applyEnvironment(ctx, pb, envMap)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val stream = proc.inputStream
        val buf = ByteArray(1024)
        while (stream.read(buf) != -1) { /* consume */ }
        return proc.waitFor()
    }

    /** Runs a command and returns combined stdout/stderr as a string (blocking). */
    fun runCapture(ctx: Context, cmd: Array<String>, envMap: Map<String, String>? = null): String {
        val pb = ProcessBuilder(*adjustHostCmd(cmd)); setHostCwd(pb, cmd)
        applyEnvironment(ctx, pb, envMap)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val text = proc.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        proc.waitFor()
        return text
    }

    /**
     * Blocking capture with exit code (call from bg thread).
     *
     * When [onLine] is set, each stdout/stderr line is delivered as it arrives
     * (same thread as the caller) so install UIs can show live logs.
     * Note: do not wrap with $PREFIX/bin/stdbuf — W^X (targetSdk 36) denies
     * exec from app-data prefix (EACCES). Host argv0 must be nativeLibraryDir.
     */
    fun runCaptureExit(
        ctx: Context,
        cmd: Array<String>,
        envMap: Map<String, String>? = null,
        /** Optional: store the live Process so callers can destroyForcibly on cancel. */
        processHolder: java.util.concurrent.atomic.AtomicReference<Process?>? = null,
        /** Optional: live line callback (bg thread). */
        onLine: ((String) -> Unit)? = null
    ): Pair<Int, String> {
        // Cancellable jobs (a holder is given) run as their own process group so
        // cancel kills proot-distro/tar/apt too, not just the direct bash child (H3).
        val launch = if (processHolder != null) {
            ProcessGroupLauncher.wrap(TermuxHostPaths.libBash(ctx).absolutePath, cmd)
        } else {
            adjustHostCmd(cmd)
        }
        val pb = ProcessBuilder(*launch); setHostCwd(pb, cmd)
        applyEnvironment(ctx, pb, envMap)
        return captureExit(pb, grouped = processHolder != null, processHolder, onLine)
    }

    /**
     * Context-free core of [runCaptureExit] (JVM-testable). When [grouped], [pb] must
     * be a [ProcessGroupLauncher.wrap] command and the holder receives a
     * [GroupKillingProcess].
     */
    internal fun captureExit(
        pb: ProcessBuilder,
        grouped: Boolean,
        processHolder: java.util.concurrent.atomic.AtomicReference<Process?>?,
        onLine: ((String) -> Unit)?,
    ): Pair<Int, String> {
        pb.redirectErrorStream(true)
        val raw = pb.start()
        val proc: Process = if (grouped) GroupKillingProcess(raw) else raw
        processHolder?.set(proc)
        return try {
            // Close stdin so interactive scripts (`read -p "Press Enter…"`) get EOF
            // instead of hanging forever under onboarding / non-TTY ProcessBuilder.
            try {
                proc.outputStream.close()
            } catch (_: Exception) {
            }
            val sb = StringBuilder()
            try {
                proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        sb.append(l).append('\n')
                        if (onLine != null) {
                            try {
                                onLine(l)
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                // A cancel closes the stream to unblock this reader; anything else is real.
                if (!(proc is GroupKillingProcess && proc.isKilled)) throw e
            }
            val text = sb.toString()
            val exit = try {
                proc.waitFor()
            } catch (_: InterruptedException) {
                proc.destroyForcibly()
                -1
            }
            exit to text
        } finally {
            processHolder?.compareAndSet(proc, null)
        }
    }

    /** Runs a command and streams each output line to [onLine] on the main thread. */
    fun runStreamed(
        ctx: Context,
        cmd: Array<String>,
        envMap: Map<String, String>? = null,
        onLine: (line: String) -> Unit,
        onDone: (exitCode: Int) -> Unit
    ) {
        runStreamedCancelable(ctx, cmd, envMap, onLine, onDone)
    }

    /**
     * Streamed run with cancel handle. [onLine]/[onDone] post to main thread.
     * Process starts on a worker thread; cancel is safe before/after start.
     */
    fun runStreamedCancelable(
        ctx: Context,
        cmd: Array<String>,
        envMap: Map<String, String>? = null,
        onLine: (line: String) -> Unit,
        onDone: (exitCode: Int) -> Unit
    ): ShellJob {
        // Own process group: cancel kills the script's children too (H3).
        val launch = ProcessGroupLauncher.wrap(TermuxHostPaths.libBash(ctx).absolutePath, cmd)
        val pb = ProcessBuilder(*launch); setHostCwd(pb, cmd)
        applyEnvironment(ctx, pb, envMap)
        pb.redirectErrorStream(true)

        // Placeholder process; real Process attached once started
        val holder = arrayOfNulls<Process>(1)
        val job = ShellJob(object : Process() {
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getInputStream() = java.io.ByteArrayInputStream(ByteArray(0))
            override fun getErrorStream() = java.io.ByteArrayInputStream(ByteArray(0))
            override fun waitFor(): Int = -1
            override fun exitValue(): Int = -1
            override fun destroy() {
                holder[0]?.destroyForcibly()
            }
            override fun destroyForcibly(): Process {
                holder[0]?.destroyForcibly()
                return this
            }
            override fun isAlive(): Boolean = holder[0]?.isAlive == true
        })

        Thread {
            val proc = try {
                if (job.cancelled) {
                    mainHandler.post { onDone(-1) }
                    return@Thread
                }
                GroupKillingProcess(pb.start()).also { holder[0] = it }
            } catch (_: Exception) {
                mainHandler.post { onDone(-1) }
                return@Thread
            }
            try {
                val reader = proc.inputStream.bufferedReader(Charsets.UTF_8)
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (job.cancelled) break
                    val l = line ?: continue
                    mainHandler.post { onLine(l) }
                }
            } catch (_: Exception) {
            }
            val exit = try {
                if (job.cancelled) {
                    try { proc.destroyForcibly() } catch (_: Exception) {}
                    -1
                } else {
                    proc.waitFor()
                }
            } catch (_: Exception) {
                -1
            }
            mainHandler.post { onDone(exit) }
        }.start()

        return job
    }

    private fun applyEnvironment(ctx: Context, pb: ProcessBuilder, envMap: Map<String, String>?) {
        val env = pb.environment()
        HostCommandBuilder.applyTo(ctx, pb, forceHostSetup = false)
        env["GIT_TERMINAL_PROMPT"] = "0"

        // Override with envMap if provided (e.g. chroot-specific values)
        envMap?.forEach { (k, v) ->
            env[k] = v
        }
    }
}
