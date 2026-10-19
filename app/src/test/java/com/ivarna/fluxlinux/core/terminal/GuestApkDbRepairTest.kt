package com.ivarna.fluxlinux.core.terminal

import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory

class GuestApkDbRepairTest {

    private fun filesDir(): File = createTempDirectory("flux-apkdb").toFile()

    @Test
    fun prootAlpine_repairsThenCaches() {
        val dir = filesDir()
        try {
            val rootfs = File(
                dir,
                "usr/var/lib/proot-distro/containers/alpine/rootfs"
            )
            rootfs.mkdirs()
            val ctx = FakeContext(dir, "$dir/jni")
            GuestApkDbRepair.repairIfNeeded(ctx, "proot", "alpine")
            val db = File(rootfs, "lib/apk/db")
            assertTrue("apk db lock should be created", File(db, "lock").isFile)

            // Second pass is a cached no-op (proot-opt-01): delete the lock and
            // re-run — the sweep must not recreate it.
            File(db, "lock").delete()
            GuestApkDbRepair.repairIfNeeded(ctx, "proot", "alpine")
            assertFalse("cached pass should skip the apk db sweep", File(db, "lock").exists())
        } finally {
            SafeFiles.deleteTree(dir)
        }
    }

    @Test
    fun prootDebian_isNotRepaired() {
        val dir = filesDir()
        try {
            val rootfs = File(
                dir,
                "usr/var/lib/proot-distro/containers/debian/rootfs"
            )
            rootfs.mkdirs()
            val ctx = FakeContext(dir, "$dir/jni")
            GuestApkDbRepair.repairIfNeeded(ctx, "proot", "debian")
            assertFalse(File(rootfs, "lib/apk/db/lock").exists())
        } finally {
            SafeFiles.deleteTree(dir)
        }
    }

    @Test
    fun chrootMethod_isNotRepaired() {
        val dir = filesDir()
        try {
            val rootfs = File(
                dir,
                "usr/var/lib/proot-distro/containers/alpine/rootfs"
            )
            rootfs.mkdirs()
            val ctx = FakeContext(dir, "$dir/jni")
            GuestApkDbRepair.repairIfNeeded(ctx, "chroot", "alpine")
            assertFalse(File(rootfs, "lib/apk/db/lock").exists())
        } finally {
            SafeFiles.deleteTree(dir)
        }
    }

    /**
     * The chmod sweep never follows links: a symlinked dir's target keeps its
     * modes, and a link cycle cannot recurse (old listFiles recursion would
     * StackOverflow). Real files still get the readable/writable sweep.
     */
    @Test
    fun chmodSweep_skipsSymlinks_andSurvivesCycles() {
        val dir = filesDir()
        val victim = createTempDirectory("flux-apkdb-victim").toFile()
        try {
            val victimFile = File(victim, "secret").apply { writeText("x") }
            val privateMode = PosixFilePermissions.fromString("rw-------")
            Files.setPosixFilePermissions(victimFile.toPath(), privateMode)
            Files.setPosixFilePermissions(victim.toPath(), PosixFilePermissions.fromString("rwx------"))

            val rootfs = File(dir, "usr/var/lib/proot-distro/containers/alpine/rootfs")
            val apk = File(rootfs, "lib/apk").apply { mkdirs() }
            val installed = File(apk, "db/installed").apply { parentFile?.mkdirs(); writeText("") }
            Files.setPosixFilePermissions(installed.toPath(), privateMode)
            Files.createSymbolicLink(File(apk, "victim").toPath(), victim.toPath())
            Files.createSymbolicLink(File(apk, "loop").toPath(), apk.toPath())

            GuestApkDbRepair.repairIfNeeded(FakeContext(dir, "$dir/jni"), "proot", "alpine")

            assertTrue("sweep completed and cached", File(apk, "db/lock").isFile)
            assertEquals(
                "symlinked dir target must keep its mode",
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(victim.toPath())
            )
            assertEquals(privateMode, Files.getPosixFilePermissions(victimFile.toPath()))
            assertTrue(
                "real files still made world-readable",
                PosixFilePermission.OTHERS_READ in Files.getPosixFilePermissions(installed.toPath())
            )
        } finally {
            SafeFiles.deleteTree(dir)
            SafeFiles.deleteTree(victim)
        }
    }
}