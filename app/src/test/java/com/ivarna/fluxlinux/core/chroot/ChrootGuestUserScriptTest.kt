package com.ivarna.fluxlinux.core.chroot

import com.ivarna.fluxlinux.core.root.ChrootPaths
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

/**
 * fluxlinux_chroot.sh v3.0: the real `guest user` and `storage links` blocks (extracted
 * between their markers, MountLockTest pattern) run with /bin/sh against a temp rootfs.
 */
class ChrootGuestUserScriptTest {

    private lateinit var tmp: File
    private lateinit var chroot: File
    private lateinit var lib: File

    @Before
    fun setUp() {
        assumeTrue("needs a POSIX sh", File("/bin/sh").canExecute())
        tmp = Files.createTempDirectory("flux-guest-user").toFile()
        chroot = File(tmp, "rootfs").apply { mkdirs() }
        File(chroot, "etc").mkdirs()
        lib = File(tmp, "lib.sh").apply {
            writeText(
                "die() { echo \"DIE: \$*\"; exit 3; }\n" +
                    block("# --- guest user (v3.0)", "# --- end guest user") +
                    block("# --- storage links (v3.0)", "# --- end storage links"),
            )
        }
    }

    @After
    fun tearDown() {
        if (::tmp.isInitialized) SafeFiles.deleteTree(tmp)
    }

    private val helperText: String by lazy {
        val rel = "src/main/assets/scripts/chroot/fluxlinux_chroot.sh"
        val cwd = File("").absoluteFile
        listOf(File(cwd, rel), File(cwd, "app/$rel")).first { it.exists() }.readText()
    }

    private fun block(begin: String, end: String): String {
        val start = helperText.indexOf(begin)
        val stop = helperText.indexOf(end, start)
        assertTrue("markers $begin / $end", start >= 0 && stop > start)
        return helperText.substring(start, stop) + "\n"
    }

    private fun passwd(vararg lines: String) =
        File(chroot, "etc/passwd").writeText(lines.joinToString("\n", postfix = "\n"))

    private fun sh(body: String): Pair<Int, String> {
        val p = ProcessBuilder("/bin/sh", "-c", ". '${lib.absolutePath}'\n$body")
            .redirectErrorStream(true)
            .apply { environment()["FLUX_CHROOT"] = chroot.absolutePath }
            .start()
        val out = String(p.inputStream.readBytes()).trim()
        return p.waitFor() to out
    }

    private fun resolve(user: String): Pair<Int, String> =
        sh("USER_NAME='$user'; resolve_guest_user; echo \"\$USER_NAME \$USER_HOME\"")

    @Test
    fun default_prefersAkane_elseFlux() {
        passwd("root:x:0:0::/root:/bin/sh", "flux:x:1000:1000::/home/flux:/bin/bash")
        assertEquals(0 to "flux /home/flux", resolve(""))
        passwd(
            "root:x:0:0::/root:/bin/sh",
            "flux:x:1000:1000::/home/flux:/bin/bash",
            "akane:x:1001:1001::/home/akane:/usr/bin/zsh",
        )
        assertEquals(0 to "akane /home/akane", resolve(""))
        assertEquals(0 to "flux /home/flux", resolve("flux"))
        assertEquals(0 to "root /root", resolve("root"))
    }

    @Test
    fun invalidOrUnknownUser_dies() {
        passwd("root:x:0:0::/root:/bin/sh", "akane:x:1000:1000::/home/akane:/bin/bash")
        listOf("Akane", "a b", "x;id", "../x", "-u", "a".repeat(33)).forEach { bad ->
            val (rc, out) = resolve(bad)
            assertEquals(bad, 3, rc)
            assertTrue(out, out.startsWith("DIE: invalid user name"))
        }
        val (rc, out) = resolve("nobody")
        assertEquals(3, rc)
        assertTrue(out, out.contains("not in"))
    }

