package com.ivarna.fluxlinux.core.service

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.ivarna.fluxlinux.core.desktop.DesktopLauncher
import com.ivarna.fluxlinux.core.desktop.DesktopSessionQuery
import com.ivarna.fluxlinux.core.terminal.FluxTerminalSessionManager
import com.ivarna.fluxlinux.core.terminal.TerminalWakeLock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * "Exit" from the terminal notification (Termux parity, plus the desktop): releases the
 * wakelock, closes every terminal session, stops a running or starting desktop and asks the
 * activity to close its task, so nothing of AkaTerminal keeps running in the background.
 * Installs are left alone: they have their own service and notification.
 */
object AppExit {

    /** The side effects, behind a seam so their order and conditions are JVM-tested. */
    internal interface Actions {
        fun releaseWakeLock()
        fun closeTerminals()
        /** The distro whose desktop is running or starting, or null. */
        fun activeDesktopId(): String?
        fun stopDesktop(distroId: String)
        fun finishUi()
    }

    private val _finishRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** MainActivity collects this for as long as it exists and closes its task. */
    val finishRequests: SharedFlow<Unit> = _finishRequests.asSharedFlow()

    fun exitAll(ctx: Context) = run(platformActions(ctx.applicationContext))

    @VisibleForTesting
    internal fun run(actions: Actions) {
        // Each step is independent: one failing must not leave the others undone.
        step("release wakelock") { actions.releaseWakeLock() }
        step("close terminals") { actions.closeTerminals() }
        step("stop desktop") { actions.activeDesktopId()?.let(actions::stopDesktop) }
        step("finish ui") { actions.finishUi() }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "exit: $name failed", e)
        }
    }

    private fun platformActions(app: Context) = object : Actions {
        override fun releaseWakeLock() = TerminalWakeLock.release()
        override fun closeTerminals() = FluxTerminalSessionManager.closeAll(app)
        override fun activeDesktopId(): String? {
            val ui = DesktopLauncher.uiState.value
            return DesktopSessionQuery.current(app, ui)?.distroId
                ?: ui.distroId?.takeIf { DesktopLauncher.isSessionActive() }
        }
        override fun stopDesktop(distroId: String) = DesktopLauncher.stop(app, distroId)
        override fun finishUi() {
            _finishRequests.tryEmit(Unit)
        }
    }

    private const val TAG = "AppExit"
}
