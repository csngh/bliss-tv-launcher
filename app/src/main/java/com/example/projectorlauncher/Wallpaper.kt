package com.example.projectorlauncher

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Wallpapers {
    class Preset(val id: String, val name: String, val colors: List<Color>) {
        val brush get() = Brush.linearGradient(colors)
    }

    val presets = listOf(
        Preset("midnight", "Midnight", listOf(Color(0xFF0E1426), Color(0xFF1B2A55), Color(0xFF0A0F1C))),
        Preset("aurora", "Aurora", listOf(Color(0xFF07121F), Color(0xFF0F6B63), Color(0xFF3B1F5B))),
        Preset("sunset", "Sunset", listOf(Color(0xFF1B1030), Color(0xFF8A2F5E), Color(0xFFE8794F))),
        Preset("ocean", "Ocean", listOf(Color(0xFF031321), Color(0xFF0B5C8F), Color(0xFF5FC8E6))),
        Preset("graphite", "Graphite", listOf(Color(0xFF101114), Color(0xFF2A2D35), Color(0xFF0A0B0D))),
    )
    const val DEFAULT = "preset:midnight"

    private val EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    /** Images the user copies here (adb push or a file manager) show up in the Wallpaper settings. */
    fun dir(context: Context): File? = context.getExternalFilesDir("wallpapers")?.also { it.mkdirs() }

    fun files(context: Context): List<File> =
        dir(context)?.listFiles { f -> f.isFile && f.extension.lowercase() in EXTENSIONS }?.sortedBy { it.name.lowercase() }.orEmpty()

    fun preset(key: String): Preset = presets.firstOrNull { key == "preset:${it.id}" } ?: presets.first()

    /** Decodes at roughly screen size so a 4K photo does not cost 30 MB of heap. */
    fun load(file: File, maxWidth: Int = 1920, lowMem: Boolean = false): ImageBitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            if (lowMem) inPreferredConfig = android.graphics.Bitmap.Config.RGB_565   // half the memory
        })?.asImageBitmap()
    } catch (e: Throwable) {
        null
    }
}

/** Full-screen wallpaper: the chosen image, or the preset gradient underneath (also shown if the image is missing). */
@Composable
fun WallpaperLayer(key: String, modifier: Modifier = Modifier.fillMaxSize()) {
    val context = LocalContext.current
    val resumed = rememberIsResumed()
    val low = remember { LowMem.isLow(context) }
    // The decoded picture (about 8 MB) is released the moment another app covers the launcher.
    val image by produceState<ImageBitmap?>(null, key, resumed) {
        value = if (key.startsWith("file:") && resumed) {
            withContext(Dispatchers.IO) { Wallpapers.dir(context)?.let { Wallpapers.load(File(it, key.removePrefix("file:")), lowMem = low) } }
        } else null
    }
    Box(modifier.background(Wallpapers.preset(key).brush)) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** The Midnight accent: the weighted average of the Midnight gradient, favouring its stronger colours. */
object WallpaperAccent {
    val midnight: Color by lazy {
        var r = 0.0; var g = 0.0; var b = 0.0; var w = 0.0
        val hsv = FloatArray(3)
        for (c in Wallpapers.preset("preset:midnight").colors.map { it.toArgb() }) {
            android.graphics.Color.colorToHSV(c, hsv)
            val weight = (hsv[1] * hsv[1] * 2.0 + 0.02) * (0.25 + hsv[2])
            r += android.graphics.Color.red(c) * weight; g += android.graphics.Color.green(c) * weight; b += android.graphics.Color.blue(c) * weight; w += weight
        }
        Color(android.graphics.Color.rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt()))
    }
}
