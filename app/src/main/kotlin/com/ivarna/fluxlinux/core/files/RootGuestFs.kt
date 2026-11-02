package com.ivarna.fluxlinux.core.files

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Root-side primitives behind [RootGuestFs] (a seam so tests can count and inspect calls). */
internal interface RootOps {
    /** lstat `base/n1`, `base/n1/n2`, … in ONE root call, stopping after a missing entry, a link or a non-dir. */
    fun chain(base: String, names: List<String>): RootSnapshot

    /** lstat (+ readlink for links) of every child of [dir] in ONE root call. */
    fun list(dir: String): RootSnapshot

    /** Mount points strictly inside the rootfs. */
    fun mounts(): List<String>

    /** True on success, false when refused/failed; throws [RootFsException] when root itself failed. */
    fun mutate(m: RootMutation): Boolean

    fun openRead(path: String): ParcelFileDescriptor

    fun openWrite(path: String, append: Boolean): ParcelFileDescriptor
}

/**
 * [GuestFs] for the Ubuntu chroot rootfs, which this app's uid cannot read: everything goes
 * through [RootOps] (su). One instance serves ONE provider call: results are cached for the
 * call, so resolving a path and listing a directory cost a constant number of su calls
 * (never one per child).
 *
 * Confinement: every path is checked to lie inside [rootfs] before any root command
 * ([requireInside]); [GuestTree] has already resolved links guest-style and only asks about
 * paths with link-free parents. Anything on a filesystem mounted inside the rootfs (the
 * chroot helper binds /dev, /sys, /proc, /sdcard, …) is list-only: never opened, written,
 * created in, deleted, moved or copied, and nothing containing such a mount is removed or
 * copied recursively. The root scripts re-check all of this as root ([RootScripts]).
 */
internal class RootGuestFs(private val ops: RootOps, val rootfs: String) : GuestFs {

    private val stats = HashMap<String, GuestFs.Stat?>()
    private val links = HashMap<String, String>()
    private val listed = HashSet<String>()
    private var mounts: List<String>? = null

    private fun requireInside(path: String) {
        if (!HostPaths.isUnder(path, rootfs)) throw RootFsException("Outside the Ubuntu chroot: $path")
    }

    private fun absorb(snap: RootSnapshot) {
        snap.mounts?.let { mounts = it }
        for (e in snap.entries) {
            stats[e.path] = e.stat
            if (e.linkTarget != null) links[e.path] = e.linkTarget else links.remove(e.path)
        }
    }

    private fun invalidate() {
        stats.clear()
        links.clear()
        listed.clear()
        mounts = null
    }

    override fun realPath(path: String): String? = if (path == rootfs) rootfs else null

    override fun hint(base: String, names: List<String>) {
        if (names.isEmpty() || !HostPaths.isUnder(base, rootfs)) return
        var b = base
        var i = 0
        while (i < names.size) {
            val p = HostPaths.child(b, names[i])
            if (!stats.containsKey(p) && b !in listed) break
            val st = stats[p] ?: return // known missing: the walk stops here
            if (st.type != GuestFs.Type.DIR) return // link / file: the walk takes over
            b = p
            i++
        }
        if (i == names.size) return
        absorb(ops.chain(b, names.subList(i, names.size)))
    }

    override fun lstat(path: String): GuestFs.Stat? {
        requireInside(path)
        if (path == rootfs) return ROOTFS_STAT
        if (stats.containsKey(path)) return stats[path]
        val parent = HostPaths.parent(path) ?: return null
        if (parent in listed) return null
        // Below a known-missing entry or a non-directory: nothing to ask root.
        if (stats.containsKey(parent) && stats[parent]?.type != GuestFs.Type.DIR) return null
        absorb(ops.chain(parent, listOf(HostPaths.name(path))))
        if (!stats.containsKey(path)) stats[path] = null
        return stats[path]
    }

    override fun readLink(path: String): String? {
        if (lstat(path)?.type != GuestFs.Type.LINK) return null
        return links[path]
    }

