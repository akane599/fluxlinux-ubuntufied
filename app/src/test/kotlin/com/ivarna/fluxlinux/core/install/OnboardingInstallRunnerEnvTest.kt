package com.ivarna.fluxlinux.core.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The PRoot customization `env` words: flags from the steps, every value single-quoted. */
class OnboardingInstallRunnerEnvTest {

    @Test
    fun shellQuote_singleQuotesAndEscapesQuotes() {
        assertEquals("'dark'", OnboardingInstallRunner.shellQuote("dark"))
        assertEquals("''", OnboardingInstallRunner.shellQuote(""))
        assertEquals("'it'\\''s; rm -rf /'", OnboardingInstallRunner.shellQuote("it's; rm -rf /"))
    }

    @Test
    fun customizationEnv_terminalOnly_andDesktop() {
        val terminal = OnboardingInstallRunner.prootCustomizationEnv(
            theme = "dark",
            customizeDesktop = InstallSteps.of("proot", InstallKind.TERMINAL).customizeDesktop,
            skipThemeIcons = false,
            skipOmz = true,
            guestUserEnv = OnboardingInstallRunner.envWords(mapOf("FLUX_GUEST_USER" to "akane")),
        )
        assertEquals(
            "FLUX_THEME='dark' FLUX_CUSTOMIZE_DESKTOP='0' FLUX_SKIP_THEME_ICONS='0' FLUX_SKIP_OMZ='1' " +
                "FLUX_SKIP_POKEMON='0' FLUX_PROOT='1' FLUX_GUEST_USER='akane' " +
                "FLUX_ASSET_DIR='/tmp/flux_xfce_assets'",
            terminal,
        )
        val desktop = OnboardingInstallRunner.prootCustomizationEnv(
            theme = "light",
            customizeDesktop = InstallSteps.of("proot", InstallKind.DESKTOP_ADD_ON).customizeDesktop,
            skipThemeIcons = true,
            skipOmz = false,
            guestUserEnv = emptyList(),
        )
        assertTrue(desktop, desktop.startsWith("FLUX_THEME='light' FLUX_CUSTOMIZE_DESKTOP='1' FLUX_SKIP_THEME_ICONS='1'"))
        assertTrue(desktop, desktop.endsWith("FLUX_PROOT='1' FLUX_ASSET_DIR='/tmp/flux_xfce_assets'"))
    }

    @Test
    fun customizationEnv_aThemeValueStaysOneWord() {
        val env = OnboardingInstallRunner.prootCustomizationEnv(
            theme = "dark FLUX_PROOT=0",
            customizeDesktop = true,
            skipThemeIcons = false,
            skipOmz = false,
            guestUserEnv = emptyList(),
        )
        assertTrue(env, env.startsWith("FLUX_THEME='dark FLUX_PROOT=0' "))
    }
}
