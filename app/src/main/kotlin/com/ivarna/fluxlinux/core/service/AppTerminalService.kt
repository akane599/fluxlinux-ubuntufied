package com.ivarna.fluxlinux.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ivarna.fluxlinux.MainActivity
import com.ivarna.fluxlinux.R
import com.ivarna.fluxlinux.core.terminal.SessionRegistry
import com.ivarna.fluxlinux.core.terminal.TerminalWakeLock

/**
 * Foreground service keeping in-app terminal sessions alive while the app is
 * backgrounded. Mirrors termux-lib `AppTerminalService` (specialUse subtype).
 * Its notification carries the "Hold wakelock" toggle ([TerminalWakeLock]), which never
 * outlives the sessions (the lock is released with the last one), and "Exit" ([AppExit]),
 * which closes the sessions, stops the desktop and closes the app.
 */
class AppTerminalService : Service() {

    private val actions = object : TerminalServiceCommands.Actions {
        override fun liveSessionCount(): Int = SessionRegistry.sessionCount
        override fun exitAll() = AppExit.exitAll(this@AppTerminalService)
        override fun toggleWakeLock() = TerminalWakeLock.toggle(this@AppTerminalService)
        override fun releaseWakeLock() = TerminalWakeLock.release()
        override fun stop() = leaveForegroundAndStop()
        override fun showForeground(sessions: Int) = showNotification(sessions)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TerminalServiceCommands.onStartCommand(
            action = intent?.action,
            sessionCountExtra = intent?.getIntExtra(EXTRA_SESSION_COUNT, 0) ?: 0,
            actions = actions,
        )
        // Not sticky: terminal sessions live in this process and die with it.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        TerminalServiceCommands.onDestroy(actions)
        super.onDestroy()
    }

    private fun leaveForegroundAndStop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showNotification(count: Int) {
        val notification = createNotification(count)
        try {
            startAsForeground(notification)
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            try {
                startForeground(NOTIF_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "fallback startForeground failed", e2)
            }
        }
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun createNotification(count: Int): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(LaunchTargets.EXTRA_TARGET_PAGE, LaunchTargets.PAGE_TERMINAL)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, NOTIF_ID, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val held = TerminalWakeLock.held.value
        val toggleWakeLock = PendingIntent.getService(
            this, NOTIF_ID + 1,
            Intent(this, AppTerminalService::class.java).setAction(ACTION_TOGGLE_WAKELOCK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val exit = PendingIntent.getService(
            this, NOTIF_ID + 2,
            Intent(this, AppTerminalService::class.java).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val sessions = resources.getQuantityString(R.plurals.terminal_notification_sessions, count, count)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.terminal_notification_title))
            .setContentText(
                if (held) getString(R.string.terminal_notification_wakelock_held, sessions) else sessions
            )
            // Exit first, then the wakelock toggle (the user's order).
            .addAction(0, getString(R.string.terminal_notification_exit), exit)
            .addAction(
                0,
                getString(if (held) R.string.terminal_wakelock_release else R.string.terminal_wakelock_hold),
                toggleWakeLock
            )
            .setSmallIcon(R.drawable.ic_stat_flux)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.terminal_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.terminal_notification_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "AppTerminalService"
        const val ACTION_TOGGLE_WAKELOCK = "com.ivarna.fluxlinux.action.TOGGLE_WAKELOCK"
        const val ACTION_EXIT = "com.ivarna.fluxlinux.action.EXIT"

        /** The open session count, sent by `SessionRegistry` with every update. */
        private const val EXTRA_SESSION_COUNT = "SESSION_COUNT"

        // New id: IMPORTANCE is immutable once a channel exists on device
        const val CHANNEL_ID = "FluxLinuxAppTerminalServiceChannel_v2"
        private const val NOTIF_ID = 1001

        /** Hold or release the terminal wakelock and update the notification (drawer switch). */
        fun toggleWakeLock(ctx: Context) {
            runCatching {
                ctx.startService(
                    Intent(ctx, AppTerminalService::class.java).setAction(ACTION_TOGGLE_WAKELOCK)
                )
            }.onFailure { Log.e(TAG, "toggleWakeLock failed", it) }
        }
    }
}

/**
 * What [AppTerminalService] does with a start command (its action and `SESSION_COUNT` extra)
 * and on destroy. The side effects sit behind [Actions], so these rules are JVM-tested.
 */
internal object TerminalServiceCommands {

    internal interface Actions {
        /** Sessions open right now (`SessionRegistry`), whatever the last update said. */
        fun liveSessionCount(): Int
        /** Notification Exit ([AppExit]): close the terminals and the desktop, finish the UI. */
        fun exitAll()
        fun toggleWakeLock()
        fun releaseWakeLock()
        /** Leave the foreground and stop the service. */
        fun stop()
        /** Enter or stay in the foreground, the notification showing [sessions]. */
        fun showForeground(sessions: Int)
    }

    fun onStartCommand(action: String?, sessionCountExtra: Int, actions: Actions) {
        if (action == AppTerminalService.ACTION_EXIT) {
            actions.exitAll()
            actions.releaseWakeLock()
            actions.stop()
            return
        }
        val count = if (action == AppTerminalService.ACTION_TOGGLE_WAKELOCK) {
            // The live count, not the last update's: if a background startForegroundService was
            // refused, sessions run without this service, and the drawer's toggle (app in the
            // foreground) starts it here. No sessions: release and stop, never acquire.
            val live = actions.liveSessionCount()
            if (live > 0) actions.toggleWakeLock()
            live
        } else {
            sessionCountExtra
        }
        if (count <= 0) {
            // The wake lock never outlives the sessions.
            actions.releaseWakeLock()
            actions.stop()
        } else {
            actions.showForeground(count)
        }
    }

    /** The wake lock never outlives the service either. */
    fun onDestroy(actions: Actions) = actions.releaseWakeLock()
}
