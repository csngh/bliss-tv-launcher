package com.example.projectorlauncher

import android.Manifest
import android.content.ContentUris
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

class VideoItem(
    val name: String,
    val uri: Uri,
    val durationMs: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val fps: Float = 0f,
) {
    /** Landscape only. */
    val landscape: Boolean get() = width > height && height > 0
    /** Same shape as the screen, so it fills it with no stretching, cropping or black bars. */
    fun fitsScreen(screenAspect: Float): Boolean =
        landscape && Math.abs(width.toFloat() / height - screenAspect) / screenAspect <= VideoLibrary.ASPECT_TOLERANCE
    val is120: Boolean get() = fps >= 119f
    /** More pixels than the screen has: heavy to decode, and a small device can run out of memory. */
    fun tooLarge(screenW: Int, screenH: Int): Boolean = width.toLong() * height > screenW.toLong() * screenH * 1.05
    val key: String get() = uri.toString()
    val tooLong: Boolean get() = durationMs > VideoLibrary.MAX_MS + VideoLibrary.GRACE_MS
    val unreadable: Boolean get() = durationMs <= 0
}

/**
 * Finds wallpaper videos. A folder key is "" for the app's own folder (no permission needed) or
 * "media:<absolute path>" for a shared-storage folder listed through MediaStore, which needs the
 * video-read permission. The system folder picker is a stub on Android TV, so we list folders ourselves.
 */
