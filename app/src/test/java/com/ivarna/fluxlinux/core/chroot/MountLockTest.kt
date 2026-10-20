package com.ivarna.fluxlinux.core.chroot

import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * M16 review: the real `mount_lock` (extracted from fluxlinux_chroot.sh) against a temp
 * rootfs. Stale locks (owner-less, dead or foreign pid, earlier boot) are broken; a live
 * FluxLinux holder is respected; two waiters breaking the same stale lock → one wins.
 */
class MountLockTest {

    private lateinit var tmp: File
    private lateinit var chroot: File
    private lateinit var lib: File
    private val lock get() = File(chroot, ".flux_mount.lock")

    private val scripts = listOf(
        "fluxlinux_chroot.sh",
        "stop_guest_gui.sh",
        "stop_debian13_gui.sh",
        "stop_alpine_gui.sh",
        "uninstall_guest_chroot.sh",
        "uninstall_debian13_chroot.sh",
        "uninstall_alpine_chroot.sh",
    )

    @Before
    fun setUp() {
        assumeTrue("needs a POSIX sh", File("/bin/sh").canExecute())
        tmp = Files.createTempDirectory("flux-lock-test").toFile()
        chroot = File(tmp, "chrootX").apply { mkdirs() }
        lib = File(tmp, "lock.sh").apply { writeText(block("fluxlinux_chroot.sh")) }
    }

    @After
    fun tearDown() {
        if (::tmp.isInitialized) {
            SafeFiles.deleteTree(tmp)
        }
    }

    private fun script(name: String): File {
        val rel = "src/main/assets/scripts/chroot/$name"
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel")).first { it.exists() }
    }

    private fun block(name: String): String {
        val src = script(name).readText()
        val start = src.indexOf("# --- mount lock (M16)")
        val end = src.indexOf("# --- end mount lock")
        check(start in 0 until end) { "$name: lock block missing" }
        return src.substring(start, end)
    }

    private fun bootId(): String =
        File("/proc/sys/kernel/random/boot_id").takeIf { it.canRead() }?.readText()?.trim().orEmpty()

    private fun now(): Long = System.currentTimeMillis() / 1000

    /** `sh -c` with $0 = fluxlinux_chroot.sh (holders are recognised by their cmdline). */
    private fun shell(cmd: String): Process =
        ProcessBuilder("/bin/sh", "-c", cmd, "fluxlinux_chroot.sh", lib.absolutePath, chroot.absolutePath)
            .redirectErrorStream(true)
            .start()

    private fun acquire(): Int {
        val p = shell(". \"\$1\"; mount_lock \"\$2\"")
        println(p.inputStream.bufferedReader().readText())
        assertTrue("timeout", p.waitFor(60, TimeUnit.SECONDS))
        return p.exitValue()
    }

    private fun ownerPid(): String = File(lock, "owner").readText().substringBefore(' ')

    private fun startHolder(): Pair<Process, String> {
        val pidFile = File(tmp, "holder.${System.nanoTime()}")
        val p = shell("echo \$\$ > \"$pidFile.t\" && mv \"$pidFile.t\" \"$pidFile\"; sleep 40; :")
        val deadline = System.currentTimeMillis() + 10_000
        while (!pidFile.exists() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return p to pidFile.readText().trim()
    }

    @Test
    fun allSevenCopiesAreIdentical() {
        val canonical = block("fluxlinux_chroot.sh").lines().map { it.trim() }
        for (name in scripts) {
            assertEquals(name, canonical, block(name).lines().map { it.trim() })
        }
        assertFalse(canonical.any { it.contains("kill -0") })
    }

    @Test
    fun ownerlessLock_isBrokenAfterAFewSeconds() {
        lock.mkdirs()
        assertEquals(0, acquire())
        assertTrue(ownerPid().all { it.isDigit() })
    }

    @Test
    fun deadForeignOrPreviousBootOwners_areBroken() {
        val (holder, holderPid) = startHolder()
        try {
            // A live pid that is no FluxLinux script (here: this JVM) — pid reuse after reboot.
            val foreignPid = ProcessBuilder("/bin/sh", "-c", "echo \$PPID").start()
                .inputStream.bufferedReader().readText().trim()
            val cases = listOf(
                "99999999 ${bootId()} ${now()}",
                "$foreignPid ${bootId()} ${now()}",
                "$holderPid not-this-boot ${now()}",
                "$holderPid ${bootId()} ${now() - 120}",
            )
            for (rec in cases) {
                lock.mkdirs()
                File(lock, "owner").writeText(rec + "\n")
                assertEquals(rec, 0, acquire())
                assertTrue("$rec replaced", !File(lock, "owner").readText().startsWith(rec))
                SafeFiles.deleteTree(lock)
            }
        } finally {
            holder.destroyForcibly()
        }
    }

    @Test
    fun liveFluxLinuxHolder_isRespected() {
        val (holder, holderPid) = startHolder()
        try {
            lock.mkdirs()
            val rec = "$holderPid ${bootId()} ${now()}\n"
            File(lock, "owner").writeText(rec)
            assertEquals(1, acquire())
            assertEquals(rec, File(lock, "owner").readText())
        } finally {
            holder.destroyForcibly()
        }
    }

    @Test
    fun twoBreakersOfOneStaleLock_onlyOneWins() {
        lock.mkdirs() // owner-less: stale after ~3 s
        val hold = ". \"\$1\"; if mount_lock \"\$2\"; then echo WON; sleep 25; else echo LOST; fi; :"
        val a = shell(hold)
        val b = shell(hold)
        val outs = listOf(a, b).map { p ->
            val out = p.inputStream.bufferedReader().readText()
            assertTrue("timeout", p.waitFor(60, TimeUnit.SECONDS))
            out.trim()
        }
        assertEquals(outs.toString(), 1, outs.count { it.endsWith("WON") })
        assertEquals(outs.toString(), 1, outs.count { it.endsWith("LOST") })
    }
}
