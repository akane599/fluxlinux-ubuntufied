package com.ivarna.fluxlinux.core.files

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Test [RootRunner]: runs the root scripts with the host `sh` (no su) in [cwd], so the very
 * scripts the chroot provider sends to su are exercised against temp-dir fixtures.
 */
internal class HostShellRunner(private val cwd: File) : RootRunner {
    val scripts = mutableListOf<String>()

    override fun start(script: String): RootJob {
        synchronized(scripts) { scripts += script }
        val p = ProcessBuilder("sh", "-c", script).directory(cwd).start()
        val err = StringBuilder()
        thread(isDaemon = true) { p.errorStream.bufferedReader().forEachLine { synchronized(err) { err.append(it).append('\n') } } }
        return object : RootJob {
            override val stdout: InputStream get() = p.inputStream
            override val stdin: OutputStream get() = p.outputStream
            override val isAlive: Boolean get() = p.isAlive
            override fun waitFor(timeoutMs: Long): Int? =
                if (p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) p.exitValue() else null
            override fun kill() {
                p.destroyForcibly()
            }
            override fun stderrText(): String = synchronized(err) { err.toString() }
        }
    }

    /** Runs [script] feeding [input] on stdin; returns the exit code. */
    fun runWithStdin(script: String, input: ByteArray): Int {
        val job = start(script)
        job.stdin.use { it.write(input) }
        job.stdout.readBytes()
        return job.waitFor(10_000) ?: -2
    }

    companion object {
        val hostPath: String = System.getenv("PATH") ?: "/usr/bin:/bin"
        val available: Boolean get() = File("/bin/sh").canExecute() && File("/proc/self/mounts").canRead()
    }
}

/** [RootOps] wrapper that records every path handed to root and counts calls. */
internal class RecordingOps(private val inner: RootOps) : RootOps {
    val paths = mutableListOf<String>()
    var calls = 0

    override fun chain(base: String, names: List<String>): RootSnapshot {
        calls++
        var p = base
        paths += base
        names.forEach { p = HostPaths.child(p, it); paths += p }
        return inner.chain(base, names)
    }

    override fun list(dir: String): RootSnapshot {
        calls++
        paths += dir
        return inner.list(dir)
    }

    override fun mounts(): List<String> {
        calls++
        return inner.mounts()
    }

    override fun mutate(m: RootMutation): Boolean {
        calls++
        when (m) {
            is RootMutation.Move -> paths += listOf(m.from, m.to)
            is RootMutation.Copy -> paths += listOf(m.from, m.to)
            is RootMutation.CreateFile -> paths += m.path
            is RootMutation.Mkdir -> paths += m.path
            is RootMutation.Delete -> paths += m.path
            else -> Unit
        }
        return inner.mutate(m)
    }

    override fun openRead(path: String) = throw UnsupportedOperationException()
    override fun openWrite(path: String, append: Boolean) = throw UnsupportedOperationException()
}
