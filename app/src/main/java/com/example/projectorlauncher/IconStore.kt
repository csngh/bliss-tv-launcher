package com.example.projectorlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** True on small-RAM devices (about 1.5 GB or less), where the launcher trims everything it can. */
object LowMem {
    @Volatile private var cached: Boolean? = null
    fun isLow(context: Context): Boolean = cached ?: run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        (am.isLowRamDevice || mi.totalMem < 1_600_000_000L || Prefs(context).forceLowMem).also { cached = it }
    }
}

/**
 * Icons are no longer decoded for every installed app up front (100 apps at 160 px was about 10 MB).
 * They load on demand into a small LRU cache, and the dock's icons are also kept as small PNG files so a
 * cold start can draw the dock without asking PackageManager for anything.
 */
object IconStore {
    const val SIZE_PX = 112
    private val cache = object : LruCache<String, ImageBitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun peek(pkg: String): ImageBitmap? = cache.get(pkg)

    fun put(pkg: String, bmp: ImageBitmap) { cache.put(pkg, bmp) }

    /** Decodes the real icon from PackageManager. Call off the main thread. */
    fun load(context: Context, app: AppEntry): ImageBitmap? {
        cache.get(app.packageName)?.let { return it }
        return try {
            val d = context.packageManager.getActivityIcon(app.launchIntent.component ?: return null)
            d.toBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888).asImageBitmap().also { cache.put(app.packageName, it) }
        } catch (e: Exception) { null }
    }

    fun trim() = cache.evictAll()
}

/** Icon for a tile: from memory, else from the dock's disk copy, else decoded off the main thread. */
@Composable
fun rememberAppIcon(app: AppEntry): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    var icon by remember(app.packageName) { mutableStateOf(IconStore.peek(app.packageName) ?: DockCache.icon(context, app.packageName)) }
    LaunchedEffect(app.packageName) {
        if (icon == null) icon = withContext(Dispatchers.IO) { IconStore.load(context, app) }
    }
    return icon
}

/**
 * The pinned apps (label, component, icon) saved to disk. After the system kills the launcher, which happens
 * a lot on 1 GB devices, the dock is rebuilt from these few small files in milliseconds.
 */
object DockCache {
    private fun dir(context: Context) = File(context.filesDir, "dock").also { it.mkdirs() }
    private fun index(context: Context) = File(dir(context), "index.txt")
    private fun png(context: Context, pkg: String) = File(dir(context), "$pkg.png")
    private val memIcons = HashMap<String, ImageBitmap>()

    fun icon(context: Context, pkg: String): ImageBitmap? = memIcons[pkg] ?: try {
        BitmapFactory.decodeFile(png(context, pkg).path)?.asImageBitmap()?.also { memIcons[pkg] = it }
    } catch (e: Exception) { null }

    /** The saved dock in the user's order, limited to [pinned]. Quick: a few lines of text and tiny PNGs. */
    fun read(context: Context, pinned: List<String>): List<AppEntry> = try {
        val rows = index(context).readLines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 3) null else Triple(p[0], p[1], p[2])
        }.associateBy { it.first }
        pinned.mapNotNull { pkg ->
            val (_, label, comp) = rows[pkg] ?: return@mapNotNull null
            val c = ComponentName.unflattenFromString(comp) ?: return@mapNotNull null
            AppEntry(pkg, label, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(c)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
    } catch (e: Exception) { emptyList() }

    /** Writes the index and any missing icon files for the pinned apps. Call off the main thread. */
    fun save(context: Context, apps: List<AppEntry>) {
        try {
            index(context).writeText(apps.joinToString("\n") { "${it.packageName}\t${it.label}\t${it.launchIntent.component?.flattenToString()}" })
            apps.forEach { app ->
                val f = png(context, app.packageName)
                val bmp = IconStore.load(context, app) ?: return@forEach
                memIcons[app.packageName] = bmp
                java.io.FileOutputStream(f).use { bmp.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            // forget files for apps no longer pinned
            dir(context).listFiles { x -> x.extension == "png" && x.nameWithoutExtension !in apps.map { it.packageName } }?.forEach { it.delete() }
        } catch (e: Exception) { android.util.Log.w("DockCache", "save failed", e) }
    }
}

/** True while this screen is the visible, resumed one. Loops and heavy images key off it. */
@Composable
fun rememberIsResumed(): Boolean {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) }
    androidx.compose.runtime.DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumed = true
            if (e == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) resumed = false
        }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    return resumed
}

/**
 * The full app list (package, label, component) saved as one small text file, no icons. After the system kills the
 * launcher, All apps is drawn from this at once instead of showing only the dock apps and then filling in.
 */
object AppListCache {
    private fun file(context: Context) = File(context.filesDir, "apps.txt")

    fun read(context: Context): List<AppEntry> = try {
        file(context).readLines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size < 4) return@mapNotNull null
            val c = ComponentName.unflattenFromString(p[2]) ?: return@mapNotNull null
            AppEntry(p[0], p[1], Intent(Intent.ACTION_MAIN).addCategory(p[3]).setComponent(c)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        }
    } catch (e: Exception) { emptyList() }

    fun save(context: Context, apps: List<AppEntry>) {
        try {
            val tmp = File(context.filesDir, "apps.tmp")
            tmp.writeText(apps.joinToString("\n") { a ->
                "${a.packageName}\t${a.label}\t${a.launchIntent.component?.flattenToString()}\t${a.launchIntent.categories?.firstOrNull() ?: Intent.CATEGORY_LAUNCHER}"
            })
            tmp.renameTo(file(context))
        } catch (e: Exception) { android.util.Log.w("AppListCache", "save failed", e) }
    }
}
