@file:OptIn(ExperimentalFoundationApi::class)

package com.ivarna.fluxlinux.ui.terminal

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.ui.theme.FluxAccentMagenta
import com.termux.view.TerminalView
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Toggle + long-press-lock modifier state (nativecode `ModifierState` parity).
 *
 * A modifier stays active until consumed by a key press unless it is locked
 * (long-press); locked modifiers survive consumption and clear on the next tap.
 * Compose `mutableStateOf` fields drive toolbar UI refresh automatically.
 */
class TerminalModifierState {
    var ctrlActive by mutableStateOf(false)
    var ctrlLocked by mutableStateOf(false)
    var altActive by mutableStateOf(false)
    var altLocked by mutableStateOf(false)
    var shiftActive by mutableStateOf(false)
    var shiftLocked by mutableStateOf(false)

    fun readCtrl(autoReadSetFalse: Boolean = true): Boolean {
        if (!ctrlActive) return false
        if (autoReadSetFalse && !ctrlLocked) ctrlActive = false
        return true
    }

    fun readAlt(autoReadSetFalse: Boolean = true): Boolean {
        if (!altActive) return false
        if (autoReadSetFalse && !altLocked) altActive = false
        return true
    }

    fun readShift(autoReadSetFalse: Boolean = true): Boolean {
        if (!shiftActive) return false
        if (autoReadSetFalse && !shiftLocked) shiftActive = false
        return true
    }

    /** Fn is a UI-only row toggle (nativecode returns false here). */
    fun readFn(autoReadSetFalse: Boolean = true): Boolean = false

    fun toggleCtrl() {
        if (ctrlLocked) {
            ctrlActive = false
            ctrlLocked = false
        } else {
            ctrlActive = !ctrlActive
            if (!ctrlActive) ctrlLocked = false
        }
    }

    fun lockCtrl() {
        ctrlActive = true
        ctrlLocked = true
    }

    fun toggleAlt() {
        if (altLocked) {
            altActive = false
            altLocked = false
        } else {
            altActive = !altActive
            if (!altActive) altLocked = false
        }
    }

    fun lockAlt() {
        altActive = true
        altLocked = true
    }

    fun toggleShift() {
        if (shiftLocked) {
            shiftActive = false
            shiftLocked = false
        } else {
            shiftActive = !shiftActive
            if (!shiftActive) shiftLocked = false
        }
    }

    fun lockShift() {
        shiftActive = true
        shiftLocked = true
    }

    /** Clear one-shot modifiers after a key is injected (locked ones survive). */
    fun consumeModifiers() {
        if (!ctrlLocked) ctrlActive = false
        if (!altLocked) altActive = false
        if (!shiftLocked) shiftActive = false
    }
}

/** Nativecode `SPECIAL_KEY_CODES` parity — toolbar key name → Android keycode. */
object TerminalKeyCodes {
    val SPECIAL_KEY_CODES: Map<String, Int> = mapOf(
        "ESC" to KeyEvent.KEYCODE_ESCAPE,
        "TAB" to KeyEvent.KEYCODE_TAB,
        "ENTER" to KeyEvent.KEYCODE_ENTER,
        "BKSP" to KeyEvent.KEYCODE_DEL,
        "DEL" to KeyEvent.KEYCODE_FORWARD_DEL,
        "UP" to KeyEvent.KEYCODE_DPAD_UP,
        "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
        "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
        "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
        "END" to KeyEvent.KEYCODE_MOVE_END,
        "PGUP" to KeyEvent.KEYCODE_PAGE_UP,
        "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN,
        "INS" to KeyEvent.KEYCODE_INSERT,
        "F1" to KeyEvent.KEYCODE_F1,
        "F2" to KeyEvent.KEYCODE_F2,
        "F3" to KeyEvent.KEYCODE_F3,
        "F4" to KeyEvent.KEYCODE_F4,
        "F5" to KeyEvent.KEYCODE_F5,
        "F6" to KeyEvent.KEYCODE_F6,
        "F7" to KeyEvent.KEYCODE_F7,
        "F8" to KeyEvent.KEYCODE_F8,
        "F9" to KeyEvent.KEYCODE_F9,
        "F10" to KeyEvent.KEYCODE_F10,
        "F11" to KeyEvent.KEYCODE_F11,
        "F12" to KeyEvent.KEYCODE_F12
    )
}