    override fun list(dir: String): List<String> {
        requireInside(dir)
        val snap = ops.list(dir)
        absorb(snap)
        listed += dir
        return snap.entries.map { HostPaths.name(it.path) }
    }

    private fun mountList(): List<String> = mounts ?: ops.mounts().also { mounts = it }

    override fun isForeignMount(path: String): Boolean {
        requireInside(path)
        return mountList().any { HostPaths.isUnder(path, it) }
    }

    override fun containsForeignMount(path: String): Boolean {
        requireInside(path)
        return mountList().any { HostPaths.isUnder(it, path) }
    }

    /** Root can write anywhere in the rootfs except on a foreign mount. */
    override fun canWrite(path: String): Boolean = lstat(path) != null && !isForeignMount(path)

    override fun usableSpace(path: String): Long? = null

    private fun mutable(path: String): Boolean {
        requireInside(path)
        return path != rootfs && !isForeignMount(path)
    }

    private fun mutate(m: RootMutation): Boolean = try {
        ops.mutate(m)
    } finally {
        invalidate()
    }

    override fun createFile(path: String): Boolean = mutable(path) && mutate(RootMutation.CreateFile(path))

    override fun mkdir(path: String): Boolean = mutable(path) && mutate(RootMutation.Mkdir(path))

    override fun deleteTree(path: String): Boolean =
        mutable(path) && !containsForeignMount(path) && mutate(RootMutation.Delete(path))

    override fun move(from: String, to: String) {
        val ok = mutable(from) && mutable(to) && !containsForeignMount(from) && mutate(RootMutation.Move(from, to))
        if (!ok) throw FileNotFoundException("Cannot move ${HostPaths.name(from)}")
    }

    override fun copyTree(from: String, to: String): Boolean =
        mutable(from) && !containsForeignMount(from) && mutable(to) && mutate(RootMutation.Copy(from, to))

    override fun open(path: String, mode: String): ParcelFileDescriptor {
        if (!mutable(path)) throw FileNotFoundException("Not available through root: $path")
        return when (mode) {
            "r" -> ops.openRead(path)
            "w", "wt" -> ops.openWrite(path, append = false)
            "wa" -> ops.openWrite(path, append = true)
            else -> throw FileNotFoundException(
                "Mode \"$mode\" is not supported for Ubuntu chroot files (no random access through root); use r, w, wt or wa",
            )
        }
    }

    private companion object {
        val ROOTFS_STAT = GuestFs.Stat(GuestFs.Type.DIR, 0L, 0L)
    }
}

