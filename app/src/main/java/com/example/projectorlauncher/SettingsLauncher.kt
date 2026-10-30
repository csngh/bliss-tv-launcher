package com.example.projectorlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

const val PKG_SETTINGS = "com.android.settings"
const val PKG_TV_SETTINGS = "com.android.tv.settings"
val SETTINGS_PACKAGES = listOf(PKG_SETTINGS, PKG_TV_SETTINGS)

class UtilityButton(
    val label: String,
    val action: String,
    /** Best-guess activities per vendor package; the Debug screen shows what really exists. */
    val components: List<ComponentName>,
    /** If no settings page exists, launch an installed app whose label matches (e.g. a Miracast app). */
    val appHint: Regex? = null,
)

private fun s(cls: String) = ComponentName(PKG_SETTINGS, cls)
private fun tv(cls: String) = ComponentName(PKG_TV_SETTINGS, cls)

val UTILITY_BUTTONS = listOf(
    UtilityButton(
        "Settings", Settings.ACTION_SETTINGS,
        listOf(s("com.android.settings.Settings"), tv("com.android.tv.settings.MainSettings")),
    ),
    UtilityButton(
        "Developer Options", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
        listOf(
            s("com.android.settings.Settings\$DevelopmentSettingsDashboardActivity"),
            s("com.android.settings.DevelopmentSettings"),
            tv("com.android.tv.settings.system.development.DevelopmentActivity"),
        ),
    ),
    UtilityButton(
        "About Device", Settings.ACTION_DEVICE_INFO_SETTINGS,
        listOf(
            s("com.android.settings.Settings\$MyDeviceInfoActivity"),
            s("com.android.settings.Settings\$DeviceInfoSettingsActivity"),
            tv("com.android.tv.settings.about.AboutActivity"),
        ),
    ),
    UtilityButton(
        "Wi-Fi Settings", Settings.ACTION_WIFI_SETTINGS,
        listOf(
            s("com.android.settings.Settings\$WifiSettingsActivity"),
            tv("com.android.tv.settings.connectivity.NetworkActivity"),
        ),
    ),
    UtilityButton(
        "Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS,
        listOf(
            s("com.android.settings.Settings\$BluetoothSettingsActivity"),
            tv("com.android.tv.settings.accessories.BluetoothAccessoryActivity"),
            tv("com.android.tv.settings.accessories.AddAccessoryActivity"),
        ),
    ),
    UtilityButton(
        "Screencast", Settings.ACTION_CAST_SETTINGS,
        listOf(
            s("com.android.settings.Settings\$WifiDisplaySettingsActivity"),
            s("com.android.settings.Settings\$CastSettingsActivity"),
        ),
        appHint = Regex("(?i)\\b(miracast|cast|mirroring|mirror|wireless display|screen ?mirror|screen ?share|airplay|ezcast|wifi display)\\b"),
    ),
    UtilityButton(
        "Install Unknown Apps", Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        listOf(
            s("com.android.settings.Settings\$ManageExternalSourcesActivity"),
            s("com.android.settings.Settings\$ManageAppExternalSourcesActivity"),
            tv("com.android.tv.settings.device.apps.AppsActivity"),
        ),
    ),
)

sealed interface LaunchResult {
    /** [via] describes which attempt worked. */
    data class Opened(val via: String, val skipped: List<String>) : LaunchResult
    data class Failed(val errors: List<String>) : LaunchResult
}

