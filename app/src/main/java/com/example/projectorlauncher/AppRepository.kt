package com.example.projectorlauncher

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class AppEntry(
    val packageName: String,
    val label: String,
    val launchIntent: Intent,
)

object AppRepository {
    /** Last full list, kept for the life of the process so the screen never starts empty. */
    @Volatile var cache: List<AppEntry> = emptyList()

    /**
     * Every installed app that has a leanback or regular launcher activity, sorted by label. No icons are
     * decoded here (they load on demand through IconStore), so this is cheap even with hundreds of apps.
     */
    fun load(context: Context, only: Set<String>? = null): List<AppEntry> {
        val pm = context.packageManager
        val byPackage = LinkedHashMap<String, Pair<ResolveInfo, String>>()
        // Leanback first so TV-specific entry points win over the phone launcher activity.
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in queryActivities(pm, query)) {
                byPackage.putIfAbsent(info.activityInfo.packageName, info to category)
            }
        }
        byPackage.remove(context.packageName)
        if (only != null) byPackage.keys.retainAll(only)

        return byPackage.values.map { (info, category) ->
            val component = ComponentName(info.activityInfo.packageName, info.activityInfo.name)
            AppEntry(
                packageName = component.packageName,
                label = info.loadLabel(pm).toString(),
                launchIntent = Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setComponent(component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        }.sortedBy { it.label.lowercase() }
    }

    @Suppress("DEPRECATION")
    fun queryActivities(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(intent, 0)
        }
}

/** App list that reloads whenever a package is installed, removed, or updated. */
@Composable
fun rememberInstalledApps(): State<List<AppEntry>> {
    val context = LocalContext.current.applicationContext
    // Warm start: the in-memory list. Cold start: the dock read straight from its disk copy, no PackageManager.
    val apps = remember { mutableStateOf(AppRepository.cache.ifEmpty { AppListCache.read(context).ifEmpty { DockCache.read(context, Prefs(context).dockApps) } }) }
    var refresh by remember { mutableIntStateOf(0) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                refresh++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    LaunchedEffect(refresh) {
        // Debounce: an update fires REMOVED + ADDED + REPLACED in quick succession.
        if (refresh > 0) delay(400)
        val prefs = Prefs(context)
        val all = withContext(Dispatchers.IO) { AppRepository.load(context) }
        AppRepository.cache = all
        apps.value = all
        withContext(Dispatchers.IO) { AppListCache.save(context, all) }
        // Refresh the on-disk copy of the dock (cheap: only the pinned apps), so the next cold start is instant.
        withContext(Dispatchers.IO) {
            val byPkg = all.associateBy { it.packageName }
            DockCache.save(context, prefs.dockApps.mapNotNull { byPkg[it] })
        }
    }
    return apps
}
