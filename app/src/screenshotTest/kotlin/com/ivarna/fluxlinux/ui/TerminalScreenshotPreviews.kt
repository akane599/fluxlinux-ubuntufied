package com.ivarna.fluxlinux.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.utils.ThemeMode
import com.ivarna.fluxlinux.ui.screens.TerminalEmptyState
import com.ivarna.fluxlinux.ui.screens.TerminalSwitchSettingCard
import com.ivarna.fluxlinux.ui.screens.TerminalTabRow
import com.ivarna.fluxlinux.ui.screens.TerminalTabUi
import com.ivarna.fluxlinux.ui.terminal.TerminalExtraKeys
import com.ivarna.fluxlinux.ui.terminal.TerminalModifierState
import com.ivarna.fluxlinux.ui.theme.FluxLinuxTheme

// Terminal tab row, empty state and extra-keys toolbar from the stateless pieces of
// TerminalScreen (fake tabs, no TerminalView), plus a TerminalSettingsScreen switch card.

private val fakeTabs = listOf(
    TerminalTabUi(title = "Ubuntu Shell", iconRes = R.drawable.distro_ubuntu),
    TerminalTabUi(title = "Ubuntu Shell Rooted", iconRes = R.drawable.distro_ubuntu),
    TerminalTabUi(title = "Host Shell", iconRes = R.drawable.distro_termux),
)

@Composable
private fun TerminalPreviewHost(themeMode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    FluxLinuxTheme(themeMode = themeMode, dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.background) { content() }
    }
}

@PreviewTest
@Preview(name = "TerminalTabs", showBackground = true, widthDp = 400, heightDp = 64)
@Composable
fun TerminalTabRowPreview() {
    TerminalPreviewHost {
        TerminalTabRow(tabs = fakeTabs, selectedIndex = 1, onSelect = {}, onClose = {}, onCloseOthers = {})
    }
}

@PreviewTest
@Preview(name = "TerminalTabsLargeFont", showBackground = true, widthDp = 360, heightDp = 72, fontScale = 1.5f)
@Composable
fun TerminalTabRowLargeFontPreview() {
    TerminalPreviewHost {
        TerminalTabRow(tabs = fakeTabs.take(2), selectedIndex = 0, onSelect = {}, onClose = {}, onCloseOthers = {})
    }
}

@PreviewTest
@Preview(name = "TerminalEmptyFailed", showBackground = true, widthDp = 360, heightDp = 420)
@Composable
fun TerminalEmptyFailedPreview() {
    TerminalPreviewHost {
        TerminalEmptyState(
            opening = false,
            errorMessage = "Host bootstrap not ready — check Settings",
            onOpen = {}
        )
    }
}

// CTRL one-shot active and ALT locked, so both modifier states are covered.
@Composable
private fun ExtraKeysPreview(themeMode: ThemeMode) {
    val modState = TerminalModifierState().apply {
        toggleCtrl()
        lockAlt()
    }
    TerminalPreviewHost(themeMode) { TerminalExtraKeys(modState = modState, terminalView = { null }) }
}

@PreviewTest
@Preview(name = "TerminalExtraKeysDark", showBackground = true, widthDp = 360, heightDp = 96)
@Composable
fun TerminalExtraKeysDarkPreview() {
    ExtraKeysPreview(ThemeMode.DARK)
}

@PreviewTest
@Preview(name = "TerminalExtraKeysLight", showBackground = true, widthDp = 360, heightDp = 96)
@Composable
fun TerminalExtraKeysLightPreview() {
    ExtraKeysPreview(ThemeMode.LIGHT)
}

@PreviewTest
@Preview(name = "TerminalExtraKeysWide", showBackground = true, widthDp = 840, heightDp = 96)
@Composable
fun TerminalExtraKeysWidePreview() {
    ExtraKeysPreview(ThemeMode.DARK)
}

@Composable
private fun AccelerateSwitchPreview(themeMode: ThemeMode) {
    TerminalPreviewHost(themeMode) {
        TerminalSwitchSettingCard(
            title = stringResource(R.string.terminal_settings_accelerate_keys),
            description = stringResource(R.string.terminal_settings_accelerate_keys_summary),
            checked = false,
            onCheckedChange = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@PreviewTest
@Preview(name = "TerminalAccelerateSwitchDark", showBackground = true, widthDp = 360, heightDp = 160)
@Composable
fun TerminalAccelerateSwitchDarkPreview() {
    AccelerateSwitchPreview(ThemeMode.DARK)
}

@PreviewTest
@Preview(name = "TerminalAccelerateSwitchLight", showBackground = true, widthDp = 360, heightDp = 160)
@Composable
fun TerminalAccelerateSwitchLightPreview() {
    AccelerateSwitchPreview(ThemeMode.LIGHT)
}