/**
 * Nativecode `injectKey` parity — special keys go through `TerminalView.onKeyDown /
 * onKeyUp` with meta flags; printable symbols go through `inputCodePoint`. This is
 * the ONLY toolbar inject path (the old `session.write("\u001b…")` / dispatch path is
 * gone — plan §2.3 / §6 PR-T4).
 */
object TerminalKeyInjector {
    fun injectKey(tv: TerminalView?, key: String, ctrl: Boolean, alt: Boolean, shift: Boolean) {
        tv ?: return
        val keyCode = TerminalKeyCodes.SPECIAL_KEY_CODES[key]
        if (keyCode != null) {
            var meta = 0
            if (ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            if (alt) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            if (shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            val evDown = KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta)
            tv.onKeyDown(keyCode, evDown)
            val evUp = KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, meta)
            tv.onKeyUp(keyCode, evUp)
        } else {
            key.codePoints().forEach { cp -> tv.inputCodePoint(cp, ctrl, alt) }
        }
    }
}


/**
 * Toolbar key layout. Rows 1–2 are Termux's default `extra-keys` property
 * (`[['ESC','/','-','HOME','UP','END','PGUP'],['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]`),
 * so ↑ sits directly above ↓ with ← and → beside it. [EXTRA_ROW] keeps every
 * other key the old FluxLinux toolbar had; [FN] toggles the [F_KEYS] row.
 * Key names are [TerminalKeyCodes] names, printable symbols, or the modifiers.
 */
object TerminalExtraKeysLayout {
    const val CTRL = "CTRL"
    const val ALT = "ALT"
    const val SHIFT = "SHIFT"
    const val FN = "FN"

    val TERMUX_ROWS: List<List<String>> = listOf(
        listOf("ESC", "/", "-", "HOME", "UP", "END", "PGUP"),
        listOf("TAB", CTRL, ALT, "LEFT", "DOWN", "RIGHT", "PGDN"),
    )

    val EXTRA_ROW: List<String> = listOf(SHIFT, "BKSP", "DEL", "INS", "ENTER", "|", "~", "_", "\\", FN)

    val F_KEYS: List<String> = (1..12).map { "F$it" }

    // Termux DEFAULT_CHAR_DISPLAY (classic arrows, ↹ tab, ― minus); other keys show their name.
    private val LABELS: Map<String, String> = mapOf(
        "UP" to "↑",
        "DOWN" to "↓",
        "LEFT" to "←",
        "RIGHT" to "→",
        "TAB" to "↹",
        "-" to "―",
        SHIFT to "SHFT",
        "ENTER" to "ENT",
        FN to "Fn",
    )

    fun label(key: String): String = LABELS[key] ?: key
}

/** Spoken name for keys whose label is a glyph or abbreviation; null → read the label. */
@StringRes
private fun keyDescriptionRes(key: String): Int? = when (key) {
    "UP" -> R.string.extra_key_up
    "DOWN" -> R.string.extra_key_down
    "LEFT" -> R.string.extra_key_left
    "RIGHT" -> R.string.extra_key_right
    "TAB" -> R.string.extra_key_tab
    "-" -> R.string.extra_key_minus
    "ESC" -> R.string.extra_key_escape
    "PGUP" -> R.string.extra_key_page_up
    "PGDN" -> R.string.extra_key_page_down
    "BKSP" -> R.string.extra_key_backspace
    "DEL" -> R.string.extra_key_delete
    "INS" -> R.string.extra_key_insert
    "ENTER" -> R.string.extra_key_enter
    TerminalExtraKeysLayout.SHIFT -> R.string.extra_key_shift
    TerminalExtraKeysLayout.FN -> R.string.extra_key_fn
    else -> null
}

/** Hold-to-repeat schedule for the toolbar keys: [KeyRepeat] accelerated vs constant mode. */
private val LocalKeyRepeatAccelerated = compositionLocalOf { false }

private val KEY_HEIGHT = 44.dp
private val KEY_GAP = 2.dp

