package com.ivarna.fluxlinux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.ivarna.fluxlinux.core.utils.ThemeMode
import com.ivarna.fluxlinux.core.utils.UbuntuRuntimeMode
import com.ivarna.fluxlinux.ui.onboarding.DonePage
import com.ivarna.fluxlinux.ui.onboarding.OptionsPage
import com.ivarna.fluxlinux.ui.onboarding.StorageAccessContent
import com.ivarna.fluxlinux.ui.onboarding.WelcomePage
import com.ivarna.fluxlinux.ui.install.InstallProgressPanel
import androidx.compose.material3.MaterialTheme
import com.ivarna.fluxlinux.ui.theme.FluxLinuxTheme

// Onboarding screenshot previews built from the stateless leaves of OnboardingFlowScreen.
// The real pages read device state (permissions, lifecycle), so these pass fake values only.

@Composable
private fun OnboardingPreviewHost(content: @Composable () -> Unit) {
    FluxLinuxTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
        // Same backdrop as OnboardingFlowScreen's root Box.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
    }
}

@PreviewTest
@Preview(name = "OnboardingStorage", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun OnboardingStoragePreview() {
    OnboardingPreviewHost {
        StorageAccessContent(
            granted = false,
            opensSettings = true,
            onBack = {},
            onGrant = {},
            onContinue = {}
        )
    }
}

@PreviewTest
@Preview(
    name = "OnboardingStorageLargeFont",
    showBackground = true,
    widthDp = 360,
    heightDp = 800,
    fontScale = 1.5f
)
@Composable
fun OnboardingStorageLargeFontPreview() {
    OnboardingPreviewHost {
        StorageAccessContent(
            granted = false,
            opensSettings = true,
            onBack = {},
            onGrant = {},
            onContinue = {}
        )
    }
}

// First page: terminal-first pitch, the desktop as an option.
@PreviewTest
@Preview(name = "OnboardingWelcome", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun OnboardingWelcomePreview() {
    OnboardingPreviewHost {
        WelcomePage(onNext = {})
    }
}

// Terminal-only setup (the default): no "Start desktop".
@PreviewTest
@Preview(name = "OnboardingDone", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun OnboardingDonePreview() {
    OnboardingPreviewHost {
        DonePage(onTerminal = {}, onDesktop = null)
    }
}

// Setup with "Also install the XFCE desktop" on: Start desktop as the secondary action.
@PreviewTest
@Preview(name = "OnboardingDoneDesktop", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun OnboardingDoneDesktopPreview() {
    OnboardingPreviewHost {
        DonePage(onTerminal = {}, onDesktop = {})
    }
}

// Options page as most people see it: no root, desktop off (the default).
@PreviewTest
@Preview(name = "OnboardingOptions", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun OnboardingOptionsPreview() {
    OnboardingPreviewHost {
        OptionsPage(theme = "dark", onTheme = {}, onBack = {}, onInstall = {})
    }
}

// Options page on a rooted phone with chroot picked (the runtime cards appear only with root)
// and the desktop switched on, so the theme cards show.
@PreviewTest
@Preview(name = "OnboardingOptionsChroot", showBackground = true, widthDp = 360, heightDp = 1000)
@Composable
fun OnboardingOptionsChrootPreview() {
    OnboardingPreviewHost {
        OptionsPage(
            theme = "dark",
            onTheme = {},
            onBack = {},
            onInstall = {},
            installDesktop = true,
            runtime = UbuntuRuntimeMode.CHROOT,
            rootAvailable = true,
            onRuntime = {}
        )
    }
}

@PreviewTest
@Preview(name = "OnboardingDoneChroot", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun OnboardingDoneChrootPreview() {
    OnboardingPreviewHost {
        DonePage(onTerminal = {}, onDesktop = null, runtime = UbuntuRuntimeMode.CHROOT)
    }
}

private const val FakeShellWarning = "Ubuntu 26.04 is installed, but its shell setup didn't finish, so zsh, " +
    "Oh My Zsh or its plugins may be missing. Re-run setup to try again."

private const val FakeInstallLog = "Setting up zsh (5.9-8) ...\n" +
    "Cloning Oh My Zsh...\n" +
    "fatal: unable to access 'https://github.com/ohmyzsh/ohmyzsh.git/': Could not resolve host\n" +
    "Customization partial: Oh My Zsh is missing (exit 3)\n" +
    "WARNING: $FakeShellWarning\n"

// Terminal-only setup that finished with a warning (shell setup partial), the log folded.
@PreviewTest
@Preview(name = "OnboardingDoneWarning", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun OnboardingDoneWarningPreview() {
    OnboardingPreviewHost {
        DonePage(onTerminal = {}, onDesktop = null, warning = FakeShellWarning, logText = FakeInstallLog)
    }
}

// The install wizard (Re-run Ubuntu setup) done with the same warning, its log open.
@PreviewTest
@Preview(name = "InstallDoneWarning", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun InstallDoneWarningPreview() {
    OnboardingPreviewHost {
        InstallProgressPanel(
            percent = 100,
            phaseLabel = "Complete",
            detail = "Environment ready",
            logText = FakeInstallLog,
            failed = false,
            errorMessage = null,
            showLog = true,
            onToggleLog = {},
            onRetry = {},
            onDone = {},
            isDone = true,
            warning = FakeShellWarning,
            warningHint = "To try again, tap Re-run Ubuntu setup under Recovery on the Ubuntu page.",
        )
    }
}
