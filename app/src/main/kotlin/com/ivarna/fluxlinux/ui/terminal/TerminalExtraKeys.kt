@file:OptIn(ExperimentalFoundationApi::class)

package com.ivarna.fluxlinux.ui.terminal

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.sharp.ContentPaste
import androidx.compose.material.icons.sharp.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.ui.components.aka.PlateKind
import com.ivarna.fluxlinux.ui.components.aka.plateInks
import com.ivarna.fluxlinux.ui.components.aka.plateOffset
import com.ivarna.fluxlinux.ui.theme.AkaTheme
import com.ivarna.fluxlinux.ui.theme.CodeStyle
import com.ivarna.fluxlinux.ui.theme.FluxSpacing
import com.ivarna.fluxlinux.ui.theme.misregistered
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
 * Toolbar key layout: two rows of [COLUMNS] keys. Each row starts with Termux's
 * default `extra-keys` property
 * (`[['ESC','/','-','HOME','UP','END','PGUP'],['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]`),
 * so ↑ sits directly above ↓ with ← and → beside it, and continues with
 * [EXTRA_KEYS], every other key the old FluxLinux toolbar had, reached by
 * scrolling right. [FN] toggles the [F_KEYS] row.
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

    val EXTRA_KEYS: List<List<String>> = listOf(
        listOf("|", "~", "_", "\\", "BKSP"),
        listOf(SHIFT, FN, "INS", "DEL", "ENTER"),
    )

    val ROWS: List<List<String>> = TERMUX_ROWS.zip(EXTRA_KEYS) { termux, extra -> termux + extra }

    const val COLUMNS = 12

    val F_KEYS: List<String> = (1..COLUMNS).map { "F$it" }

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

/** Narrow cells (a phone with both utility columns): word labels step down one size. */
private val LocalCompactLabels = compositionLocalOf { false }

private val KEY_HEIGHT = 44.dp
private val KEY_GAP = 3.dp
private val MIN_FULL_ROW_CELL_WIDTH = 56.dp
/** Below this cell width "HOME" / "PGUP" at labelMedium touch the key edges. */
private val COMPACT_LABEL_CELL_WIDTH = 40.dp
/** The fixed utility columns (☰ at the left, Paste at the right): one key wide, as tall as the rows. */
private val UTILITY_KEY_WIDTH = KEY_HEIGHT
private val LOCK_INDICATOR_WIDTH = 14.dp
private val LOCK_INDICATOR_HEIGHT = 2.dp

/**
 * Termux-style special-keys toolbar ([TerminalExtraKeysLayout]).
 *
 * Two rows of [TerminalExtraKeysLayout.COLUMNS] equal cells that scroll right
 * together, so the columns stay aligned. When the full rows don't fit, cells are
 * sized so the 7 Termux keys fill the width and the extra keys sit past the edge.
 * F1–F12 (toggled via Fn) form a third row on the same grid.
 *
 * Two fixed utility columns sit outside the scroll and mirror each other: ☰ ([onOpenDrawer])
 * at the left edge and Paste ([onPaste]; long-press: [onPasteLongPress], the clipboard menu)
 * at the right edge, so Paste is always one tap away whatever the keys are scrolled to.
 *
 * Printed as the Federal Blue shell field under the terminal: keys lie flat on the field with
 * bone labels and take one tint up while pressed; a latched modifier (CTRL, ALT, SHFT, Fn)
 * prints as the selection overprint, its plates out of register until pressed.
 */
@Composable
fun TerminalExtraKeys(
    modState: TerminalModifierState,
    terminalView: () -> TerminalView?,
    onOpenDrawer: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
    onPasteLongPress: (() -> Unit)? = null,
    accelerateRepeat: Boolean = false,
) {
    val scroll = rememberScrollState()
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
                .background(AkaTheme.inks.shellField)
        ) {
            val gridWidth = maxWidth - (KEY_GAP + UTILITY_KEY_WIDTH) * 2
            fun cellWidthFor(columns: Int): Dp = (gridWidth - KEY_GAP * (columns + 1)) / columns
            val fullRowCell = cellWidthFor(TerminalExtraKeysLayout.COLUMNS)
            val cellWidth = if (fullRowCell >= MIN_FULL_ROW_CELL_WIDTH) fullRowCell
            else cellWidthFor(TerminalExtraKeysLayout.TERMUX_ROWS.first().size)
            val rowModifier = Modifier
                .height(KEY_HEIGHT)
                .padding(horizontal = KEY_GAP)
            val compactLabels = cellWidth < COMPACT_LABEL_CELL_WIDTH
            val rows = if (fnVisible) TerminalExtraKeysLayout.ROWS + listOf(TerminalExtraKeysLayout.F_KEYS)
            else TerminalExtraKeysLayout.ROWS

            val utilityHeight = KEY_HEIGHT * rows.size + KEY_GAP * (rows.size - 1)

            CompositionLocalProvider(
                LocalCompactLabels provides compactLabels,
                // Ripples and icons on the field take its bone ink.
                LocalContentColor provides AkaTheme.inks.onShellField,
            ) {
                Row(modifier = Modifier.padding(vertical = KEY_GAP)) {
                    DrawerKey(
                        onClick = onOpenDrawer,
                        modifier = Modifier
                            .padding(start = KEY_GAP)
                            .width(UTILITY_KEY_WIDTH)
                            .height(utilityHeight)
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(scroll),
                        verticalArrangement = Arrangement.spacedBy(KEY_GAP)
                    ) {
                        rows.forEach { row ->
                            Row(modifier = rowModifier, horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                                row.forEach { key -> Key(key, Modifier.width(cellWidth)) }
                            }
                        }
                    }
                    PasteKey(
                        onClick = onPaste,
                        onLongClick = onPasteLongPress,
                        modifier = Modifier
                            .padding(end = KEY_GAP)
                            .width(UTILITY_KEY_WIDTH)
                            .height(utilityHeight)
                    )
                }
            }
        }
    }
}

