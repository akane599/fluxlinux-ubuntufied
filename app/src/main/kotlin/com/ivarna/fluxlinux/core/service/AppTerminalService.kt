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
 * outlives the sessions: the lock is released with the last one.
 */
class AppTerminalService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        val sessions = if (count == 1) "1 terminal session running" else "$count terminal sessions running"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FluxLinux — Terminal")
            .setContentText(
                if (held) getString(R.string.terminal_notification_wakelock_held, sessions) else sessions
            )
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
                "FluxLinux Terminal Sessions",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows while in-app terminal sessions stay alive in the background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "AppTerminalService"
        const val ACTION_TOGGLE_WAKELOCK = "com.ivarna.fluxlinux.action.TOGGLE_WAKELOCK"

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
