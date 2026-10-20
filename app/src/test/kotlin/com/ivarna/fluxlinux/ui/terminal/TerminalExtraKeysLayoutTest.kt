package com.ivarna.fluxlinux.ui.terminal

import com.ivarna.fluxlinux.ui.terminal.TerminalExtraKeysLayout.ALT
import com.ivarna.fluxlinux.ui.terminal.TerminalExtraKeysLayout.CTRL
import com.ivarna.fluxlinux.ui.terminal.TerminalExtraKeysLayout.FN
import com.ivarna.fluxlinux.ui.terminal.TerminalExtraKeysLayout.SHIFT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalExtraKeysLayoutTest {

    private val allKeys = TerminalExtraKeysLayout.ROWS.flatten() + TerminalExtraKeysLayout.F_KEYS

    @Test
    fun termuxRows_matchTermuxDefaultExtraKeys() {
        assertEquals(
            listOf(
                listOf("ESC", "/", "-", "HOME", "UP", "END", "PGUP"),
                listOf("TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN"),
            ),
            TerminalExtraKeysLayout.TERMUX_ROWS,
        )
    }

    @Test
    fun rows_areTwoFullGridRowsStartingWithTermuxKeys() {
        assertEquals(2, TerminalExtraKeysLayout.ROWS.size)
        TerminalExtraKeysLayout.ROWS.forEachIndexed { i, row ->
            assertEquals(TerminalExtraKeysLayout.COLUMNS, row.size)
            assertEquals(TerminalExtraKeysLayout.TERMUX_ROWS[i], row.take(TerminalExtraKeysLayout.TERMUX_ROWS[i].size))
        }
        assertEquals(TerminalExtraKeysLayout.COLUMNS, TerminalExtraKeysLayout.F_KEYS.size)
    }

    @Test
    fun arrows_upAboveDown_leftAndRightFlankDown() {
        val (top, bottom) = TerminalExtraKeysLayout.TERMUX_ROWS
        val down = bottom.indexOf("DOWN")
        assertEquals(down, top.indexOf("UP"))
        assertEquals(down - 1, bottom.indexOf("LEFT"))
        assertEquals(down + 1, bottom.indexOf("RIGHT"))
    }

    @Test
    fun everyOldToolbarKeyIsStillReachable() {
        val oldKeys = listOf(
            CTRL, ALT, SHIFT, "ESC", "TAB", "ENTER", "BKSP",
            "LEFT", "UP", "DOWN", "RIGHT", "DEL", "INS",
            "/", "|", "~", "-", "_", "\\", FN,
        ) + (1..12).map { "F$it" }
        oldKeys.forEach { assertTrue(it, it in allKeys) }
        assertEquals("no duplicate keys", allKeys.size, allKeys.toSet().size)
    }

    @Test
    fun everyKeyIsInjectableOrAModifier() {
        val uiKeys = setOf(CTRL, ALT, SHIFT, FN)
        allKeys.filterNot { it in uiKeys }.forEach { key ->
            assertTrue(
                key,
                key in TerminalKeyCodes.SPECIAL_KEY_CODES || key.codePointCount(0, key.length) == 1,
            )
        }
    }

    @Test
    fun everyRepeatableKeyIsOnTheToolbar() {
        KeyRepeat.REPEATABLE_KEYS.forEach { assertTrue(it, it in allKeys) }
    }

    @Test
    fun labels_useTermuxGlyphs() {
        assertEquals(
            listOf("ESC", "/", "―", "HOME", "↑", "END", "PGUP"),
            TerminalExtraKeysLayout.TERMUX_ROWS[0].map(TerminalExtraKeysLayout::label),
        )
        assertEquals(
            listOf("↹", "CTRL", "ALT", "←", "↓", "→", "PGDN"),
            TerminalExtraKeysLayout.TERMUX_ROWS[1].map(TerminalExtraKeysLayout::label),
        )
    }
}
