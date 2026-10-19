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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.tools.screenshot.PreviewTest
import com.ivarna.fluxlinux.core.utils.ThemeMode
import com.ivarna.fluxlinux.ui.components.FluxSwitch
import com.ivarna.fluxlinux.ui.components.GlassSettingCard
import com.ivarna.fluxlinux.ui.components.SharedStorageUi
import com.ivarna.fluxlinux.ui.components.UbuntuDashboard
import com.ivarna.fluxlinux.ui.components.UbuntuDashboardActions
import com.ivarna.fluxlinux.ui.components.UbuntuDashboardState
import com.ivarna.fluxlinux.ui.components.UbuntuStatus
import com.ivarna.fluxlinux.ui.theme.FluxLinuxTheme

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

@PreviewTest
@Preview(name = "Home", showBackground = true, widthDp = 360, heightDp = 1240)
@Composable
fun HomeScreenPreview() {
    Dashboard(UbuntuDashboardState(status = UbuntuStatus.STOPPED, storage = storageNotGranted))
}

@PreviewTest
@Preview(name = "HomeRunning", showBackground = true, widthDp = 360, heightDp = 1120)
@Composable
fun HomeRunningPreview() {
    Dashboard(UbuntuDashboardState(status = UbuntuStatus.RUNNING, storage = storageReady))
}

@PreviewTest
@Preview(name = "HomeAutoStart", showBackground = true, widthDp = 360, heightDp = 330)
@Composable
fun HomeAutoStartPreview() {
    Dashboard(
        UbuntuDashboardState(
            status = UbuntuStatus.STOPPED,
            autoStartPending = true,
            storage = storageReady
        )
    )
}

@PreviewTest
@Preview(name = "HomeStartFailed", showBackground = true, widthDp = 360, heightDp = 480)
@Composable
fun HomeStartFailedPreview() {
    Dashboard(
        UbuntuDashboardState(
            status = UbuntuStatus.STOPPED,
            lastStartFailed = true,
            storage = storageReady
        )
    )
}

@PreviewTest
@Preview(name = "HomeNotInstalled", showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun HomeNotInstalledPreview() {
    Dashboard(UbuntuDashboardState(status = UbuntuStatus.NOT_INSTALLED, storage = storageNotGranted))
}

@PreviewTest
@Preview(name = "HomeLargeFont", showBackground = true, widthDp = 360, heightDp = 1800, fontScale = 1.5f)
@Composable
fun HomeLargeFontPreview() {
    Dashboard(UbuntuDashboardState(status = UbuntuStatus.STOPPED, storage = storageNotGranted))
}

@PreviewTest
@Preview(name = "Settings", showBackground = true, widthDp = 360, heightDp = 340)
@Composable
fun SettingsPreview() {
    PreviewHost {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionHeader("App")
            SettingRow(
                title = "Open Ubuntu desktop when FluxLinux starts",
                subtitle = "Starts the desktop each time you open the app",
                checked = true
            )
            SettingRow(title = "Terminal", subtitle = "Font, size and extra keys", checked = false)
            SectionHeader("Advanced")
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.secondary
    )
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
