package com.ivarna.fluxlinux.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.sharp.VolumeUp
import androidx.compose.material.icons.sharp.DisplaySettings
import androidx.compose.material.icons.sharp.Folder
import androidx.compose.material.icons.sharp.SdStorage
import androidx.compose.material.icons.sharp.Storage
import androidx.compose.material.icons.sharp.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.ivarna.fluxlinux.core.utils.ThemeMode
import com.ivarna.fluxlinux.core.utils.UbuntuRuntimeMode
import com.ivarna.fluxlinux.ui.components.DesktopAddOnUi
import com.ivarna.fluxlinux.ui.components.DesktopRun
import com.ivarna.fluxlinux.ui.components.SharedStorageUi
import com.ivarna.fluxlinux.ui.components.UbuntuDashboard
import com.ivarna.fluxlinux.ui.components.UbuntuDashboardActions
import com.ivarna.fluxlinux.ui.components.UbuntuDashboardState
import com.ivarna.fluxlinux.ui.components.UbuntuRuntimeCard
import com.ivarna.fluxlinux.ui.components.UbuntuRuntimeUi
import com.ivarna.fluxlinux.ui.components.UbuntuStatus
import com.ivarna.fluxlinux.ui.components.aka.SectionLabel
import com.ivarna.fluxlinux.ui.screens.SettingsAboutSection
import com.ivarna.fluxlinux.ui.screens.SettingsPageNavRow
import com.ivarna.fluxlinux.ui.screens.SettingsPageScaffold
import com.ivarna.fluxlinux.ui.theme.AkaTheme
import com.ivarna.fluxlinux.ui.theme.FluxLinuxTheme
import com.ivarna.fluxlinux.ui.theme.FluxSpacing

// Screenshot previews built from the stateless leaf composables the real screens
// (HomeScreen, SettingsScreen) are made of. The screens themselves read device
// state (filesystem, services), so these use fake data only. The app is always dark.

private val fakeLinkNames = listOf("shared", "downloads", "documents", "dcim", "pictures", "music", "movies")

private val storageNotGranted = SharedStorageUi(
    supported = true,
    granted = false,
    linksPresent = false,
    linkNames = fakeLinkNames
)

private val storageReady = SharedStorageUi(
    supported = true,
    granted = true,
    linksPresent = true,
    linkNames = fakeLinkNames
)

@Composable
private fun PreviewHost(content: @Composable () -> Unit) {
    FluxLinuxTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
        // verticalScroll gives children unbounded height, as in the real scroll hosts.
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.verticalScroll(rememberScrollState())) { content() }
        }
    }
}

@Composable
private fun Dashboard(state: UbuntuDashboardState) {
    PreviewHost { UbuntuDashboard(state = state, actions = UbuntuDashboardActions()) }
}

// Ubuntu ready with an installed, stopped desktop; shared storage not allowed yet.
@PreviewTest
@Preview(name = "Home", showBackground = true, widthDp = 360, heightDp = 1240)
@Composable
fun HomeScreenPreview() {
    Dashboard(
        UbuntuDashboardState(
            status = UbuntuStatus.READY,
            desktop = DesktopAddOnUi.Installed(DesktopRun.STOPPED),
            storage = storageNotGranted,
        )
    )
}

// The desktop running: the header still says Ubuntu is ready; the Desktop card says running.
@PreviewTest
@Preview(name = "HomeRunning", showBackground = true, widthDp = 360, heightDp = 1060)
@Composable
fun HomeRunningPreview() {
    Dashboard(
        UbuntuDashboardState(
            status = UbuntuStatus.READY,
            desktop = DesktopAddOnUi.Installed(DesktopRun.RUNNING),
            storage = storageReady,
        )
    )
}

// Desktop add-on states: not installed (terminal-only setup), installing, failed, and still
// being checked (a chroot's su probe before its flag is set).
@PreviewTest
@Preview(name = "HomeDesktopNotInstalled", showBackground = true, widthDp = 360, heightDp = 1100)
@Composable
fun HomeDesktopNotInstalledPreview() {
    Dashboard(
        UbuntuDashboardState(
            status = UbuntuStatus.READY,
            desktop = DesktopAddOnUi.NotInstalled,
            storage = storageReady,
        )
    )
}

@PreviewTest
@Preview(name = "HomeDesktopInstalling", showBackground = true, widthDp = 360, heightDp = 1080)
@Composable
fun HomeDesktopInstallingPreview() {
    Dashboard(
        UbuntuDashboardState(
            status = UbuntuStatus.READY,
            desktop = DesktopAddOnUi.Installing(
                percent = 42,
                phaseLabel = "Installing the XFCE desktop…",
                detail = "Unpacking xfce4-panel (4.20.4-1) ..."
            ),
            storage = storageReady,
        )
    )
}

@PreviewTest
@Preview(name = "HomeDesktopFailed", showBackground = true, widthDp = 360, heightDp = 1060)
@Composable
fun HomeDesktopFailedPreview() {
    Dashboard(
        UbuntuDashboardState(
            status = UbuntuStatus.READY,
            desktop = DesktopAddOnUi.Failed("apt-get exited with code 100"),
            storage = storageReady,
        )
    )
}

@PreviewTest
@Preview(name = "HomeDesktopChecking", showBackground = true, widthDp = 360, heightDp = 1000)
@Composable
fun HomeDesktopCheckingPreview() {
    Dashboard(
        UbuntuDashboardState(
            status = UbuntuStatus.READY,
            desktop = DesktopAddOnUi.Checking,
            storage = storageReady,
            runtime = UbuntuRuntimeMode.CHROOT,
        )
    )
}