/**
 * Single glyphs (arrows, ↹, symbols) read larger, like Termux's keys, and are cut in the mono
 * face of the terminal; word keys (ESC, HOME, CTRL) stay compact in the label roles.
 */
@Composable
private fun labelStyle(label: String): TextStyle = when {
    label.length == 1 -> MaterialTheme.typography.titleMedium.copy(fontFamily = CodeStyle.fontFamily)
    LocalCompactLabels.current -> MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp)
    else -> MaterialTheme.typography.labelMedium
}

/**
 * A key cap on the shell field: flat on the field at rest, one printed tint up while pressed.
 * An [active] key (a latched modifier, Fn) is the selection overprint whose plates rest out of
 * register and settle into register while pressed. Drawn before the clip, so the plates can
 * spill into the [KEY_GAP] around the cap.
 */
@Composable
private fun keyCapModifier(active: Boolean, pressed: Boolean): Modifier {
    val shape = MaterialTheme.shapes.extraSmall
    val plates = plateInks(PlateKind.SELECTION)
    val offset = plateOffset(registered = pressed)
    return if (active) {
        Modifier
            .misregistered(shape, plates.fill, plates.plateA, plates.plateB) { offset }
            .clip(shape)
    } else {
        Modifier
            .clip(shape)
            .background(if (pressed) AkaTheme.inks.shellFieldHigh else Color.Transparent)
    }
}

@Composable
private fun keyContent(active: Boolean): Color =
    if (active) plateInks(PlateKind.SELECTION).onFill else AkaTheme.inks.onShellField

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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxHeight()
            .then(keyCapModifier(active, pressed))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onPress,
                onLongClick = onLongPress
            )
    ) {
        KeyLabel(
            label = label,
            description = description,
            color = keyContent(active)
        )
        // Locked (long-press) adds a bar under the label, so it reads apart from a one-shot press.
        if (locked) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = FluxSpacing.xs)
                    .size(width = LOCK_INDICATOR_WIDTH, height = LOCK_INDICATOR_HEIGHT)
                    .background(keyContent(true), MaterialTheme.shapes.extraSmall)
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
private fun keyClickModifier(
    onClick: () -> Unit,
    onHoldEnd: (() -> Unit)?,
    // The key's own source, so its cap can show the pressed tint.
    interactionSource: MutableInteractionSource,
): Modifier {
    if (onHoldEnd == null) {
        return Modifier.combinedClickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onClick = onClick
        )
    }
    val fire by rememberUpdatedState(onClick)
    val end by rememberUpdatedState(onHoldEnd)
    val accelerated by rememberUpdatedState(LocalKeyRepeatAccelerated.current)
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
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxHeight()
            .then(keyCapModifier(active, pressed))
            .then(keyClickModifier(onClick, onHoldEnd, interactionSource))
    ) {
        KeyLabel(
            label = label,
            description = description,
            color = keyContent(active)
        )
    }
}

/** ☰: opens the terminal's session drawer; never scrolls away with the key grid. */
@Composable
private fun DrawerKey(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(keyCapModifier(active = false, pressed = pressed))
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            )
    ) {
        Icon(
            imageVector = Icons.Sharp.Menu,
            contentDescription = stringResource(R.string.terminal_open_drawer),
            tint = AkaTheme.inks.onShellField,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** Paste from the clipboard (bracketed when the program asked for it); long-press: clipboard menu. */
@Composable
private fun PasteKey(onClick: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .then(keyCapModifier(active = false, pressed = pressed))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClickLabel = stringResource(R.string.terminal_paste),
                onLongClickLabel = onLongClick?.let { stringResource(R.string.terminal_paste_more) },
                onLongClick = onLongClick,
                onClick = onClick
            )
    ) {
        Icon(
            imageVector = Icons.Sharp.ContentPaste,
            contentDescription = stringResource(R.string.terminal_paste),
            tint = AkaTheme.inks.onShellField,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun KeyLabel(label: String, description: String?, color: Color) {
    Text(
        text = label,
        style = labelStyle(label),
        color = color,
        maxLines = 1,
        // TalkBack reads the description instead of a glyph like "↹".
        modifier = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    )
}
