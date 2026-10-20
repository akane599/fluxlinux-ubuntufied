package com.ivarna.fluxlinux.core.terminal

import com.ivarna.fluxlinux.core.root.BusyBoxPaths
import com.ivarna.fluxlinux.core.root.ChrootPaths
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** M14: the chroot uninstall script is staged, verified and run only from the root-only stage. */
class UninstallSessionCommandTest {

    private val sha = "a".repeat(64)

    private fun build(appCopy: String? = "/data/data/com.ivarna.fluxlinux/files/staged_scripts/uninstall_guest_chroot.sh", bb: String? = "/data/adb/magisk/busybox") =
        UninstallSessionFactory.buildChrootUninstallCommand(
            scriptName = "uninstall_guest_chroot.sh",
            sha256 = sha,
            appStagedCopy = appCopy,
            scriptB64 = "IyEvYmluL3NoCg==",
            resolvedBb = bb,
            chrootPath = "/data/local/tmp/chrootUbuntu",
            distroId = "ubuntu_chroot",
        )

    @Test
    fun runsScriptFromRootStage_neverFromDataLocalTmp() {
        val cmd = build()
        val staged = "${ChrootPaths.ROOT_STAGE_DIR}/uninstall_guest_chroot.sh"
        assertTrue(cmd.contains("sh '$staged'"))
        assertTrue(cmd.contains("FLUX_RESOLVE_BB='${BusyBoxPaths.RESOLVER_ON_DEVICE}'"))
        assertTrue(BusyBoxPaths.RESOLVER_ON_DEVICE.startsWith(ChrootPaths.ROOT_STAGE_DIR + "/"))
        // The only /data/local/tmp reference is the rootfs path handed to the script.
        val withoutRootfs = cmd.replace("FLUX_CHROOT='/data/local/tmp/chrootUbuntu'", "")
        assertFalse(cmd, withoutRootfs.contains("/data/local/tmp"))
    }

    @Test
    fun verifiesShaBeforeRunning_andFailsClosed() {
        val cmd = build()
        val verify = cmd.indexOf("\"\$(_fsha '${ChrootPaths.ROOT_STAGE_DIR}/uninstall_guest_chroot.sh')\" = '$sha'")
        val run = cmd.indexOf("sh '${ChrootPaths.ROOT_STAGE_DIR}/uninstall_guest_chroot.sh'")
        assertTrue(cmd, verify in 0 until run)
        assertTrue(cmd.contains("exit 127"))
        assertFalse(cmd.contains("|| true"))
    }

    @Test
    fun withoutAppCopy_usesBase64Install_andIgnoresShellWritableBusyBox() {
        val cmd = build(appCopy = null, bb = "/data/local/tmp/flux_busybox")
        assertTrue(cmd.contains("base64 -d"))
        assertFalse(cmd.contains("FLUX_BB="))
        assertFalse(cmd.replace("FLUX_CHROOT='/data/local/tmp/chrootUbuntu'", "").contains("/data/local/tmp"))
    }
}
