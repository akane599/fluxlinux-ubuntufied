package com.ivarna.fluxlinux.ui.terminal

/**
 * Hold-to-repeat schedule for the terminal extra-keys toolbar (Termux-style).
 *
 * Timeline of one hold, measured from press-down:
 * - [PRESS_CONFIRM_DELAY_MS]: the first key fires (Android's tap timeout, so a
 *   swipe that scrolls the toolbar row is not mistaken for a key press). A tap
 *   released earlier fires exactly once on release.
 * - [REPEAT_START_MS]: the first repeat fires (Termux `ExtraKeysView` uses
 *   `ViewConfiguration.getLongPressTimeout()`, 400 ms by default).
 * - Constant mode (the default): repeats then fire every [CONSTANT_INTERVAL_MS],
 *   Termux's `DEFAULT_LONG_PRESS_REPEAT_DELAY`.
 * - Accelerated mode (opt-in, `TerminalPreferences.isKeyRepeatAccelerated`):
 *   repeats speed up from [START_INTERVAL_MS] by [INTERVAL_STEP_MS] per repeat
 *   down to the [MIN_INTERVAL_MS] floor, reached about one second into the hold.
 */
object KeyRepeat {
    const val PRESS_CONFIRM_DELAY_MS = 100L
    const val REPEAT_START_MS = 400L
    const val CONSTANT_INTERVAL_MS = 80L
    const val START_INTERVAL_MS = 100L
    const val INTERVAL_STEP_MS = 10L
    const val MIN_INTERVAL_MS = 30L

    /** Toolbar key names (see [TerminalKeyCodes]) that repeat while held. */
    val REPEATABLE_KEYS: Set<String> = setOf("UP", "DOWN", "LEFT", "RIGHT", "PGUP", "PGDN", "BKSP", "DEL")

    fun isRepeatable(key: String): Boolean = key in REPEATABLE_KEYS

    /**
     * Delay in ms between the previous fire and repeat number [repeat] (0-based):
     * repeat 0 follows the first key, so the first repeat lands at [REPEAT_START_MS]
     * in both modes. [accelerated] picks the speed-up schedule over the constant one.
     */
    fun delayBeforeRepeat(repeat: Int, accelerated: Boolean): Long {
        require(repeat >= 0) { "repeat must be >= 0, was $repeat" }
        if (repeat == 0) return REPEAT_START_MS - PRESS_CONFIRM_DELAY_MS
        if (!accelerated) return CONSTANT_INTERVAL_MS
        return (START_INTERVAL_MS - (repeat - 1).toLong() * INTERVAL_STEP_MS).coerceAtLeast(MIN_INTERVAL_MS)
    }
}
