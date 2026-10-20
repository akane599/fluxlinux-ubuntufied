package com.ivarna.fluxlinux.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ivarna.fluxlinux.MainActivity
import com.ivarna.fluxlinux.R

/**
 * Keep-alive FGS while XFCE / host GUI services run (nativecode BackgroundService parity).
 */
class DesktopSessionService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Defensive: a null intent only comes from a sticky restart after process death,
            // when the desktop died with the old process. Never show a false "active" notice.
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            try {
                startForeground(NOTIF_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "fallback startForeground failed", e2)
            }
        }
        // Not sticky: the guest desktop runs in this process tree and dies with it.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(): Notification {
        // A target page makes a cold-start tap land on Home without counting as a plain
        // launch (which could auto-start the desktop again).
        val open = Intent(this, MainActivity::class.java).apply {
            putExtra(LaunchTargets.EXTRA_TARGET_PAGE, LaunchTargets.PAGE_HOME)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FluxLinux — Desktop Session")
            .setContentText("XFCE / GUI host services are active. Open app to stop.")
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
                "FluxLinux Desktop Session",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows while XFCE / graphical desktop is running"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "DesktopSessionService"
        const val CHANNEL_ID = "FluxLinuxDesktopSession_v1"
        private const val NOTIF_ID = 42
    }
}