object SettingsLauncher {
    fun isInstalled(context: Context, pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Order: the standard action (Android picks the handler, normally the TV settings app), then the TV
     * settings package, then com.android.settings. Packages that are not installed are skipped silently.
     * Every attempt is wrapped in try/catch and failures are collected.
     */
    fun launch(context: Context, button: UtilityButton): LaunchResult {
        val packages = listOf(PKG_TV_SETTINGS, PKG_SETTINGS).filter { isInstalled(context, it) }
        val attempts = buildList<Pair<String, Intent>> {
            // A target chosen in Launcher settings always goes first; everything below stays as the fallback.
            ComponentName.unflattenFromString(Prefs(context).target(button.label))?.let {
                add("chosen activity ${it.flattenToShortString()}" to Intent().setComponent(it))
            }
            if (button.appHint != null) {
                // No Cast settings page exists on most TVs, so an app comes first: the chosen one, else a name match.
                chosenApp(context, Prefs(context).screencastPackage)?.let { add(it) }
                addAll(findAppsByLabel(context, button.appHint))
            }
            add("action ${button.action}" to Intent(button.action))
            packages.forEach { pkg ->
                add("action in $pkg" to Intent(button.action).setPackage(pkg))
                button.components.filter { it.packageName == pkg }.forEach { c ->
                    add(c.flattenToShortString() to Intent().setComponent(c))
                }
            }
            packages.forEach { pkg ->
                context.packageManager.getLaunchIntentForPackage(pkg)?.let { add("launch intent of $pkg" to it) }
            }
            add("generic ${Settings.ACTION_SETTINGS}" to Intent(Settings.ACTION_SETTINGS))
        }
        return launchAttempts(context, attempts)
    }

    /**
     * Accepts "package/class", "package/.relative", or a bare class name such as
     * com.htc.luminaos.activity.MainSettingActivity: then the owning package is the longest
     * dotted prefix of it that is installed (com.htc.luminaos).
     */
    fun resolveComponent(context: Context, text: String): ComponentName? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if ('/' in t) return ComponentName.unflattenFromString(t)
        var pkg = t.substringBeforeLast('.', "")
        while (pkg.isNotEmpty()) {
            if (isInstalled(context, pkg)) return ComponentName(pkg, t)
            pkg = pkg.substringBeforeLast('.', "")
        }
        return null
    }

    private fun chosenApp(context: Context, pkg: String): Pair<String, Intent>? {
        if (pkg.isEmpty() || !isInstalled(context, pkg)) return null
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: return null
        return "chosen app $pkg" to intent
    }

    /** Label of the app the Screencast button would open on Automatic, or null if nothing matches. */
    fun autoScreencastLabel(context: Context): String? =
        UTILITY_BUTTONS.firstOrNull { it.appHint != null }?.appHint
            ?.let { findAppsByLabel(context, it).firstOrNull()?.first?.removePrefix("app ")?.trim('"') }

    private fun findAppsByLabel(context: Context, pattern: Regex): List<Pair<String, Intent>> {
        val pm = context.packageManager
        return listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
            .flatMap { AppRepository.queryActivities(pm, Intent(Intent.ACTION_MAIN).addCategory(it)) }
            .filter { it.activityInfo.packageName != context.packageName && pattern.containsMatchIn(it.loadLabel(pm)) }
            .distinctBy { it.activityInfo.packageName }
            .map {
                val c = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                "app \"${it.loadLabel(pm)}\"" to Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(c)
            }
    }

    /** Runs [attempts] in order, each in its own try/catch, stopping at the first that starts. */
    fun launchAttempts(context: Context, attempts: List<Pair<String, Intent>>): LaunchResult {
        val errors = mutableListOf<String>()
        for ((name, intent) in attempts) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return LaunchResult.Opened(name, errors)
            } catch (e: Exception) {
                errors += "$name -> ${e.javaClass.simpleName}: ${e.message?.lineSequence()?.firstOrNull()}"
            }
        }
        return LaunchResult.Failed(errors)
    }

    /**
     * Opens the system page for one app, where it can be stopped, uninstalled, cleared and so on. Tries the standard
     * action, then the same action pinned to each settings package, then the TV settings app's own screen.
     */
    fun openAppInfo(context: Context, app: AppEntry): LaunchResult {
        val uri = android.net.Uri.parse("package:${app.packageName}")
        val attempts = buildList<Pair<String, Intent>> {
            add("app info" to Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            listOf(PKG_TV_SETTINGS, PKG_SETTINGS).filter { isInstalled(context, it) }.forEach { pkg ->
                add("app info in $pkg" to Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri).setPackage(pkg))
            }
            add("TV app management" to Intent().setComponent(ComponentName(PKG_TV_SETTINGS, "com.android.tv.settings.device.apps.AppManagementActivity")).setData(uri)
                .putExtra("packageName", app.packageName))
            add("Settings" to Intent(Settings.ACTION_SETTINGS))
        }
        return launchAttempts(context, attempts)
    }

    fun launchApp(context: Context, app: AppEntry): String? = try {
        context.startActivity(app.launchIntent)
        null
    } catch (e: Exception) {
        "Unable to open ${app.label}: ${e.javaClass.simpleName}: ${e.message}"
    }
}
