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
import androidx.core.content.ContextCompat
import com.ivarna.fluxlinux.MainActivity
import com.ivarna.fluxlinux.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground keep-alive during base desktop / onboarding install (proot + chroot).
 *
 * Long rootfs extract and package installs can take many minutes; without an FGS
 * Android may kill the process when the user leaves the app or under memory pressure.
 * Uses [ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC] (network/file install work).
 */
class BaseInstallService : Service() {

    override fun onCreate() {
        super.onCreate()
        running.set(true)
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Defensive: only a sticky restart delivers a null intent, and the install
            // runner died with the old process, so there is nothing to keep alive.
            stopInstallForeground()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_STOP -> {
                stopInstallForeground()
                return START_NOT_STICKY
            }
            ACTION_UPDATE -> {
                if (!foregrounded) {
                    // Not yet in FG (race) — promote with this payload
                    enterForeground(
                        intent.getStringExtra(EXTRA_TITLE) ?: "Installing…",
                        intent.getStringExtra(EXTRA_TEXT) ?: "Base desktop setup",
                        intent.getIntExtra(EXTRA_PERCENT, 0)
                    )
                } else {
                    val title = intent.getStringExtra(EXTRA_TITLE) ?: "Installing…"
                    val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                    val percent = intent.getIntExtra(EXTRA_PERCENT, 0)
                    notifyProgress(title, text, percent)
                }
                return START_NOT_STICKY
            }
            else -> {
                // ACTION_START. Not sticky: after process death no runner exists, so a
                // restarted service would only show a false "Installing…" notification.
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Installing…"
                val text = intent.getStringExtra(EXTRA_TEXT) ?: "Base desktop setup"
                val percent = intent.getIntExtra(EXTRA_PERCENT, 0)
                enterForeground(title, text, percent)
                return START_NOT_STICKY
            }
        }
    }

    /**
     * Android 15+ caps `dataSync` foreground services at 6 h per day; the system calls this
     * when the cap is hit and crashes the app unless the service stops itself.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "dataSync FGS time limit reached; stopping install keep-alive")
        stopInstallForeground()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.set(false)
        foregrounded = false
        super.onDestroy()
    }

    private fun enterForeground(title: String, text: String, percent: Int) {
        val notification = buildNotification(title, text, percent)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
            foregrounded = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            try {
                startForeground(NOTIF_ID, notification)
                foregrounded = true
            } catch (e2: Exception) {
                Log.e(TAG, "fallback startForeground failed", e2)
            }
        }
    }

    private fun notifyProgress(title: String, text: String, percent: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(title, text, percent))
    }

    private fun stopInstallForeground() {
        if (foregrounded) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregrounded = false
        }
        stopSelf()
    }

    private fun buildNotification(title: String, text: String, percent: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            NOTIF_ID,
            Intent(this, MainActivity::class.java).apply {
                // No navigation (Page.NONE); onboarding restores its own screen.
                putExtra(LaunchTargets.EXTRA_TARGET_PAGE, LaunchTargets.PAGE_INSTALL)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val clamped = percent.coerceIn(0, 100)
        val body = if (text.isNotBlank()) text else "Keeping install alive…"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_flux)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText("$clamped%")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .setProgress(100, clamped, clamped <= 0 && body.contains("Preparing", ignoreCase = true))
        return builder.build()
    }

    private var foregrounded = false

    companion object {
        private const val TAG = "BaseInstallService"
        const val CHANNEL_ID = "FluxLinuxBaseInstall_v1"
        private const val NOTIF_ID = 4712

        /** The dismissible "finished with a warning" notice; outlives the keep-alive's [NOTIF_ID]. */
        private const val DONE_NOTIF_ID = 4713

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Base desktop install",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description =
                    "Shows while Debian rootfs / XFCE install is running so the system does not kill AkaTerminal"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        /**
         * A run finished, but with a warning (part of the setup didn't finish). The keep-alive
         * notification is removed right after its last update ([stop]), so a warning shown only
         * there would flash by while the app is in the background; this one stays until tapped
         * or dismissed, and the next [start] clears it.
         */
        fun notifyFinishedWithWarning(context: Context, title: String, text: String) {
            val app = context.applicationContext
            runCatching {
                ensureChannel(app)
                val open = PendingIntent.getActivity(
                    app,
                    DONE_NOTIF_ID,
                    Intent(app, MainActivity::class.java).apply {
                        // No navigation (Page.NONE): onboarding's Done page or the app as it was.
                        putExtra(LaunchTargets.EXTRA_TARGET_PAGE, LaunchTargets.PAGE_INSTALL)
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val notification = NotificationCompat.Builder(app, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_flux)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(open)
                    .build()
                val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(DONE_NOTIF_ID, notification)
            }.onFailure { Log.w(TAG, "finished-with-warning notification failed", it) }
        }

        private fun cancelFinishedNotice(context: Context) {
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(DONE_NOTIF_ID)
            }
        }

        const val ACTION_START = "com.ivarna.fluxlinux.BASE_INSTALL_START"
        const val ACTION_UPDATE = "com.ivarna.fluxlinux.BASE_INSTALL_UPDATE"
        const val ACTION_STOP = "com.ivarna.fluxlinux.BASE_INSTALL_STOP"

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_PERCENT = "percent"

        private val running = AtomicBoolean(false)

        /** Set by [start] until [stop]; covers the window before [onCreate] sets [running]. */
        private val startRequested = AtomicBoolean(false)

        fun isRunning(): Boolean = running.get()

        fun start(
            context: Context,
            title: String = "Installing…",
            text: String = "Base desktop setup",
            percent: Int = 0
        ) {
            // A previous run's warning no longer applies once a new run starts.
            cancelFinishedNotice(context)
            val intent = Intent(context, BaseInstallService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_PERCENT, percent)
            }
            ContextCompat.startForegroundService(context, intent)
            startRequested.set(true)
        }

        fun update(context: Context, title: String, text: String, percent: Int) {
            if (!running.get()) return
            val intent = Intent(context, BaseInstallService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
                putExtra(EXTRA_PERCENT, percent)
            }
            // Service already running — plain startService is enough for updates
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "update failed: ${e.message}")
            }
        }

        fun stop(context: Context) {
            // A start() whose service is not created yet must still be stopped: ACTION_STOP is
            // queued behind ACTION_START, so startForeground() runs first (stopping a service
            // started with startForegroundService() before that crashes the app).
            val requested = startRequested.getAndSet(false)
            if (!running.get() && !requested) {
                try {
                    context.stopService(Intent(context, BaseInstallService::class.java))
                } catch (_: Exception) {
                }
                return
            }
            try {
                val intent = Intent(context, BaseInstallService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "stop via action failed: ${e.message}")
                try {
                    context.stopService(Intent(context, BaseInstallService::class.java))
                } catch (_: Exception) {
                }
            }
        }
    }
}
