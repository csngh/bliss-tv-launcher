package com.example.projectorlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/** Plain-text report lines; lines starting with "# " are section headers. */
@Suppress("DEPRECATION")
fun collectDebugLines(context: Context): List<String> {
    val pm = context.packageManager
    val lines = mutableListOf<String>()

    lines += "# Device"
    lines += "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    for (pkg in SETTINGS_PACKAGES) {
        lines += "# Package $pkg"
        val info: PackageInfo? = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
            } else {
                pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            }
        } catch (e: Exception) {
            null
        }
        if (info == null) {
            lines += "NOT INSTALLED"
            continue
        }
        val activities = info.activities.orEmpty().sortedBy { it.name }
        lines += "installed, version ${info.versionName}, ${activities.size} activities"
        activities.forEach {
            val flags = (if (it.exported) "exported" else "private") + (if (it.enabled) "" else ", disabled")
            lines += "${it.name.removePrefix("$pkg.")}  [$flags]"
        }
    }

    lines += "# Who handles each action"
    for (button in UTILITY_BUTTONS) {
        val handlers = AppRepository.queryActivities(pm, Intent(button.action))
        lines += "${button.label} (${button.action.substringAfterLast('.')})"
        if (handlers.isEmpty()) lines += "    no handler"
        handlers.forEach { lines += "    " + it.activityInfo.packageName + "/" + it.activityInfo.name }
    }

    lines += "# Other installed packages with \"settings\" in the name"
    val others = pm.getInstalledPackages(0)
        .map { it.packageName }
        .filter { "settings" in it.lowercase() && it !in SETTINGS_PACKAGES }
        .sorted()
    if (others.isEmpty()) lines += "none"
    lines += others
    return lines
}
