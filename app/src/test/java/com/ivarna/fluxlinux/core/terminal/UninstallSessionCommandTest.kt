package com.ivarna.fluxlinux.core.terminal

import com.ivarna.fluxlinux.core.root.BusyBoxPaths
import com.ivarna.fluxlinux.core.root.ChrootPaths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** M14: the chroot uninstall script runs only from the root-only stage, re-verified first. */
class UninstallSessionCommandTest {

    private val sha = "a".repeat(64)
    private val resolverSha = "b".repeat(64)
    private val staged = "${ChrootPaths.ROOT_STAGE_DIR}/uninstall_guest_chroot.sh"

    private fun build(bb: String? = "/data/adb/magisk/busybox") =
        UninstallSessionFactory.buildChrootUninstallCommand(
            scriptName = "uninstall_guest_chroot.sh",
            scriptSha256 = sha,
            resolverSha256 = resolverSha,
            resolvedBb = bb,
            chrootPath = "/data/local/tmp/chrootUbuntu",
            distroId = "ubuntu_chroot",
        )

    @Test
    fun runsScriptFromRootStage_neverFromDataLocalTmp() {
        val cmd = build()
        assertTrue(cmd.contains("sh '$staged'"))
        assertTrue(cmd.contains("FLUX_RESOLVE_BB='${BusyBoxPaths.RESOLVER_ON_DEVICE}'"))
        assertTrue(BusyBoxPaths.RESOLVER_ON_DEVICE.startsWith(ChrootPaths.ROOT_STAGE_DIR + "/"))
        // The only /data/local/tmp reference is the rootfs path handed to the script.
        val withoutRootfs = cmd.replace("FLUX_CHROOT='/data/local/tmp/chrootUbuntu'", "")
        assertFalse(cmd, withoutRootfs.contains("/data/local/tmp"))
    }

    @Test
    fun reverifiesScriptAndResolverBeforeRunning_andFailsClosed() {
        val cmd = build()
        val run = cmd.indexOf("sh '$staged'")
        val verifyScript = cmd.indexOf("\"\$(_fsha '$staged')\" = '$sha'")
        val verifyResolver =
            cmd.indexOf("\"\$(_fsha '${BusyBoxPaths.RESOLVER_ON_DEVICE}')\" = '$resolverSha'")
        assertTrue(cmd, verifyScript in 0 until run)
        assertTrue(cmd, verifyResolver in 0 until run)
        assertTrue(cmd.contains("exit 127"))
        assertFalse(cmd.contains("|| true"))
    }

    @Test
    fun ignoresShellWritableBusyBox() {
        val cmd = build(bb = "/data/local/tmp/flux_busybox")
        assertFalse(cmd.contains("FLUX_BB="))
        assertFalse(cmd.replace("FLUX_CHROOT='/data/local/tmp/chrootUbuntu'", "").contains("/data/local/tmp"))
    }

    @Test
    fun stagesThroughRootShellStageVerified() {
        val src = File("src/main/kotlin/com/ivarna/fluxlinux/core/terminal/UninstallSessionFactory.kt")
            .takeIf { it.exists() }
            ?: File("app/src/main/kotlin/com/ivarna/fluxlinux/core/terminal/UninstallSessionFactory.kt")
        val text = src.readText()
        assertTrue(text.contains("RootShell.stageVerified(ctx, asset, \"\${ChrootPaths.ROOT_STAGE_DIR}/\$scriptName\")"))
        assertTrue(text.contains("RootShell.ensureBusyBoxResolver(ctx)"))
    }
}
