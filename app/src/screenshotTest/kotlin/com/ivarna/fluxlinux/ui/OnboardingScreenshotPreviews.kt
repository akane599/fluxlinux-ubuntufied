package com.ivarna.fluxlinux.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.ivarna.fluxlinux.core.utils.ThemeMode
import com.ivarna.fluxlinux.ui.onboarding.DonePage
import com.ivarna.fluxlinux.ui.onboarding.StorageAccessContent
import com.ivarna.fluxlinux.ui.theme.FluxDarkSurface
import com.ivarna.fluxlinux.ui.theme.FluxLinuxTheme

// Onboarding screenshot previews built from the stateless leaves of OnboardingFlowScreen.
// The real pages read device state (permissions, lifecycle), so these pass fake values only.

@Composable
private fun OnboardingPreviewHost(content: @Composable () -> Unit) {
    FluxLinuxTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
        // Same backdrop as OnboardingFlowScreen's root Box.
        Box(Modifier.fillMaxSize().background(FluxDarkSurface)) { content() }
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

@PreviewTest
@Preview(name = "OnboardingDone", showBackground = true, widthDp = 360, heightDp = 800)
@Composable
fun OnboardingDonePreview() {
    OnboardingPreviewHost {
        DonePage(onHome = {}, onTerminal = {}, onDesktop = {})
    }
}
