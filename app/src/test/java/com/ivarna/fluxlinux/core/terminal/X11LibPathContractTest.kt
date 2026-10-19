package com.ivarna.fluxlinux.core.terminal

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks the Android 17 libXlorie.so fix: Android 17 refuses System.load of a writable
 * file, so both start scripts must hand CmdEntryPoint the package manager's read-only
 * nativeLibraryDir copy (dirname(base.apk)/lib/arm64) via TERMUX_X11_LIB_PATH, and
 * CmdEntryPoint must prefer a candidate it cannot write.
 */
class X11LibPathContractTest {

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        val candidates = listOf(
            File(cwd, rel),
            File(cwd, "app/$rel"),
            File(cwd.parentFile, rel),
            File(cwd.parentFile, "app/$rel")
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("missing $rel (cwd=$cwd)")
    }

    private val scripts = listOf(
        "src/main/assets/scripts/debian/proot/start/start_gui.sh",
        "src/main/assets/scripts/chroot/start_gui_chroot.sh"
    )

    @Test
    fun startScripts_deriveLibPathFromApkDirAndPassIt() {
        for (rel in scripts) {
            val text = repoFile(rel).readText()
            assertTrue(
                "$rel must derive NATIVE_LIB from the APK dir lib/arm64",
                text.contains("NATIVE_LIB=\"\$(dirname \"\$TERMUX_X11_APK_PATH\")/lib/arm64/libXlorie.so\"")
            )
            assertTrue(
                "$rel must export TERMUX_X11_LIB_PATH from NATIVE_LIB",
                text.contains("export TERMUX_X11_LIB_PATH=\"\$NATIVE_LIB\"")
            )
            assertTrue(
                "$rel must pass TERMUX_X11_LIB_PATH on the app_process env line",
                Regex(
                    """TERMUX_X11_APK_PATH="\${'$'}TERMUX_X11_APK_PATH" \\\s*\n""" +
                        """TERMUX_X11_LIB_PATH="\${'$'}TERMUX_X11_LIB_PATH" \\"""
                ).containsMatchIn(text)
            )
            assertTrue(
                "$rel must skip the writable extraction when the read-only lib exists",
                text.contains("if [ -z \"\$TERMUX_X11_LIB_PATH\" ] && {")
            )
            assertTrue(
                "$rel must chmod the extracted fallback read-only",
                text.contains("chmod 0555 \"\$APP_LIB_DIR/libXlorie.so\"")
            )
        }
    }

    @Test
    fun cmdEntryPoint_readsLibPathEnvAndPrefersReadOnly() {
        val java = repoFile("termux-x11/src/main/java/com/termux/x11/CmdEntryPoint.java").readText()
        assertTrue("CmdEntryPoint must read TERMUX_X11_LIB_PATH", java.contains("getenv(\"TERMUX_X11_LIB_PATH\")"))
        assertTrue("CmdEntryPoint must check canWrite", java.contains(".canWrite()"))
        assertTrue(
            "CmdEntryPoint must derive nativeLibraryDir from TERMUX_X11_APK_PATH",
            java.contains("getenv(\"TERMUX_X11_APK_PATH\")") && java.contains("\"arm64-v8a\": return \"arm64\"")
        )
        assertTrue(
            "CmdEntryPoint must keep the /data/data fallback",
            java.contains("\"/data/data/\" + pkg + \"/lib/libXlorie.so\"")
        )
    }
}
