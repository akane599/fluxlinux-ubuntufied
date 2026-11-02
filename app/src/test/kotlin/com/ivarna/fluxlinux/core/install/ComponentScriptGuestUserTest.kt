package com.ivarna.fluxlinux.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Component scripts follow FLUX_GUEST_USER (akane on Ubuntu, default flux) instead of a
 * hardcoded `flux` user and `/home/flux`, and new users get their name as password.
 */
class ComponentScriptGuestUserTest {

    private val componentScripts = listOf(
        "setup_appdev_debian.sh",
        "setup_gamedev_debian.sh",
        "setup_datascience_debian.sh",
        "setup_cybersec_debian.sh",
        "setup_gengdev_debian.sh",
        "setup_webdev_debian.sh",
        "setup_kde_debian.sh",
    )

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel")).firstOrNull { it.isFile }
            ?: error("missing $rel (cwd=$cwd)")
    }

    private fun component(name: String) =
        repoFile("src/main/assets/scripts/debian/common/setup/$name").readText()

    @Test
    fun componentScripts_noHardcodedFluxHome() {
        (componentScripts + "setup_customization_kde_debian.sh").forEach { name ->
            val text = component(name)
            assertFalse("$name still hardcodes /home/flux", text.contains("/home/flux"))
            assertFalse("$name still hardcodes the flux user", Regex("""USER="flux"""").containsMatchIn(text))
            assertTrue("$name reads FLUX_GUEST_USER", text.contains("\${FLUX_GUEST_USER:-flux}"))
        }
    }

    @Test
    fun header_resolvesUserBeforeFirstUse() {
        assumeTrue(File("/bin/sh").canExecute())
        componentScripts.forEach { name ->
            val text = component(name)
            val start = text.indexOf("FLUX_USER=\"\${FLUX_GUEST_USER:-flux}\"")
            val end = text.indexOf("FLUX_HOME=\"/home/\$FLUX_USER\"\n", start)
            assertTrue(name, start > 0 && end > start)
            val firstUse = text.indexOf("\$FLUX_HOME")
            assertTrue("$name: header before first FLUX_HOME use", firstUse == -1 || firstUse > end)
            val header = text.substring(start, end) + "FLUX_HOME=\"/home/\$FLUX_USER\"\n"
            fun run(value: String?): String {
                val pb = ProcessBuilder("/bin/sh", "-c", header + "echo \"\$FLUX_USER \$FLUX_HOME\"")
                pb.environment().remove("FLUX_GUEST_USER")
                value?.let { pb.environment()["FLUX_GUEST_USER"] = it }
                val p = pb.redirectErrorStream(true).start()
                val out = String(p.inputStream.readBytes()).trim()
                assertEquals(0, p.waitFor())
                return out
            }
            assertEquals(name, "akane /home/akane", run("akane"))
            assertEquals(name, "flux /home/flux", run(null))
            listOf("root", "a b", "x;id", "../x", "Akane", "a".repeat(33)).forEach {
                assertEquals("$name <$it>", "flux /home/flux", run(it))
            }
            // Guest passwd with flux only (rename not done yet): flux, never an ownerless akane home.
            assertTrue("$name: passwd fallback", header.contains("grep -q '^flux:' /etc/passwd"))
            val fake = File.createTempFile("passwd", null).apply { deleteOnExit() }
            fun runWith(passwd: String): String {
                fake.writeText(passwd)
                val h = header.replace("/etc/passwd", fake.absolutePath)
                val pb = ProcessBuilder("/bin/sh", "-c", h + "echo \"\$FLUX_USER \$FLUX_HOME\"")
                pb.environment()["FLUX_GUEST_USER"] = "akane"
                val p = pb.redirectErrorStream(true).start()
                val out = String(p.inputStream.readBytes()).trim()
                assertEquals(0, p.waitFor())
                return out
            }
            assertEquals(name, "flux /home/flux", runWith("root:x:0:0::/root:/bin/sh\nflux:x:1000:1000::/home/flux:/bin/bash\n"))
            assertEquals(name, "akane /home/akane", runWith("flux:x:1000:1000::/home/flux:/bin/bash\nakane:x:1001:1001::/home/akane:/bin/bash\n"))
            assertEquals(name, "akane /home/akane", runWith("root:x:0:0::/root:/bin/sh\n"))
        }
    }

    @Test
    fun sharedSetupScripts_keepFluxWhenPasswdHasOnlyFlux() {
        val common = repoFile("src/main/assets/scripts/common/setup/flux_guest_common.sh").readText()
        assertTrue(common.contains("grep -q '^flux:' /etc/passwd"))
        val kde = component("setup_customization_kde_debian.sh")
        assertTrue(kde.indexOf("grep -q '^flux:' /etc/passwd") in 1 until kde.indexOf("USER_HOME=\"/home/\$CUSTOM_USER\""))
        val xfce = repoFile("src/main/assets/scripts/common/setup/setup_customization_xfce.sh").readText()
        assertTrue(xfce.indexOf("grep -q '^flux:' /etc/passwd") in 1 until xfce.indexOf("USER_HOME=\"/home/\$CUSTOM_USER\""))
    }

    @Test
    fun newUsers_passwordIsUserName_existingUntouched() {
        val chroot = repoFile("src/main/assets/scripts/chroot/setup_guest_chroot.sh").readText()
        assertTrue(chroot.contains("echo '\$USERNAME:\$USERNAME' | chpasswd"))
        assertFalse(chroot.contains(":flux' | chpasswd"))
        // Only inside the "user missing" branch.
        val create = chroot.indexOf("if ! id \$USERNAME >/dev/null 2>&1; then")
        val pw = chroot.indexOf("echo '\$USERNAME:\$USERNAME' | chpasswd")
        assertTrue(create in 1 until pw)
        assertTrue(pw < chroot.indexOf("id \$USERNAME >/dev/null 2>&1 ||", create))

        val common = repoFile("src/main/assets/scripts/common/setup/flux_guest_common.sh").readText()
        val ensure = common.substring(common.indexOf("_flux_ensure_user() {"))
        val branch = ensure.indexOf("if ! id \"\$_u\" >/dev/null 2>&1; then")
        val cpw = ensure.indexOf("echo \"\$_u:\$_u\" | chpasswd")
        assertTrue(branch in 0 until cpw)
        assertFalse(common.contains("\$_u:flux"))

        val kde = component("setup_kde_debian.sh")
        assertTrue(kde.contains("echo \"\$FLUX_USER:\$FLUX_USER\" | chpasswd"))
        assertTrue(kde.indexOf("if ! id \"\$FLUX_USER\" &>/dev/null; then") < kde.indexOf("\$FLUX_USER:\$FLUX_USER"))
    }
}