/**
 * Termux-style special-keys toolbar ([TerminalExtraKeysLayout]).
 *
 * Rows 1–2: Termux default keys, 7 equal-width cells across the full width.
 * Row 3: horizontal scroll — SHFT, BKSP, DEL, INS, ENT, `|~_\`, Fn toggle, in
 * cells as wide as the grid above. Row 4: F1–F12 (toggled via Fn).
 * Image attach is out of scope for v1 (plan §4).
 */
@Composable
fun TerminalExtraKeys(
    modState: TerminalModifierState,
    terminalView: () -> TerminalView?,
    modifier: Modifier = Modifier,
    accelerateRepeat: Boolean = false,
) {
    val extraRowScroll = rememberScrollState()
    val fRowScroll = rememberScrollState()
    var fnVisible by remember { mutableStateOf(false) }

    fun send(key: String) {
        TerminalKeyInjector.injectKey(
            terminalView(),
            key,
            modState.ctrlActive,
            modState.altActive,
            modState.shiftActive
        )
    }

    fun press(key: String) {
        send(key)
        modState.consumeModifiers()
    }

    // Repeatable keys send on every fire of a hold with the latched modifiers and
    // consume them once when the hold ends, so a hold behaves like one tap.
    fun onFire(key: String): () -> Unit =
        if (KeyRepeat.isRepeatable(key)) { { send(key) } } else { { press(key) } }

    fun onHoldEnd(key: String): (() -> Unit)? =
        if (KeyRepeat.isRepeatable(key)) { { modState.consumeModifiers() } } else null

    @Composable
    fun Key(key: String, cellModifier: Modifier) {
        val label = TerminalExtraKeysLayout.label(key)
        val description = keyDescriptionRes(key)?.let { stringResource(it) }
        when (key) {
            TerminalExtraKeysLayout.CTRL -> ModifierKey(
                label, description, modState.ctrlActive, modState.ctrlLocked,
                { modState.toggleCtrl() }, { modState.lockCtrl() }, cellModifier
            )
            TerminalExtraKeysLayout.ALT -> ModifierKey(
                label, description, modState.altActive, modState.altLocked,
                { modState.toggleAlt() }, { modState.lockAlt() }, cellModifier
            )
            TerminalExtraKeysLayout.SHIFT -> ModifierKey(
                label, description, modState.shiftActive, modState.shiftLocked,
                { modState.toggleShift() }, { modState.lockShift() }, cellModifier
            )
            TerminalExtraKeysLayout.FN ->
                ToolbarKey(label, description, { fnVisible = !fnVisible }, cellModifier, active = fnVisible)
            else -> ToolbarKey(label, description, onFire(key), cellModifier, onHoldEnd = onHoldEnd(key))
        }
    }

    CompositionLocalProvider(LocalKeyRepeatAccelerated provides accelerateRepeat) {
        BoxWithConstraints(
            modifier = modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f))
        ) {
            val columns = TerminalExtraKeysLayout.TERMUX_ROWS.first().size
            // Scroll-row cells match the grid columns so the three rows line up.
            val cellWidth: Dp = (maxWidth - KEY_GAP * (columns + 1)) / columns
            val rowModifier = Modifier
                .fillMaxWidth()
                .height(KEY_HEIGHT)
                .padding(horizontal = KEY_GAP)

            Column(
                modifier = Modifier.padding(vertical = KEY_GAP),
                verticalArrangement = Arrangement.spacedBy(KEY_GAP)
            ) {
                // ── Rows 1–2: Termux default keys (equal width, full width) ─────
                TerminalExtraKeysLayout.TERMUX_ROWS.forEach { row ->
                    Row(modifier = rowModifier, horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                        row.forEach { key -> Key(key, Modifier.weight(1f)) }
                    }
                }

                // ── Row 3: remaining keys, horizontal scroll ────────────────────
                Row(
                    modifier = rowModifier.horizontalScroll(extraRowScroll),
                    horizontalArrangement = Arrangement.spacedBy(KEY_GAP)
                ) {
                    TerminalExtraKeysLayout.EXTRA_ROW.forEach { key -> Key(key, Modifier.width(cellWidth)) }
                }

                // ── Row 4: F-keys (toggle via Fn) ───────────────────────────────
                if (fnVisible) {
                    Row(
                        modifier = rowModifier.horizontalScroll(fRowScroll),
                        horizontalArrangement = Arrangement.spacedBy(KEY_GAP)
                    ) {
                        TerminalExtraKeysLayout.F_KEYS.forEach { key -> Key(key, Modifier.width(cellWidth)) }
                    }
                }
            }
        }
    }
}

