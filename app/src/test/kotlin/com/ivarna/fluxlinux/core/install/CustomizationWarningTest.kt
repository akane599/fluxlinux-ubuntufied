package com.ivarna.fluxlinux.core.install

import com.ivarna.fluxlinux.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A customization that failed or stopped without Oh My Zsh (exit 3) still finishes the
 * install, but the success result carries a warning worded for what the run set up.
 */
class CustomizationWarningTest {

    private fun warning(method: String, kind: InstallKind, exit: Int): Int? =
        OnboardingInstallRunner.customizationWarningRes(InstallSteps.of(method, kind), exit)

    @Test
    fun cleanCustomization_hasNoWarning() {
        for (method in listOf("proot", "chroot")) {
            for (kind in InstallKind.entries) {
                assertNull("$method $kind", warning(method, kind, 0))
            }
        }
    }

    @Test
    fun terminalOnly_warnsAboutTheShellSetup_desktopRuns_aboutTheDesktop() {
        for (method in listOf("proot", "chroot")) {
            for (exit in listOf(OnboardingInstallRunner.CUSTOMIZATION_PARTIAL_EXIT, 1, 127, -1)) {
                assertEquals(R.string.install_warning_shell_setup, warning(method, InstallKind.TERMINAL, exit))
                assertEquals(
                    R.string.install_warning_desktop_setup,
                    warning(method, InstallKind.TERMINAL_AND_DESKTOP, exit),
                )
                assertEquals(R.string.install_warning_desktop_setup, warning(method, InstallKind.DESKTOP_ADD_ON, exit))
            }
        }
    }

    @Test
    fun theLog_namesTheMissingOhMyZsh_onlyForThePartialExit() {
        assertTrue(OnboardingInstallRunner.customizationFailureLog(3).contains("Oh My Zsh"))
        assertEquals("Customization failed (exit 1)", OnboardingInstallRunner.customizationFailureLog(1))
    }

    @Test
    fun theScriptsPartialExit_isTheOneTheRunnerNames() {
        val script = repoFile("src/main/assets/scripts/common/setup/setup_customization_xfce.sh").readText()
        assertTrue(script.contains("exit ${OnboardingInstallRunner.CUSTOMIZATION_PARTIAL_EXIT}"))
    }

    @Test
    fun warningStrings_takeTheDistroName() {
        val xml = repoFile("src/main/res/values/strings_install.xml").readText()
        for (name in listOf("install_warning_shell_setup", "install_warning_desktop_setup")) {
            val text = Regex("""<string name="$name">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
            assertTrue(name, text != null && text.startsWith("%1\$s "))
        }
    }

    @Test
    fun bothRuntimes_passTheWarningToTheSuccessResult() {
        val src = repoFile("src/main/kotlin/com/ivarna/fluxlinux/core/install/OnboardingInstallRunner.kt").readText()
        for (name in listOf("runProot", "runChroot")) {
            val body = src.substringAfter("private fun $name(").substringBefore("\n    }\n")
            val outcome = body.indexOf("val customWarning = customizationOutcome(profile, steps, phases, customIdx, onProgress, customExit)")
            assertTrue(name, outcome >= 0)
            assertTrue(name, outcome < body.indexOf("postSuccess(onProgress, phases, customWarning)"))
            // The customization component is still recorded only for a clean exit.
            assertTrue(name, body.contains("customOk = customExit == 0"))
        }
    }

    private fun repoFile(rel: String): File {
        val cwd = File("").absoluteFile
        return listOf(File(cwd, rel), File(cwd, "app/$rel")).first { it.isFile }
    }
}
