package com.ivarna.fluxlinux.core.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.util.Log
import com.ivarna.fluxlinux.BuildConfig
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher

/** Entry points for the Ubuntu roots that [UbuntuDocumentsProvider] exposes to Android file managers. */
object UbuntuFiles {
    const val ROOT_HOME = GuestDocumentPaths.ROOT_HOME
    const val ROOT_SYSTEM = GuestDocumentPaths.ROOT_SYSTEM

    private const val TAG = "UbuntuFiles"
    private const val DISTRO = "ubuntu"

    @Suppress("UNUSED_PARAMETER")
    fun authority(ctx: Context): String = "${BuildConfig.APPLICATION_ID}.documents"

    fun isAvailable(ctx: Context): Boolean = TerminalLauncher.isProotInstalled(ctx, DISTRO)

    /**
     * Opens the system file manager at [rootId]. Tries the root view, then the root
     * document view, then the tree picker; returns false (no crash) when nothing handles it.
     * No URI grant flags: DocumentsUI already holds MANAGE_DOCUMENTS, and a grant would hand
     * the root to any other app that answers these intents.
     */
    fun open(ctx: Context, rootId: String = ROOT_HOME): Boolean {
        val auth = authority(ctx)
        val rootDoc = DocumentsContract.buildDocumentUri(auth, GuestDocumentPaths.encode(rootId, ""))
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(
                DocumentsContract.buildRootUri(auth, rootId), DocumentsContract.Root.MIME_TYPE_ITEM
            ),
            Intent(Intent.ACTION_VIEW).setDataAndType(rootDoc, DocumentsContract.Document.MIME_TYPE_DIR),
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).putExtra(DocumentsContract.EXTRA_INITIAL_URI, rootDoc),
        )
        return attempts.any { intent ->
            try {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No handler for ${intent.action}", e)
                false
            } catch (e: SecurityException) {
                Log.w(TAG, "Refused ${intent.action}", e)
                false
            }
        }
    }

    fun notifyRootsChanged(ctx: Context) {
        ctx.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority(ctx)), null)
    }
}