// Ubuntu not set up: only the header and Recovery show (the desktop state is never drawn).
@PreviewTest
@Preview(name = "HomeNotInstalled", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun HomeNotInstalledPreview() {
    Dashboard(
        UbuntuDashboardState(
            status = UbuntuStatus.NOT_INSTALLED,
            desktop = DesktopAddOnUi.NotInstalled,
            storage = storageNotGranted,
        )
    )
}

// The Home preview at 1.5× font scale.
@PreviewTest
@Preview(name = "HomeLargeFont", showBackground = true, widthDp = 360, heightDp = 1800, fontScale = 1.5f)
@Composable
fun HomeLargeFontPreview() {
    Dashboard(
        UbuntuDashboardState(
            status = UbuntuStatus.READY,
            desktop = DesktopAddOnUi.Installed(DesktopRun.STOPPED),
            storage = storageNotGranted,
        )
    )
}

// Chroot mode, both installs present, desktop installed and stopped: the runtime chip offers
// the quick switch.
@PreviewTest
@Preview(name = "HomeChroot", showBackground = true, widthDp = 360, heightDp = 1120)
@Composable
fun HomeChrootPreview() {
    Dashboard(
        UbuntuDashboardState(
            status = UbuntuStatus.READY,
            desktop = DesktopAddOnUi.Installed(DesktopRun.STOPPED),
            storage = storageReady,
            runtime = UbuntuRuntimeMode.CHROOT,
            canSwitchRuntime = true
        )
    )
}

// Chroot mode, root denied: the desktop is never drawn (detection can't run without root).
@PreviewTest
@Preview(name = "HomeChrootNeedsRoot", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
fun HomeChrootNeedsRootPreview() {
    Dashboard(
        UbuntuDashboardState(
            status = UbuntuStatus.NEEDS_ROOT,
            desktop = DesktopAddOnUi.Checking,
            storage = storageReady,
            runtime = UbuntuRuntimeMode.CHROOT,
            canSwitchRuntime = true
        )
    )
}

// Chroot mode while root and the install are still being probed, at 1.5× font scale.
@PreviewTest
@Preview(name = "HomeChrootLargeFont", showBackground = true, widthDp = 360, heightDp = 520, fontScale = 1.5f)
@Composable
fun HomeChrootLargeFontPreview() {
    Dashboard(
        UbuntuDashboardState(
            status = UbuntuStatus.CHECKING,
            desktop = DesktopAddOnUi.Checking,
            storage = storageReady,
            runtime = UbuntuRuntimeMode.CHROOT
        )
    )
}

// Settings > Ubuntu runtime: chroot selected; PRoot after a denied root probe; a probe running.
@PreviewTest
@Preview(name = "SettingsRuntime", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun SettingsRuntimePreview() {
    PreviewHost {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            UbuntuRuntimeCard(state = UbuntuRuntimeUi(selected = UbuntuRuntimeMode.CHROOT), onSelect = {})
            UbuntuRuntimeCard(
                state = UbuntuRuntimeUi(
                    selected = UbuntuRuntimeMode.PROOT,
                    message = "Root access was denied or isn't available. Ubuntu stays on PRoot. " +
                        "Allow AkaTerminal in your root manager (Magisk, KernelSU) and try again.",
                    messageIsError = true
                ),
                onSelect = {}
            )
            UbuntuRuntimeCard(
                state = UbuntuRuntimeUi(selected = UbuntuRuntimeMode.PROOT, checking = true),
                onSelect = {}
            )
        }
    }
}

// The Settings hub, built from the same rows SettingsScreen uses (the screen itself probes
// root, storage access and the phantom process killer, so the preview fakes those rows).
@PreviewTest
@Preview(name = "Settings", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
fun SettingsPreview() {
    FluxLinuxTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
        SettingsPageScaffold(title = "Settings", onBack = {}) {
            Box(Modifier.padding(horizontal = FluxSpacing.lg, vertical = FluxSpacing.sm)) {
                UbuntuRuntimeCard(state = UbuntuRuntimeUi(selected = UbuntuRuntimeMode.CHROOT), onSelect = {})
            }
            SettingsPageNavRow(
                icon = Icons.Sharp.SdStorage,
                title = "Shared storage",
                subtitle = "Not allowed: guests cannot see /sdcard or USB drives. Tap, then enable All files access",
                iconTint = AkaTheme.inks.held,
                onClick = {},
            )
            SettingsPageNavRow(Icons.Sharp.Terminal, "Terminal", {}, subtitle = "Font zoom, extra keys, and guest shell")
            SettingsPageNavRow(Icons.Sharp.DisplaySettings, "X11 display", {}, subtitle = "Scale, fullscreen, input for embedded X11")
            SettingsPageNavRow(Icons.AutoMirrored.Sharp.VolumeUp, "Audio", {}, subtitle = "Host PulseAudio status, start, restart, logs")
            SectionLabel("Advanced")
            SettingsPageNavRow(Icons.Sharp.Storage, "Chroot", {}, subtitle = "Installed roots, size, kill orphan processes")
            SettingsPageNavRow(Icons.Sharp.Folder, "PRoot", {}, subtitle = "Installed containers and app-storage size")
        }
    }
}

@PreviewTest
@Preview(name = "SettingsAbout", showBackground = true, widthDp = 360, heightDp = 900)
@Composable
fun SettingsAboutPreview() {
    PreviewHost { SettingsAboutSection(open = {}) }
}

@PreviewTest
@Preview(name = "SettingsAboutLargeFont", showBackground = true, widthDp = 360, heightDp = 1100, fontScale = 1.3f)
@Composable
fun SettingsAboutLargeFontPreview() {
    PreviewHost { SettingsAboutSection(open = {}) }
}
