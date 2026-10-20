package com.ivarna.fluxlinux.core.terminal

import com.ivarna.fluxlinux.core.install.InstallController
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** H2: the distro uninstall refuses while an install runs or the host prefix is extracting. */
class UninstallBusyGuardContractTest {

    private fun mainActivity(): String {
        val rel = "src/main/kotlin/com/ivarna/fluxlinux/MainActivity.kt"
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel"))
            .first { it.exists() }
            .readText()
    }

    @Test
    fun hostRepairAllowed_blocksDuringInstallOrExtraction() {
        val running = InstallController.State(status = InstallController.Status.Running)
        assertFalse(InstallController.hostRepairAllowed(running, extracting = false))
        assertFalse(InstallController.hostRepairAllowed(InstallController.State(), extracting = true))
        assertTrue(InstallController.hostRepairAllowed(InstallController.State(), extracting = false))
    }

    @Test
    fun uninstallChecksGuardBeforeAndAfterPrepareHost() {
        val src = mainActivity()
        val region = src.substring(src.indexOf("onUninstallDistro = {"))
        val guard = region.indexOf("InstallController.hostRepairAllowed(")
        val busy = region.indexOf("BootstrapInstaller")
        val firstCheck = region.indexOf("if (!uninstallAllowed())")
        val prepare = region.indexOf("TerminalLauncher.prepareHost(")
        val recheck = region.indexOf("if (!uninstallAllowed())", prepare)
        val open = region.indexOf(".openUninstallSession(")
        assertTrue(guard in 0 until firstCheck)
        assertTrue(busy in guard until firstCheck)
        assertTrue(firstCheck in 0 until prepare)
        assertTrue(recheck in prepare until open)
        assertTrue(region.substring(0, open).contains("R.string.home_repair_blocked_install"))
    }
}
