package com.ivarna.fluxlinux.core.terminal

import com.ivarna.fluxlinux.core.system.SafeFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths

class HostShLinkTest {

    private lateinit var tmp: File
    private lateinit var bin: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("host-sh-link").toFile()
        bin = File(tmp, "usr/bin").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        SafeFiles.deleteTree(tmp)
    }

    private fun shPath() = File(bin, "sh").toPath()

    @Test
    fun createsRelativeLinkToBashWhenShMissing() {
        File(bin, "bash").writeText("#bash")

        assertEquals(HostShLink.Result.CREATED, HostShLink.ensure(bin))

        assertTrue(Files.isSymbolicLink(shPath()))
        assertEquals(Paths.get("bash"), Files.readSymbolicLink(shPath()))
        assertEquals("#bash", File(bin, "sh").readText())
    }

    @Test
    fun secondCallIsANoOp() {
        File(bin, "bash").writeText("#bash")
        HostShLink.ensure(bin)

        assertEquals(HostShLink.Result.PRESENT, HostShLink.ensure(bin))
        assertEquals(Paths.get("bash"), Files.readSymbolicLink(shPath()))
    }

    @Test
    fun keepsExistingShFile() {
        File(bin, "bash").writeText("#bash")
        File(bin, "sh").writeText("#dash")

        assertEquals(HostShLink.Result.PRESENT, HostShLink.ensure(bin))

        assertFalse(Files.isSymbolicLink(shPath()))
        assertEquals("#dash", File(bin, "sh").readText())
    }

    @Test
    fun keepsExistingDanglingLink() {
        File(bin, "bash").writeText("#bash")
        Files.createSymbolicLink(shPath(), Paths.get("dash"))

        assertEquals(HostShLink.Result.PRESENT, HostShLink.ensure(bin))

        assertEquals(Paths.get("dash"), Files.readSymbolicLink(shPath()))
    }

    @Test
    fun doesNothingWithoutBash() {
        assertEquals(HostShLink.Result.NO_BASH, HostShLink.ensure(bin))

        assertFalse(Files.exists(shPath(), LinkOption.NOFOLLOW_LINKS))
    }
}
