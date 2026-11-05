package com.ivarna.fluxlinux.core.root

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * KernelSU's `su` fd wrapper ignores a program's O_NONBLOCK on the terminal, so full-screen
 * programs in a chroot session (codex start/exit) block until the next key. Interactive chroot
 * sessions use `su -W` when this su accepts it and root runs in KernelSU's default domain.
 */
class RootShellNoFdWrapperTest {

    @After
    fun tearDown() {
        RootShell.seedSuInvocationForTest(null)
        RootShell.seedNoFdWrapperForTest(null)
    }

    @Test
    fun probe_acceptsOnlyKernelSuDefaultDomain() {
        assertTrue(RootShell.parseNoFdWrapperProbe(0, "u:r:su:s0\u0000"))
        assertTrue(RootShell.parseNoFdWrapperProbe(0, "u:r:su:s0\n"))
        // Custom App Profile domain: the wrapper may be what lets root reach the app's terminal.
        assertFalse(RootShell.parseNoFdWrapperProbe(0, "u:r:ksu_custom:s0"))
        assertFalse(RootShell.parseNoFdWrapperProbe(0, "u:r:magisk:s0"))
        // Magisk / older KernelSU: unknown option.
        assertFalse(RootShell.parseNoFdWrapperProbe(1, "Unrecognized option: 'W'"))
        assertFalse(RootShell.parseNoFdWrapperProbe(0, ""))
    }

    @Test
    fun noWrapperInvocation_onlyForPlainSuForms() {
        assertEquals(
            listOf("/system/bin/su", "-W", "-c"),
            RootShell.noWrapperInvocation(listOf("/system/bin/su", "-c")),
        )
        assertEquals(listOf("su", "-W", "-c"), RootShell.noWrapperInvocation(listOf("su", "-c")))
        assertEquals(
            listOf("/system/bin/sh", "-c", "SU_WRAP:/system/bin/su -W"),
            RootShell.noWrapperInvocation(listOf("/system/bin/sh", "-c", "SU_WRAP:/system/bin/su")),
        )
        // Magisk's `su 0 -c` form never gets -W.
        assertNull(RootShell.noWrapperInvocation(listOf("/system/bin/su", "0", "-c")))
    }

    @Test
    fun shellRootCommand_addsNoWrapperFlagOnlyWhenAsked() {
        RootShell.seedSuInvocationForTest(listOf("/system/bin/su", "-c"))
        assertEquals("/system/bin/su -c 'echo hi'", RootShell.shellRootCommand("echo hi"))
        assertEquals(
            "/system/bin/su -W -c 'echo hi'",
            RootShell.shellRootCommand("echo hi", noFdWrapper = true),
        )

        RootShell.seedSuInvocationForTest(listOf("/system/bin/sh", "-c", "SU_WRAP:/system/bin/su"))
        assertEquals(
            "/system/bin/su -W -c 'echo hi'",
            RootShell.shellRootCommand("echo hi", noFdWrapper = true),
        )

        RootShell.seedSuInvocationForTest(listOf("/system/bin/su", "0", "-c"))
        assertEquals(
            "/system/bin/su 0 -c 'echo hi'",
            RootShell.shellRootCommand("echo hi", noFdWrapper = true),
        )
    }

    @Test
    fun supportsNoFdWrapper_usesTheCachedAnswer() {
        RootShell.seedNoFdWrapperForTest(true)
        assertTrue(RootShell.supportsNoFdWrapper())
        RootShell.seedNoFdWrapperForTest(false)
        assertFalse(RootShell.supportsNoFdWrapper())
    }

    @Test
    fun chrootSessions_askForTheUnwrappedTerminal() {
        val src = listOf("src/main/kotlin", "app/src/main/kotlin")
            .map { File(it, "com/ivarna/fluxlinux/core/terminal/ChrootCommandBuilder.kt") }
            .first { it.isFile }
            .readText()
        assertTrue(src.contains("RootShell.shellRootCommand(withPath, noFdWrapper = RootShell.supportsNoFdWrapper())"))
    }
}
