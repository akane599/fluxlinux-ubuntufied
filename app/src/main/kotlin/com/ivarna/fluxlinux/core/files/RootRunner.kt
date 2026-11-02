package com.ivarna.fluxlinux.core.files

import android.os.Looper
import android.os.SystemClock
import com.ivarna.fluxlinux.core.root.RootShell
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Result of a bounded root script: exit code (-1 no root, -2 timeout, -3 output too large), stdout bytes. */
internal class RootResult(val exitCode: Int, val stdout: ByteArray, val stderr: String)

/** A running root script with raw byte stdin/stdout (stderr is drained internally). */
internal interface RootJob {
    val stdout: InputStream
    val stdin: OutputStream
    val isAlive: Boolean

    /** Exit code, or null when still running after [timeoutMs]. */
    fun waitFor(timeoutMs: Long): Int?

    /** Kills the whole root process tree (not just the su client). Idempotent. */
    fun kill()

    fun stderrText(): String
}

internal interface RootRunner {
    /** Starts [script] as root; null when root is unavailable. */
    fun start(script: String): RootJob?

    /** Runs [script] with empty stdin and collects stdout; never blocks longer than [timeoutMs] (+ kill). */
    fun run(script: String, timeoutMs: Long = TIMEOUT_MS): RootResult {
        val job = start(script) ?: return RootResult(-1, ByteArray(0), "")
        runCatching { job.stdin.close() }
        val out = ByteArrayOutputStream()
        val overflow = AtomicBoolean(false)
        val reader = thread(isDaemon = true, name = "flux-root-out") {
            try {
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = job.stdout.read(buf)
                    if (n < 0) break
                    if (out.size() + n > MAX_OUTPUT) {
                        overflow.set(true)
                        job.kill()
                        break
                    }
                    out.write(buf, 0, n)
                }
            } catch (_: IOException) {
            }
        }
        val code = job.waitFor(timeoutMs)
        if (code == null) {
            job.kill()
            reader.join(2_000)
            return RootResult(-2, ByteArray(0), job.stderrText())
        }
        reader.join(5_000)
        if (overflow.get()) return RootResult(-3, ByteArray(0), job.stderrText())
        return RootResult(code, out.toByteArray(), job.stderrText())
    }

    companion object {
        /** Per-command budget for every bounded root call. */
        const val TIMEOUT_MS = 10_000L
        const val MAX_OUTPUT = 64 * 1024 * 1024
    }
}

/**
 * [RootRunner] over [RootShell]'s cached su invocation. A failed su resolution is remembered
 * for [NO_ROOT_RETRY_MS] so DocumentsUI polling never turns into a stream of su probes;
 * [invalidate] (called from [UbuntuFiles.notifyRootsChanged]) forgets it.
 *
 * The root shell reports its pid on stderr first ([RootShell.wrapWithPidMarker]), so [RootJob.kill]
 * kills the root-side tree through [RootShell.buildTreeKillScript] (the su client alone may
 * not be killable by the app uid, e.g. KernelSU execs the shell in place).
 */
internal object SuRootRunner : RootRunner {
    private const val NO_ROOT_RETRY_MS = 30_000L

    @Volatile
    private var noRootUntil = 0L

    fun invalidate() {
        noRootUntil = 0L
    }

    override fun start(script: String): RootJob? {
        val main = Looper.getMainLooper()
        check(main == null || main.thread !== Thread.currentThread()) { "Root file access on the main thread" }
        val now = SystemClock.elapsedRealtime()
        if (now < noRootUntil) return null
        if (RootShell.resolveSuInvocation() == null) {
            noRootUntil = now + NO_ROOT_RETRY_MS
            return null
        }
        val wrapped = "{ ${RootShell.wrapWithPidMarker(":")}; } >&2\n$script"
        val proc = try {
            ProcessBuilder("/system/bin/sh", "-c", RootShell.shellRootCommand(wrapped)).start()
        } catch (_: IOException) {
            return null
        }
        return SuJob(proc)
    }

    private class SuJob(private val proc: Process) : RootJob {
        private val rootPid = AtomicReference<RootShell.RootPid?>(null)
        private val err = StringBuilder()
        private val killed = AtomicBoolean(false)

        init {
            thread(isDaemon = true, name = "flux-root-err") {
                try {
                    proc.errorStream.bufferedReader().forEachLine { line ->
                        val marker = if (rootPid.get() == null) RootShell.parsePidMarker(line) else null
                        if (marker != null) {
                            rootPid.set(marker)
                        } else {
                            synchronized(err) { if (err.length < 4096) err.append(line).append('\n') }
                        }
                    }
                } catch (_: IOException) {
                }
            }
        }

        override val stdout: InputStream get() = proc.inputStream
        override val stdin: OutputStream get() = proc.outputStream
        override val isAlive: Boolean get() = proc.isAlive

        override fun waitFor(timeoutMs: Long): Int? =
            if (proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) proc.exitValue() else null

        override fun kill() {
            if (killed.compareAndSet(false, true) && proc.isAlive) {
                rootPid.get()?.let { pid ->
                    runCatching { RootShell.captureResult(RootShell.buildTreeKillScript(pid), timeoutMs = 10_000L) }
                }
            }
            proc.destroyForcibly()
        }

        override fun stderrText(): String = synchronized(err) { err.toString() }
    }
}
