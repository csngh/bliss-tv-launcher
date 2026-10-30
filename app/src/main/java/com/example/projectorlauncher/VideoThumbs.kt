package com.example.projectorlauncher

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Small video thumbnails (about 130 x 74), loaded on demand into a 2 MB cache. */
object VideoThumbs {
    private val cache = object : LruCache<String, ImageBitmap>(2 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun peek(v: VideoItem): ImageBitmap? = cache.get(v.key)

    fun load(context: Context, v: VideoItem): ImageBitmap? {
        cache.get(v.key)?.let { return it }
        val size = Size(160, 90)
        val bmp: Bitmap? = try {
            if (v.uri.scheme == "file") {
                val path = v.uri.path ?: return null
                if (Build.VERSION.SDK_INT >= 29) android.media.ThumbnailUtils.createVideoThumbnail(File(path), size, null)
                else @Suppress("DEPRECATION") android.media.ThumbnailUtils.createVideoThumbnail(path, android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
            } else if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.loadThumbnail(v.uri, size, null)
            } else null
        } catch (e: Exception) { null }
        return bmp?.asImageBitmap()?.also { cache.put(v.key, it) }
    }
}

@Composable
fun rememberVideoThumb(v: VideoItem): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    var thumb by remember(v.key) { mutableStateOf(VideoThumbs.peek(v)) }
    LaunchedEffect(v.key) { if (thumb == null) thumb = withContext(Dispatchers.IO) { VideoThumbs.load(context, v) } }
    return thumb
}
