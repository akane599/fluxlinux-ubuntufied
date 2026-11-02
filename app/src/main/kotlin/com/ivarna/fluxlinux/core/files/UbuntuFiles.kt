package com.ivarna.fluxlinux.core.files

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import com.ivarna.fluxlinux.BuildConfig
import com.ivarna.fluxlinux.core.chroot.ChrootDetection
import com.ivarna.fluxlinux.core.root.ChrootPaths
import com.ivarna.fluxlinux.core.root.RootShell
import com.ivarna.fluxlinux.core.terminal.TerminalLauncher
import com.ivarna.fluxlinux.core.utils.UbuntuRuntime

/**
 * Entry points for the Ubuntu roots that [UbuntuDocumentsProvider] exposes to Android file
 * managers. The roots follow [UbuntuRuntime.mode]: PRoot serves the app-private rootfs
 * directly, chroot serves [ChrootPaths.UBUNTU_CHROOT_PATH] through root. Callers keep using
 * [ROOT_HOME] / [ROOT_SYSTEM]; [open] maps them to the active mode's root ids.
 */
object UbuntuFiles {
    const val ROOT_HOME = GuestDocumentPaths.ROOT_HOME
    const val ROOT_SYSTEM = GuestDocumentPaths.ROOT_SYSTEM

    private const val TAG = "UbuntuFiles"
    private const val CHROOT_AVAILABILITY_TTL_MS = 15_000L

    /** (available, elapsedRealtime) of the last chroot availability probe. */
    @Volatile
    private var chrootAvailability: Pair<Boolean, Long>? = null

    @Suppress("UNUSED_PARAMETER")
    fun authority(ctx: Context): String = "${BuildConfig.APPLICATION_ID}.documents"

    /** Layout of the active Ubuntu install (decides root ids, rootfs and backend). */
    fun activeLayout(ctx: Context): GuestLayout = GuestLayout.of(UbuntuRuntime.mode(ctx))

    /** The active mode's root id for [rootId] (a home/system root id of either mode). */
    fun activeRootId(ctx: Context, rootId: String): String = activeLayout(ctx).mapRootId(rootId)

    /**
     * Whether the active mode's roots can be served. Chroot: root granted and the chroot
     * installed, cached for a few seconds; on the main thread only the cached answer is used
     * (never a su call).
     */
    fun isAvailable(ctx: Context): Boolean = when (activeLayout(ctx)) {
        GuestLayout.PROOT -> TerminalLauncher.isProotInstalled(ctx, UbuntuRuntime.PROOT_ID)
        GuestLayout.CHROOT -> isChrootAvailable()
    }

    private fun isChrootAvailable(): Boolean {
        val now = SystemClock.elapsedRealtime()
        chrootAvailability?.let { (ok, at) -> if (now - at in 0 until CHROOT_AVAILABILITY_TTL_MS) return ok }
        val main = Looper.getMainLooper()
        if (main != null && main.thread === Thread.currentThread()) return chrootAvailability?.first ?: false
        val ok = runCatching {
            RootShell.isRootAvailable() && ChrootDetection.isInstalled(ChrootPaths.UBUNTU_CHROOT_PATH)
        }.getOrDefault(false)
        chrootAvailability = ok to now
        return ok
    }

    /**
     * Opens the system file manager at [rootId] (mapped to the active mode). Tries the root
     * view, then the root document view, then the tree picker; returns false (no crash) when
     * nothing handles it. No URI grant flags: DocumentsUI already holds MANAGE_DOCUMENTS, and
     * a grant would hand the root to any other app that answers these intents.
     */
    fun open(ctx: Context, rootId: String = ROOT_HOME): Boolean {
        val auth = authority(ctx)
        val id = activeRootId(ctx, rootId)
        val rootDoc = DocumentsContract.buildDocumentUri(auth, GuestDocumentPaths.encode(id, ""))
        val attempts = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(
                DocumentsContract.buildRootUri(auth, id), DocumentsContract.Root.MIME_TYPE_ITEM
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

    /**
     * Re-publishes the roots (call after install/uninstall and after a PRoot/chroot mode
     * switch). Drops the cached chroot/root availability so the next query re-probes.
     */
    fun notifyRootsChanged(ctx: Context) {
        chrootAvailability = null
        SuRootRunner.invalidate()
        ChrootFilesBusyBox.invalidate()
        ctx.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority(ctx)), null)
    }
}
