package com.ivarna.fluxlinux.core.desktop

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host-only desktop start/stop log (termux-lib `gui_desktop.log` parity).
 * Ring-capped file under [Context.getCacheDir]; never enters guest/isolation runners.
 *
 * L18: [append]/[header]/[clear] never touch the disk on the caller's (main) thread.
 * They are queued, in order, to one writer thread that keeps a [BufferedWriter] open,
 * flushes once per burst and rotates at [MAX_BYTES] (keeps the newest half).
 * [read]/[hasContent] first wait for queued writes so they see every appended line.
 */
object GuiDesktopLog {

    private const val FILE_NAME = "gui_desktop.log"
    internal const val MAX_BYTES = 512L * 1024L
    private const val DRAIN_TIMEOUT_MS = 1_000L

    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gui-desktop-log").apply { isDaemon = true }
    }
    private val flushQueued = AtomicBoolean(false)

    // Writer-thread confined.
    private var out: BufferedWriter? = null
    private var outFile: File? = null
    private var outBytes = 0L

    fun logFile(ctx: Context): File = File(ctx.cacheDir, FILE_NAME)

    fun clear(ctx: Context) {
        val f = logFile(ctx)
        writer.execute {
            closeOut()
            runCatching { f.writeText("") }
        }
    }

    fun header(ctx: Context, action: String, script: String, method: String) {
        append(ctx, "")
        append(ctx, "=== $action method=$method script=$script ===")
    }

    fun append(ctx: Context, line: String) {
        val f = logFile(ctx)
        writer.execute { write(f, "$line\n") }
        if (flushQueued.compareAndSet(false, true)) {
            writer.execute {
                flushQueued.set(false)
                runCatching { out?.flush() }
            }
        }
    }

    fun read(ctx: Context): String {
        drain()
        return try {
            val f = logFile(ctx)
            if (f.isFile) f.readText() else ""
        } catch (_: Exception) {
            ""
        }
    }

    fun hasContent(ctx: Context): Boolean {
        drain()
        return try {
            val f = logFile(ctx)
            f.isFile && f.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    /** Waits (bounded) until every queued write is on disk. */
    internal fun drain() {
        runCatching {
            writer.submit { runCatching { out?.flush() } }.get(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun write(f: File, text: String) {
        try {
            var w = out
            if (w == null || outFile != f) {
                closeOut()
                f.parentFile?.mkdirs()
                w = BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
                out = w
                outFile = f
                outBytes = f.length()
            }
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (outBytes + bytes > MAX_BYTES) {
                closeOut()
                val tail = f.readText().takeLast((MAX_BYTES / 2).toInt())
                f.writeText(tail + text)
                return // the next write reopens and re-reads the size
            }
            w.write(text)
            outBytes += bytes
        } catch (_: Exception) {
            closeOut()
        }
    }

    private fun closeOut() {
        runCatching { out?.close() }
        out = null
        outFile = null
        outBytes = 0L
    }
}
