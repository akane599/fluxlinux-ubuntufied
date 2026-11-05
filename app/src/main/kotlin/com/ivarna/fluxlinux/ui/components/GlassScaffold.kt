package com.ivarna.fluxlinux.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeState

/**
 * A screen on the stock: top bar, content, and an optional bar floating over the bottom.
 * The glass era is over, so nothing is blurred any more; [hazeState] and [blurContent] stay
 * only so existing callers compile unchanged.
 */
@Composable
fun GlassScaffold(
    @Suppress("UNUSED_PARAMETER") hazeState: HazeState,
    @Suppress("UNUSED_PARAMETER") blurContent: Boolean = true,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = topBar,
            // Do not consume IME / status-bar insets here. Terminal owns IME
            // padding so ExtraKeys sit on the keyboard; Home TopBar owns status bars.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            content = { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = paddingValues.calculateTopPadding()) // Only respect top padding from Scaffold
                ) {
                    content()
                }
            }
        )

        // Bottom bar floats over the content.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        ) {
            bottomBar()
        }
    }
}
