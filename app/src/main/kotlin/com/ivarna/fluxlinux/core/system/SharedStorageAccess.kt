package com.ivarna.fluxlinux.core.system

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * Shared-storage access. Android 11+: all-files access (MANAGE_EXTERNAL_STORAGE), declared
 * only by the ivarna flavor. Android 8-10: legacy READ/WRITE_EXTERNAL_STORAGE (main manifest).
 */
object SharedStorageAccess {
    /** True when this build can obtain shared-storage access on this device. */
    fun isSupported(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || declared(context)

    /** API<30: legacy READ+WRITE granted; API≥30: all-files access granted. */
    fun granted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            legacyPermissions().all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        } else {
            Environment.isExternalStorageManager()
        }

    /** Runtime permissions to request on API<30; empty on API≥30 (use [openSettings]). */
    fun legacyPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        } else {
            emptyArray()
        }

    fun declared(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && runCatching {
            context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_PERMISSIONS
            ).requestedPermissions?.contains(Manifest.permission.MANAGE_EXTERNAL_STORAGE) == true
        }.getOrDefault(false)

    fun granted(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun openSettings(context: Context) {
        val pkg = Uri.parse("package:${context.packageName}")
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg))
        }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }
}
