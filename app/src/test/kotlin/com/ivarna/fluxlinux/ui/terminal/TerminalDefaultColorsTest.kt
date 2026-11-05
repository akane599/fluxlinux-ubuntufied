package com.ivarna.fluxlinux.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.ivarna.fluxlinux.ui.theme.Iro
import com.ivarna.fluxlinux.ui.theme.TerminalBackground
import com.termux.terminal.TerminalColorScheme
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The vendored emulator's default text, background and cursor colours are the app's Compose
 * tokens (the app never loads a colors.properties into it), so the shell can't drift back to an
 * older palette when the theme changes: the risograph Bone/Red outlived the Akane-iro redesign.
 */
class TerminalDefaultColorsTest {

    private fun hex(argb: Int) = "%08x".format(argb)

    private fun assertDefault(token: Color, index: Int) {
        assertEquals(hex(token.toArgb()), hex(TerminalColorScheme().mDefaultColors[index]))
        // What a new session's renderer paints with.
        assertEquals(hex(token.toArgb()), hex(TerminalColors().mCurrentColors[index]))
    }

    @Test
    fun foreground_isShironeri() = assertDefault(Iro.Shironeri, TextStyle.COLOR_INDEX_FOREGROUND)

    @Test
    fun background_isTerminalBackground() = assertDefault(TerminalBackground, TextStyle.COLOR_INDEX_BACKGROUND)

    @Test
    fun cursor_isAkane() = assertDefault(Iro.Akane, TextStyle.COLOR_INDEX_CURSOR)
}
