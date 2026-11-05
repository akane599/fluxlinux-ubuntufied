package com.ivarna.fluxlinux.ui.terminal

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Toast
import com.ivarna.fluxlinux.R
import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/** What a Paste request did, so the caller can say why nothing happened. */
enum class PasteResult { PASTED, EMPTY, NO_SESSION }

/**
 * Clipboard, link and transcript actions for the embedded [TerminalView].
 *
 * Pasting always goes through [TerminalSession.onPasteTextFromClipboard], i.e. the shared
 * session client in `SessionRegistry`, which feeds `TerminalEmulator.paste` (bracketed paste
 * when the program asked for it; ESC and C1 controls stripped).
 */
object TerminalClipboard {

    /** Android 13+ shows its own "copied" confirmation; an app toast on top would double it. */
    internal fun systemConfirmsCopy(sdk: Int): Boolean = sdk >= Build.VERSION_CODES.TIRAMISU

    /**
     * Clipboard and share intents travel through Binder (about 1 MB per call): keep the newest
     * output, well under it. A full 10 000-row scrollback can be several times larger.
     */
    internal const val SHARE_LIMIT_CHARS = 100_000

    internal fun tailForShare(text: String, limit: Int = SHARE_LIMIT_CHARS): String =
        if (text.length <= limit) text else text.takeLast(limit)

    /** True for Ctrl+Shift+V and a keyboard's dedicated Paste key. */
    internal fun isPasteShortcut(keyCode: Int, ctrl: Boolean, shift: Boolean): Boolean =
        keyCode == KeyEvent.KEYCODE_PASTE || (ctrl && shift && keyCode == KeyEvent.KEYCODE_V)

    private fun clipboard(ctx: Context): ClipboardManager? =
        ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    fun hasText(ctx: Context): Boolean = runCatching {
        val cm = clipboard(ctx) ?: return false
        cm.hasPrimaryClip() && cm.primaryClipDescription?.let {
            it.hasMimeType("text/*") || it.hasMimeType("text/uri-list")
        } != false
    }.getOrDefault(false)

    fun paste(view: TerminalView?): PasteResult {
        val session = view?.currentSession ?: return PasteResult.NO_SESSION
        if (!hasText(view.context)) return PasteResult.EMPTY
        session.onPasteTextFromClipboard()
        return PasteResult.PASTED
    }

    /** [paste] with a short message when there was nothing to paste. */
    fun pasteWithFeedback(view: TerminalView?, ctx: Context) {
        if (paste(view) == PasteResult.EMPTY) {
            Toast.makeText(ctx, R.string.terminal_clipboard_empty, Toast.LENGTH_SHORT).show()
        }
    }

    /** Copies [text]; says so below Android 13, and always says when the copy failed. */
    fun copy(ctx: Context, text: String, @androidx.annotation.StringRes confirmation: Int) {
        val ok = runCatching {
            clipboard(ctx)?.setPrimaryClip(ClipData.newPlainText("terminal", text)) != null
        }.getOrDefault(false)
        when {
            !ok -> Toast.makeText(ctx, R.string.terminal_copy_failed, Toast.LENGTH_SHORT).show()
            !systemConfirmsCopy(Build.VERSION.SDK_INT) ->
                Toast.makeText(ctx, confirmation, Toast.LENGTH_SHORT).show()
        }
    }

    /** The whole scrollback plus screen of [view]'s session, trailing blank lines trimmed. */
    fun transcript(view: TerminalView?): String? =
        runCatching { view?.currentSession?.emulator?.screen?.transcriptText?.trimEnd() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    /** Links in the session's output, newest first; wrapped lines are joined before matching. */
    fun links(view: TerminalView?): List<String> {
        val text = runCatching {
            view?.currentSession?.emulator?.screen?.transcriptTextWithFullLinesJoined
        }.getOrNull() ?: return emptyList()
        return TerminalLinks.findAll(text).asReversed()
    }

    /** The link under a tap, if any (see the [linkAt] overload for the cell rules). */
    fun linkAt(view: TerminalView, event: MotionEvent): String? = runCatching {
        val screen = view.currentSession?.emulator?.screen ?: return null
        val (column, row) = view.getColumnAndRow(event, true).let { it[0] to it[1] }
        linkAt(screen, column, row)
    }.getOrNull()

    /**
     * The link in the word at [column], [row] of [screen]; [row] is relative to the scroll, as
     * TerminalView reports it (negative in the scrollback). The word spans every row of a
     * soft-wrapped line, also rows that have scrolled into the scrollback, so a long URL broken
     * across rows opens whole whichever of its rows is tapped.
     */
    internal fun linkAt(screen: TerminalBuffer, column: Int, row: Int): String? =
        TerminalLinks.urlIn(screen.getWordAtLocation(column, row))

    /** Opens [url] in the default browser; false (with a message) when nothing can open it. */
    fun openLink(ctx: Context, url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TerminalLinks.toUri(url)))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.terminal_link_no_app, Toast.LENGTH_SHORT).show()
            false
        } catch (_: SecurityException) {
            Toast.makeText(ctx, R.string.terminal_link_no_app, Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun share(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, tailForShare(text))
        val chooser = Intent.createChooser(send, ctx.getString(R.string.terminal_share_title))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(chooser) }
    }

    /**
     * The menu behind the selection toolbar's "More…" (TerminalView calls showContextMenu()
     * after ending the selection). Without a listener that button did nothing.
     */
    fun installContextMenu(view: TerminalView, onShowLinks: () -> Unit) {
        view.setOnCreateContextMenuListener { menu, v, _ ->
            val tv = v as TerminalView
            val ctx = tv.context
            menu.add(R.string.terminal_menu_paste).apply {
                isEnabled = hasText(ctx)
                setOnMenuItemClickListener { pasteWithFeedback(tv, ctx); true }
            }
            menu.add(R.string.terminal_menu_links).setOnMenuItemClickListener {
                onShowLinks()
                true
            }
            val output = transcript(tv)
            menu.add(R.string.terminal_menu_copy_all).apply {
                isEnabled = output != null
                setOnMenuItemClickListener {
                    output?.let { copy(ctx, tailForShare(it), R.string.terminal_output_copied) }
                    true
                }
            }
            menu.add(R.string.terminal_menu_share).apply {
                isEnabled = output != null
                setOnMenuItemClickListener {
                    output?.let { share(ctx, it) }
                    true
                }
            }
        }
    }
}