/** [RootOps] that runs [RootScripts] through a [RootRunner]. */
internal class ShellRootOps(
    private val runner: RootRunner,
    private val rootfs: String,
    private val scripts: RootScripts = RootScripts(rootfs),
) : RootOps {

    override fun chain(base: String, names: List<String>): RootSnapshot =
        RootScripts.parseChain(base, names, exec(scripts.chain(base, names)))

    override fun list(dir: String): RootSnapshot = RootScripts.parseList(dir, exec(scripts.list(dir)))

    override fun mounts(): List<String> = RootScripts.parseMountsOutput(exec(scripts.mounts()))

    override fun mutate(m: RootMutation): Boolean {
        val r = runner.run(scripts.mutation(m))
        failure(r.exitCode)?.let { throw RootFsException(it) }
        if (r.exitCode != 0) Log.w(TAG, "root ${m::class.simpleName} refused/failed: exit ${r.exitCode} ${r.stderr.take(200)}")
        return r.exitCode == 0
    }

    private fun exec(script: String): ByteArray {
        val r = runner.run(script)
        failure(r.exitCode)?.let { throw RootFsException(it) }
        return when (r.exitCode) {
            0 -> r.stdout
            3 -> throw RootFsException("Ubuntu chroot is not installed at $rootfs")
            else -> throw RootFsException("Root file command failed (exit ${r.exitCode}): ${r.stderr.take(200)}")
        }
    }

    private fun failure(code: Int): String? = when (code) {
        -1 -> "Root access is not available"
        -2 -> "Root file command timed out"
        -3 -> "Root file command output too large"
        else -> null
    }

    /** `su … cat` feeding a reliable pipe; a failed or refused read closes the pipe with an error. */
    override fun openRead(path: String): ParcelFileDescriptor {
        val job = runner.start(scripts.mutation(RootMutation.Read(path)))
            ?: throw RootFsException("Root access is not available")
        runCatching { job.stdin.close() }
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        val started = AtomicBoolean(false)
        watchdog(job, started)
        thread(isDaemon = true, name = "flux-saf-read") {
            var error: String? = null
            try {
                // Not closed itself: the fd belongs to writeSide, closed (maybe with an error) below.
                val out = FileOutputStream(writeSide.fileDescriptor)
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = job.stdout.read(buf)
                    if (n < 0) break
                    started.set(true)
                    out.write(buf, 0, n)
                }
                val code = job.waitFor(RootRunner.TIMEOUT_MS)
                if (code != 0) error = "Root read failed (exit ${code ?: "timeout"})"
            } catch (e: IOException) {
                error = "Read aborted: ${e.message}"
            } finally {
                started.set(true)
                if (error != null) job.kill()
                closePipe(writeSide, error)
            }
        }
        return readSide
    }

    /**
     * Client writes into a reliable pipe; root streams it into a fresh temp file next to the
     * target, then (only after a clean EOF) gives it the target's owner/mode — or the parent
     * dir's owner for a new file — and renames it over the target. Any failure removes the temp.
     */
    override fun openWrite(path: String, append: Boolean): ParcelFileDescriptor {
        val dir = HostPaths.parent(path) ?: throw FileNotFoundException(path)
        val temp = if (append) null else HostPaths.child(dir, ".flux-saf-${UUID.randomUUID()}.tmp")
        val first = if (temp == null) RootMutation.Append(path) else RootMutation.WriteTemp(temp)
        val job = runner.start(scripts.mutation(first)) ?: throw RootFsException("Root access is not available")
        thread(isDaemon = true, name = "flux-saf-drain") { runCatching { job.stdout.readBytes() } }
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        thread(isDaemon = true, name = "flux-saf-write") {
            var error: String? = null
            try {
                // Not closed itself: the fd belongs to readSide, closed (maybe with an error) below.
                val input = FileInputStream(readSide.fileDescriptor)
                job.stdin.use { input.copyTo(it, 64 * 1024) }
                readSide.checkError()
                val code = job.waitFor(RootRunner.TIMEOUT_MS)
                if (code != 0) {
                    error = "Root write failed (exit ${code ?: "timeout"})"
                } else if (temp != null && !mutate(RootMutation.CommitTemp(temp, path))) {
                    error = "Could not replace ${HostPaths.name(path)}"
                }
            } catch (e: IOException) {
                error = "Write aborted: ${e.message}"
            } finally {
                if (error != null) {
                    job.kill()
                    if (temp != null) runCatching { runner.run(scripts.mutation(RootMutation.AbortTemp(temp))) }
                }
                closePipe(readSide, error)
            }
        }
        return writeSide
    }

    /** Kills a job that neither produced output nor exited within the timeout (hung su). */
    private fun watchdog(job: RootJob, started: AtomicBoolean) {
        thread(isDaemon = true, name = "flux-saf-watchdog") {
            val deadline = System.currentTimeMillis() + RootRunner.TIMEOUT_MS
            while (!started.get() && job.isAlive && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(100)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
            if (!started.get() && job.isAlive) job.kill()
        }
    }

    private fun closePipe(pfd: ParcelFileDescriptor, error: String?) {
        try {
            if (error == null) pfd.close() else pfd.closeWithError(error)
        } catch (_: IOException) {
        }
        if (error != null) Log.w(TAG, error)
    }

    private companion object {
        const val TAG = "UbuntuChrootFiles"
    }
}
