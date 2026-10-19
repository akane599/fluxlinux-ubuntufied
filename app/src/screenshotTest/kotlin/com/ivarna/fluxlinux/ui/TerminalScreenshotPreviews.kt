package com.ivarna.fluxlinux.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.utils.ThemeMode
import com.ivarna.fluxlinux.ui.screens.TerminalEmptyState
import com.ivarna.fluxlinux.ui.screens.TerminalTabRow
import com.ivarna.fluxlinux.ui.screens.TerminalTabUi
import com.ivarna.fluxlinux.ui.theme.FluxLinuxTheme

// Terminal tab row + empty state from the stateless pieces of TerminalScreen, fake tabs only.

private val fakeTabs = listOf(
    TerminalTabUi(title = "Ubuntu Shell", iconRes = R.drawable.distro_ubuntu),
    TerminalTabUi(title = "Ubuntu Shell Rooted", iconRes = R.drawable.distro_ubuntu),
    TerminalTabUi(title = "Host Shell", iconRes = R.drawable.distro_termux),
)

@Composable
private fun TerminalPreviewHost(content: @Composable () -> Unit) {
    FluxLinuxTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
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
