package com.ivarna.fluxlinux.ui.terminal

import android.view.View
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

/**
 * Who gets a gesture that starts on the terminal: the terminal (scrolls, pinch zoom, selection,
 * mouse drags) or the session drawer, whose drag sits on an ancestor.
 *
 * Compose hands an `AndroidView`'s moves to its ancestors first (Android's intercept model), and
 * the drawer's drag starts on any sideways drift past the touch slop: a scroll stopped as soon as
 * the finger slid a little. Claiming every gesture for the terminal instead left the drawer
 * opening only from a thin edge strip. The rule: nothing is decided until the finger has moved
 * `decideAfter` (half the touch slop, so the decision comes before the drawer's slop); then a
 * swipe towards opening the drawer (within 45° of it) is left to the drawer, and anything else,
 * or a second finger, is the terminal's. It is asked again on every move until the terminal
 * claims the gesture, so a swipe that turns into a scroll before the drawer moved stays a scroll.
 */
internal object TerminalGestures {

    /** True when the terminal should claim the gesture now (see [TerminalGestures]). */
    fun terminalClaims(dx: Float, dy: Float, pointers: Int, decideAfter: Float, rtl: Boolean): Boolean {
        if (pointers > 1) return true
        if (dx * dx + dy * dy < decideAfter * decideAfter) return false
        // The drawer opens from the start edge: rightward, or leftward in a right-to-left layout.
        val towardsOpen = if (rtl) -dx else dx
        return !(towardsOpen > 0f && towardsOpen >= abs(dy))
    }
}

/**
 * Applies [TerminalGestures] to the `AndroidView` hosting the terminal. This runs in the Initial
 * pass, ahead of the view's own dispatch and of the drawer's Main-pass drag: when the terminal
 * claims a gesture, it asks [view]'s parent not to intercept, so Compose sends that move and the
 * rest of the gesture to the terminal first and marks them consumed, and the drawer's drag never
 * starts. Compose clears the request when the gesture ends.
 */
internal fun Modifier.keepTerminalGestures(rtl: Boolean, view: () -> View?): Modifier =
    pointerInput(rtl) {
        val decideAfter = viewConfiguration.touchSlop / 2
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) break
                val moved = change.position - down.position
                val pointers = event.changes.count { it.pressed }
                if (TerminalGestures.terminalClaims(moved.x, moved.y, pointers, decideAfter, rtl)) {
                    view()?.parent?.requestDisallowInterceptTouchEvent(true)
                    break
                }
            }
        }
    }
