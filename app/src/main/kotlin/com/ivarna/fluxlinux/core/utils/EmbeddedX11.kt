package com.ivarna.fluxlinux.core.utils

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Launcher for the Termux:X11 clone embedded directly in this app.
 * X11 renders in-process (same package) — no external Termux:X11 APK needed.
 */
object EmbeddedX11 {

    const val X11_VERSION = "1.03.01"
    private const val TAG = "EmbeddedX11"

    /** Open the X11 preferences activity (in-app). */
    fun launchPreferences(context: Context): Boolean =
        launch(context, com.termux.x11.LoriePreferences::class.java)

    private fun launch(context: Context, cls: Class<*>): Boolean = try {
        val intent = Intent(context, cls).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.e(TAG, "Failed to launch ${cls.name}", e)
        false
    }
}