    @Test
    fun passwdHome_usedOnlyWhenPlain() {
        passwd("akane:x:1000:1000::/srv/akane:/bin/bash")
        assertEquals(0 to "akane /srv/akane", resolve("akane"))
        passwd("akane:x:1000:1000::/srv/a\$(id):/bin/bash")
        assertEquals(0 to "akane /home/akane", resolve("akane"))
        passwd("akane:x:1000:1000::relative:/bin/bash")
        assertEquals(0 to "akane /home/akane", resolve("akane"))
    }

    private fun links(mode: String, user: String = "akane", home: String = "/home/akane"): String {
        val (rc, out) = sh(
            "_b=\$(storage_links_body); sh -c \"\$_b\" flux-links '$mode' '$user' '$home' '${chroot.absolutePath}'",
        )
        assertEquals(out, 0, rc)
        return out.lines().last { it.startsWith("FLUX_LINKS ") }
    }

    private val names = listOf("shared", "downloads", "documents", "dcim", "pictures", "music", "movies")

    @Test
    fun storageLinks_createIdempotentAndStatus() {
        File(chroot, "home/akane").mkdirs()
        File(chroot, "root").mkdirs()
        assertTrue(links("status").contains("present=0"))
        assertFalse("status never writes", File(chroot, "home/akane/storage").exists())

        assertEquals("FLUX_LINKS mode=ensure created=14 existing=0 present=1 skipped=", links("ensure"))
        val shared = File(chroot, "home/akane/storage/shared").toPath()
        assertEquals("/sdcard", Files.readSymbolicLink(shared).toString())
        assertEquals(
            "/sdcard/Download",
            Files.readSymbolicLink(File(chroot, "root/storage/downloads").toPath()).toString(),
        )
        assertEquals("FLUX_LINKS mode=ensure created=0 existing=14 present=1 skipped=", links("ensure"))
        assertEquals("FLUX_LINKS mode=status created=0 existing=14 present=1 skipped=", links("status"))
        names.forEach { assertTrue(Files.isSymbolicLink(File(chroot, "home/akane/storage/$it").toPath())) }
    }

    @Test
    fun storageLinks_neverReplaceOrFollow() {
        File(chroot, "home/akane/storage").mkdirs()
        File(chroot, "root").mkdirs()
        val foreign = File(chroot, "home/akane/storage/music").apply { writeText("mine") }
        val wrong = File(chroot, "home/akane/storage/movies").toPath()
        Files.createSymbolicLink(wrong, File("/elsewhere").toPath())
        // /root/storage is a symlink: skipped, nothing written through it.
        val victim = File(tmp, "victim").apply { mkdirs() }
        Files.createSymbolicLink(File(chroot, "root/storage").toPath(), victim.toPath())

        val line = links("ensure")
        assertTrue(line, line.contains("created=5 "))
        assertTrue(line, line.contains("present=0"))
        assertTrue(line, line.contains("/home/akane/storage/music"))
        assertTrue(line, line.contains("/home/akane/storage/movies"))
        assertTrue(line, line.contains("/root/storage"))
        assertEquals("mine", foreign.readText())
        assertEquals("/elsewhere", Files.readSymbolicLink(wrong).toString())
        assertEquals(0, victim.list()?.size ?: -1)
    }

    @Test
    fun storageLinks_symlinkedHomeSkipped() {
        val victim = File(tmp, "victim").apply { mkdirs() }
        File(chroot, "home").mkdirs()
        Files.createSymbolicLink(File(chroot, "home/akane").toPath(), victim.toPath())
        val line = links("ensure")
        assertTrue(line, line.contains("present=0"))
        assertEquals(0, victim.list()?.size ?: -1)
    }

    @Test
    fun helper_v30_wiresUserAndLinks() {
        assertTrue(helperText.contains(ChrootPaths.CHROOT_HELPER_VERSION))
        assertTrue(helperText.contains("links|links-status)"))
        assertTrue(helperText.contains("run_storage_links ensure"))
        assertFalse("no hardcoded root|flux user gate", helperText.contains("root|flux)"))
    }
}