/** Single glyphs (arrows, ↹, symbols) get a larger size so they read like Termux's keys. */
private fun labelFontSize(label: String) = if (label.length == 1) 18.sp else 11.sp

@Composable
private fun ModifierKey(
    label: String,
    description: String?,
    active: Boolean,
    locked: Boolean,
    onPress: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxHeight()
            .background(
                if (active) FluxAccentMagenta.copy(alpha = 0.85f)
                else MaterialTheme.colorScheme.surface
            )
            // R5: LOCKED (long-press) gets an outline ring + badge dot so it is
            // visually distinct from a one-shot active modifier.
            .then(
                if (locked) Modifier.border(2.dp, MaterialTheme.colorScheme.onPrimary)
                else Modifier
            )
            .combinedClickable(onClick = onPress, onLongClick = onLongPress)
    ) {
        KeyLabel(
            label = label,
            description = description,
            color = if (active) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        if (locked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.onPrimary, CircleShape)
            )
        }
    }
}

/**
 * Click handling for a toolbar key. Without [onHoldEnd] it is a plain click.
 * With it the key repeats while held on the [KeyRepeat] schedule (constant or
 * accelerated per [LocalKeyRepeatAccelerated], read live on every repeat): [onClick] runs
 * once for a tap or on every fire of a hold, then [onHoldEnd] runs once when the
 * press ends (release, cancel, or leaving composition). A swipe that the toolbar
 * row's scroll claims before [KeyRepeat.PRESS_CONFIRM_DELAY_MS] sends nothing.
 * Accessibility services get the same single-key action through semantics.
 */
@Composable
private fun keyClickModifier(onClick: () -> Unit, onHoldEnd: (() -> Unit)?): Modifier {
    if (onHoldEnd == null) return Modifier.combinedClickable(onClick = onClick)
    val fire by rememberUpdatedState(onClick)
    val end by rememberUpdatedState(onHoldEnd)
    val accelerated by rememberUpdatedState(LocalKeyRepeatAccelerated.current)
    val interactionSource = remember { MutableInteractionSource() }
    return Modifier
        .indication(interactionSource, LocalIndication.current)
        .semantics(mergeDescendants = true) {
            role = Role.Button
            onClick {
                fire()
                end()
                true
            }
        }
        .pointerInput(interactionSource) {
            coroutineScope {
                awaitEachGesture {
                    val press = PressInteraction.Press(awaitFirstDown().position)
                    var fired = false
                    val repeater = launch {
                        delay(KeyRepeat.PRESS_CONFIRM_DELAY_MS)
                        interactionSource.tryEmit(press)
                        fired = true
                        fire()
                        var repeat = 0
                        while (true) {
                            delay(KeyRepeat.delayBeforeRepeat(repeat++, accelerated))
                            fire()
                        }
                    }
                    var up: PointerInputChange? = null
                    try {
                        up = waitForUpOrCancellation()
                    } finally {
                        repeater.cancel()
                        if (up != null) {
                            up.consume()
                            if (!fired) {
                                // Tap released before the hold was confirmed: one key.
                                interactionSource.tryEmit(press)
                                fired = true
                                fire()
                            }
                        }
                        if (fired) {
                            end()
                            interactionSource.tryEmit(
                                if (up != null) PressInteraction.Release(press) else PressInteraction.Cancel(press)
                            )
                        }
                    }
                }
            }
        }
}

@Composable
private fun ToolbarKey(
    label: String,
    description: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onHoldEnd: (() -> Unit)? = null,
    active: Boolean = false
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxHeight()
            .background(
                if (active) FluxAccentMagenta.copy(alpha = 0.85f)
                else MaterialTheme.colorScheme.surface
            )
            .then(keyClickModifier(onClick, onHoldEnd))
    ) {
        KeyLabel(
            label = label,
            description = description,
            color = if (active) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun KeyLabel(label: String, description: String?, color: Color, fontWeight: FontWeight) {
    Text(
        text = label,
        fontSize = labelFontSize(label),
        fontWeight = fontWeight,
        color = color,
        maxLines = 1,
        // TalkBack reads the description instead of a glyph like "↹".
        modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    )
}
