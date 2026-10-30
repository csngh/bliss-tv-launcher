package com.example.projectorlauncher

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

object KeepHome {
    // Never treated as "the stock launcher", so Settings and the installer cannot be hijacked.
    private val PROTECTED = SETTINGS_PACKAGES + listOf(
        "com.android.systemui",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
    )

    fun isProtected(context: Context, pkg: String) = pkg == context.packageName || pkg in PROTECTED

    fun shouldRun(prefs: Prefs) = prefs.enabled && prefs.keepOnTop

    /** Starts or stops the service to match the saved preferences. */
    fun sync(context: Context) {
        val intent = Intent(context, KeepHomeService::class.java)
        try {
            if (shouldRun(Prefs(context))) ContextCompat.startForegroundService(context, intent)
            else context.stopService(intent)
        } catch (e: Exception) {
            android.util.Log.w("KeepHome", "sync failed", e)
        }
    }

    /** Starts the service just to launch the launcher after boot (and keep watching if enabled). */
    fun startForBoot(context: Context) {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, KeepHomeService::class.java).putExtra(KeepHomeService.EXTRA_BOOT_LAUNCH, true),
            )
        } catch (e: Exception) {
            android.util.Log.w("KeepHome", "boot start failed", e)
        }
    }

    fun turnAllOff(context: Context) {
        Prefs(context).enabled = false
        context.stopService(Intent(context, KeepHomeService::class.java))
    }

    fun mainActivityIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    fun canDrawOverlays(context: Context) = Settings.canDrawOverlays(context)

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
