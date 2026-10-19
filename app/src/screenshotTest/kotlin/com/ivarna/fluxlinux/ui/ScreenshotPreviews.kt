package com.ivarna.fluxlinux.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.tools.screenshot.PreviewTest
import com.ivarna.fluxlinux.core.data.Distro
import com.ivarna.fluxlinux.core.desktop.DesktopSession
import com.ivarna.fluxlinux.ui.components.ActiveDesktopCard
import com.ivarna.fluxlinux.ui.components.CompactDistroCard
import com.ivarna.fluxlinux.ui.components.DistroCard
import com.ivarna.fluxlinux.ui.components.FluxSwitch
import com.ivarna.fluxlinux.ui.components.GlassSettingCard
import com.ivarna.fluxlinux.ui.components.MethodTab
import com.ivarna.fluxlinux.ui.components.MethodTabs
import com.ivarna.fluxlinux.ui.theme.FluxLinuxTheme

// Screenshot previews built from the stateless leaf composables the real screens
// (HomeScreen, DistroScreen, SettingsScreen) are made of. The screens themselves
// read device state (filesystem, Shizuku, services), so these use fake data only.

private val fakeDistro = Distro(
    id = "debian",
    name = "Debian",
    description = "The universal operating system. Stable and reliable.",
    color = Color(0xFFD70A53),
    prootSupported = true,
    chrootSupported = false
)

private val fakeComingSoonDistro = Distro(
    id = "fedora",
    name = "Fedora",
    description = "Coming soon.",
    color = Color(0xFF3C6EB4),
    comingSoon = true,
    prootSupported = true,
    chrootSupported = true
)

@Composable
private fun PreviewHost(content: @Composable () -> Unit) {
    FluxLinuxTheme(dynamicColor = false) {
        // verticalScroll gives children unbounded height, as in the real LazyColumn/scroll hosts
        // (cards use fillMaxHeight for their accent stripe and would otherwise stretch to the preview).
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.verticalScroll(rememberScrollState())) { content() }
        }
    }
}

@PreviewTest
@Preview(name = "Home", showBackground = true, widthDp = 360, heightDp = 330)
@Composable
fun HomeScreenPreview() {
    PreviewHost {
        Column(
            modifier = Modifier.padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ActiveDesktopCard(
                session = DesktopSession(
                    distroId = "debian",
                    distroName = "Debian",
                    type = DesktopSession.Type.XFCE4,
                    phase = DesktopSession.Phase.Running
                ),
                onOpen = {},
                onStop = {},
                onLogs = {},
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            DistroCard(
                distro = fakeDistro,
                isInstalled = true,
                onInstall = {},
                onUninstall = {},
                onNavigateToSettings = {},
                onNavigateToStart = {}
            )
        }
    }
}

@PreviewTest
@Preview(name = "DistroPicker", showBackground = true, widthDp = 360, heightDp = 330)
@Composable
fun DistroPickerPreview() {
    PreviewHost {
        Column(
            modifier = Modifier.padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Available Distros",
                color = MaterialTheme.colorScheme.secondary,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            MethodTabs(
                selected = MethodTab.PROOT,
                onSelected = {},
                prootCount = 2,
                chrootCount = 1
            )
            DistroCard(
                distro = fakeDistro,
                isInstalled = false,
                onInstall = {},
                onUninstall = {},
                onNavigateToSettings = {},
                onNavigateToStart = {}
            )
            CompactDistroCard(distro = fakeComingSoonDistro)
        }
    }
}

@PreviewTest
@Preview(name = "Settings", showBackground = true, widthDp = 360, heightDp = 260)
@Composable
fun SettingsPreview() {
    PreviewHost {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "App",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary
            )
            SettingRow(title = "Terminal", subtitle = "Font, size and extra keys", checked = true)
            SettingRow(title = "Audio", subtitle = "PulseAudio output for guests", checked = false)
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean) {
    GlassSettingCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(14.dp))
            FluxSwitch(checked = checked, onCheckedChange = null)
        }
    }
}