object VideoLibrary {
    const val MAX_MS = 30_000L
    const val GRACE_MS = 600L
    const val ASPECT_TOLERANCE = 0.02f
    const val APP_FOLDER = ""
    private const val MEDIA = "media:"
    private val EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "3gp")

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Real screen size in pixels (landscape order). */
    fun screenSize(context: Context): Pair<Int, Int> {
        val m = context.resources.displayMetrics
        return maxOf(m.widthPixels, m.heightPixels) to minOf(m.widthPixels, m.heightPixels)
    }

    fun screenAspect(context: Context): Float = screenSize(context).let { it.first.toFloat() / it.second }

    class Probe(val width: Int, val height: Int, val fps: Float)

    /** Reads the true (rotation-corrected) size and the frame rate from the file itself. */
    private val probeCache = java.util.concurrent.ConcurrentHashMap<String, Probe>()

    /** Cached by file and size, so reopening a folder of 100+ clips does not read every file again. */
    fun probe(context: Context, uri: Uri, fallbackW: Int, fallbackH: Int, durationMs: Long, sizeBytes: Long = -1): Probe {
        val key = "$uri#$sizeBytes"
        probeCache[key]?.let { return it }
        return probeUncached(context, uri, fallbackW, fallbackH, durationMs).also { if (it.fps > 0f || it.width > 0) probeCache[key] = it }
    }

    private fun probeUncached(context: Context, uri: Uri, fallbackW: Int, fallbackH: Int, durationMs: Long): Probe {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            fun meta(k: Int) = r.extractMetadata(k)?.toLongOrNull() ?: 0L
            var w = meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt().takeIf { it > 0 } ?: fallbackW
            var h = meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt().takeIf { it > 0 } ?: fallbackH
            val rot = meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt()
            if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
            val frames = if (Build.VERSION.SDK_INT >= 28) meta(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT) else 0L
            val dur = if (durationMs > 0) durationMs else meta(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
            val fps = if (frames > 0 && dur > 0) frames * 1000f / dur
            else r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull() ?: 0f
            Probe(w, h, fps)
        } catch (e: Exception) {
            Probe(fallbackW, fallbackH, 0f)
        } finally {
            r.release()
        }
    }

    fun appDir(context: Context): File? = context.getExternalFilesDir("videos")?.also { it.mkdirs() }

    fun folderLabel(context: Context, folder: String) =
        if (folder.startsWith(MEDIA)) folder.removePrefix(MEDIA) else appDir(context)?.path ?: "(app folder unavailable)"

    /**
     * Turns the folder a file manager returned (a document tree such as "primary:Movies/Wallpapers" or
     * "1A2B-3C4D:Clips") into a real path. Null when it is not shared storage on this device.
     */
    private const val STUBS = "com.android.tv.frameworkpackagestubs"

    /** A real handler for [intent]: never the empty placeholder some TV builds register for file picking. */
    @Suppress("DEPRECATION")
    fun realHandlers(context: Context, intent: Intent): List<android.content.pm.ResolveInfo> =
        context.packageManager.queryIntentActivities(intent, 0).filter { it.activityInfo.packageName != STUBS }

    /** True when the device has a real folder picker (the TV image of Android ships only an empty stub). */
    fun hasFolderPicker(context: Context): Boolean {
        return realHandlers(context, Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)).isNotEmpty()
    }

    /** The folder that holds a file the file manager returned (any of the usual kinds of address). Null when unknown. */
    @Suppress("DEPRECATION")
    fun fileToFolder(context: Context, uri: Uri): String? = try {
        when {
            uri.scheme == "file" -> uri.path?.let { File(it).parent }
            android.provider.DocumentsContract.isDocumentUri(context, uri) && uri.authority == "com.android.externalstorage.documents" -> {
                val id = android.provider.DocumentsContract.getDocumentId(uri)
                val base = if (id.substringBefore(':') == "primary") android.os.Environment.getExternalStorageDirectory().path else "/storage/${id.substringBefore(':')}"
                File(base, id.substringAfter(':', "")).parent
            }
            else -> context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { File(it).parent } else null
            }
        }
    } catch (e: Exception) { null }

    fun treeToPath(uri: Uri): String? = try {
        val id = android.provider.DocumentsContract.getTreeDocumentId(uri)
        val volume = id.substringBefore(':')
        val rel = id.substringAfter(':', "")
        val base = if (volume == "primary") android.os.Environment.getExternalStorageDirectory().path else "/storage/$volume"
        if (rel.isEmpty()) base else "$base/$rel"
    } catch (e: Exception) { null }

    fun scan(context: Context, folder: String): List<VideoItem> =
        if (folder.startsWith(MEDIA)) scanMediaStore(context, folder.removePrefix(MEDIA)) else scanAppFolder(context)

    @Suppress("DEPRECATION")
    private fun scanMediaStore(context: Context, dir: String): List<VideoItem> {
        val cols = arrayOf(
            MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT,
        )
        val items = mutableListOf<VideoItem>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cols,
            // _ and % are wildcards in LIKE, so a folder named "My_Videos" must have them escaped.
            "${MediaStore.Video.Media.DATA} LIKE ? ESCAPE '\\' AND ${MediaStore.Video.Media.DATA} NOT LIKE ? ESCAPE '\\'",
            dir.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").let { arrayOf("$it/%", "$it/%/%") },
            "${MediaStore.Video.Media.DISPLAY_NAME} COLLATE NOCASE",
        )?.use { c ->
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                val pr = probe(context, uri, c.getInt(4), c.getInt(5), c.getLong(2), c.getLong(3))
                items += VideoItem(
                    name = c.getString(1) ?: "video", uri = uri,
                    durationMs = c.getLong(2), sizeBytes = c.getLong(3), width = pr.width, height = pr.height, fps = pr.fps,
                )
            }
        }
        return items
    }

    private fun scanAppFolder(context: Context): List<VideoItem> {
        val files = appDir(context)?.listFiles { f -> f.isFile && f.extension.lowercase() in EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }.orEmpty()
        return files.map { f ->
            val uri = Uri.fromFile(f)
            val r = android.media.MediaMetadataRetriever()
            val dur = try {
                r.setDataSource(f.path)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } catch (e: Exception) { 0L } finally { r.release() }
            val pr = probe(context, uri, 0, 0, dur, f.length())
            VideoItem(f.name, uri, dur, f.length(), pr.width, pr.height, pr.fps)
        }
    }
}

/** Opens the file manager to pick a video, skipping empty placeholder handlers. */
class PickVideoFile : androidx.activity.result.contract.ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent {
        val base = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*")
        val real = VideoLibrary.realHandlers(context, base)
        return when {
            real.size == 1 -> Intent(base).setClassName(real[0].activityInfo.packageName, real[0].activityInfo.name)
            real.size > 1 -> Intent.createChooser(base, "Choose a file manager")
                .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName("com.android.tv.frameworkpackagestubs", "com.android.tv.frameworkpackagestubs.Stubs\$DocumentsStub")))
            else -> base
        }
    }
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}
