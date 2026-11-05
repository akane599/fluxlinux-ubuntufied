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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT) {
            AppExit.exitAll(this)
            TerminalWakeLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val count = if (intent?.action == ACTION_TOGGLE_WAKELOCK) {
            // The live count, not the last update's: if a background startForegroundService was
            // refused, sessions run without this service, and the drawer's toggle (app in the
            // foreground) starts it here. No sessions: release and stop.
            val live = SessionRegistry.sessionCount
            if (live > 0) TerminalWakeLock.toggle(this)
            live
        } else {
            intent?.getIntExtra("SESSION_COUNT", 0) ?: 0
        }
        if (count <= 0) {
            TerminalWakeLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
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
        // Not sticky: terminal sessions live in this process and die with it.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        TerminalWakeLock.release()
        super.onDestroy()
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
