package com.ivarna.fluxlinux.core.utils

import java.util.concurrent.TimeUnit

object RootUtils {

    /** Upper bound for one [runRootCommand]; a hung su (pending grant dialog) must not pin the caller. */
    private const val DEFAULT_TIMEOUT_MS = 15_000L

    /**
     * Checks if the device is rooted and if we can obtain root access.
     */
    fun isRootAvailable(): Boolean {
        return runRootCommand("id").isSuccess
    }

    /**
     * Runs a command as root (su). Blocking: background thread only.
     * Returns a Result object containing exit code and output.
     */
    fun runRootCommand(command: String): ShellResult =
        runWithStdin(listOf("su"), command, DEFAULT_TIMEOUT_MS)

    /**
     * Spawns [argv], feeds [script] + `exit` on stdin, and drains the merged stdout/stderr on a
     * helper thread while waiting at most [timeoutMs]. On timeout the process is destroyed and
     * the result is a failure. stderr is merged into [ShellResult.output]; on failure
     * [ShellResult.error] carries that text (or a timeout note) so callers can still surface it.
     */
    internal fun runWithStdin(argv: List<String>, script: String, timeoutMs: Long): ShellResult {
        var process: Process? = null
        try {
            val p = ProcessBuilder(argv).redirectErrorStream(true).start()
            process = p
            val out = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().use { r ->
                        val buf = CharArray(4096)
                        while (true) {
                            val n = r.read(buf)
                            if (n < 0) break
                            synchronized(out) { out.append(buf, 0, n) }
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply {
                isDaemon = true
                start()
            }
            try {
                p.outputStream.bufferedWriter().use { w ->
                    w.write(script)
                    w.write("\nexit\n")
                }
            } catch (_: Exception) {
                // su exited before reading stdin (denied); the exit code tells the story.
            }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
                reader.join(500)
                val partial = synchronized(out) { out.toString() }.trim()
                return ShellResult(
                    isSuccess = false,
                    output = partial,
                    error = "timed out after ${timeoutMs}ms",
                )
            }
            // Grandchildren may still hold the pipe; never block on them indefinitely.
            reader.join(2_000)
            val text = synchronized(out) { out.toString() }.trim()
            val ok = p.exitValue() == 0
            return ShellResult(
                isSuccess = ok,
                output = text,
                error = if (ok) "" else text,
            )
        } catch (e: Exception) {
            return ShellResult(false, "", e.message ?: "Unknown error")
        } finally {
            try {
                process?.destroy()
            } catch (_: Exception) {
            }
        }
    }

    data class ShellResult(
        val isSuccess: Boolean,
        val output: String,
        val error: String
    )
}
