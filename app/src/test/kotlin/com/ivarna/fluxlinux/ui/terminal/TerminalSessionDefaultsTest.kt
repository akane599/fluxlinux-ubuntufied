package com.ivarna.fluxlinux.ui.terminal

import com.ivarna.fluxlinux.core.terminal.FakeContext
import com.ivarna.fluxlinux.core.terminal.TerminalShellAvailability
import com.ivarna.fluxlinux.core.terminal.TerminalShellCardUi
import com.ivarna.fluxlinux.core.terminal.TerminalShellCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TerminalSessionDefaultsTest {

    private fun cards(
        ubuntuProot: Boolean = false,
        ubuntuChroot: Boolean = false,
        rootAvailable: Boolean = false,
        debianProot: Boolean = false,
    ): List<TerminalShellCardUi> {
        val dir = File(System.getProperty("java.io.tmpdir"), "flux_tsd_${System.nanoTime()}")
        val avail = TerminalShellAvailability(
            debianProot = debianProot,
            alpineProot = false,
            ubuntuProot = ubuntuProot,
            debianChroot = false,
            alpineChroot = false,
            ubuntuChroot = ubuntuChroot,
            rootAvailable = rootAvailable,
        )
        return TerminalShellCatalog.sections(FakeContext(dir, "$dir/jni"), avail).flatMap { it.cards }
    }

    @Test
    fun defaultUser_isUbuntuProotUserShellFromCatalog() {
        val choices = TerminalSessionDefaults.resolve(cards(ubuntuProot = true))
        assertNotNull(choices.defaultUser)
        val def = choices.defaultUser!!.def
        assertEquals("shell", def.type)
        assertEquals("proot", def.method)
        assertEquals("ubuntu", def.distroId)
        assertEquals("Ubuntu Shell", def.label)
        assertTrue(choices.defaultUser!!.enabled)
    }

    @Test
    fun defaultUser_staysPresentButDisabledWhenUbuntuMissing() {
        val choices = TerminalSessionDefaults.resolve(cards(debianProot = true))
        val user = choices.defaultUser!!
        assertEquals("ubuntu", user.def.distroId)
        assertFalse(user.enabled)
        assertNull(choices.ubuntuRoot)
    }

    @Test
    fun extras_withUbuntuProotOnly_offerRootAndHostButNoChroot() {
        val choices = TerminalSessionDefaults.resolve(cards(ubuntuProot = true))
        assertEquals(listOf("shell-root", "host"), choices.extras.map { it.def.type })
        assertEquals("Ubuntu Shell Rooted", choices.ubuntuRoot!!.def.label)
        assertEquals("proot", choices.ubuntuRoot!!.def.method)
        assertEquals("host", choices.host!!.def.method)
        assertNull(choices.ubuntuChroot)
    }

    @Test
    fun chroot_requiresBothRootAndInstalledChroot() {
        assertNull(TerminalSessionDefaults.resolve(cards(ubuntuChroot = true)).ubuntuChroot)
        assertNull(TerminalSessionDefaults.resolve(cards(rootAvailable = true)).ubuntuChroot)
        val chroot = TerminalSessionDefaults.resolve(cards(ubuntuChroot = true, rootAvailable = true)).ubuntuChroot!!
        assertEquals("chroot", chroot.def.method)
        assertEquals("ubuntu_chroot", chroot.def.distroId)
        assertEquals("shell", chroot.def.type)
    }

    @Test
    fun extras_fullSetIsOrderedRootChrootHost() {
        val choices = TerminalSessionDefaults.resolve(
            cards(ubuntuProot = true, ubuntuChroot = true, rootAvailable = true)
        )
        assertEquals(
            listOf("proot" to "shell-root", "chroot" to "shell", "host" to "host"),
            choices.extras.map { it.def.method to it.def.type },
        )
    }

    @Test
    fun resolve_emptyCatalogYieldsNothing() {
        val choices = TerminalSessionDefaults.resolve(emptyList())
        assertNull(choices.defaultUser)
        assertTrue(choices.extras.isEmpty())
    }

    @Test
    fun shortTabTitle_dropsShellNoise() {
        assertEquals("Ubuntu", TerminalSessionDefaults.shortTabTitle("Ubuntu Shell"))
        assertEquals("Ubuntu root", TerminalSessionDefaults.shortTabTitle("Ubuntu Shell Rooted"))
        assertEquals("Ubuntu Chroot", TerminalSessionDefaults.shortTabTitle("Ubuntu Chroot Shell"))
        assertEquals("Ubuntu Chroot root", TerminalSessionDefaults.shortTabTitle("Ubuntu Chroot Rooted"))
        assertEquals("Host", TerminalSessionDefaults.shortTabTitle("Host Shell"))
        assertEquals("Shell", TerminalSessionDefaults.shortTabTitle("Shell"))
    }
}
