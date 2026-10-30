package com.example.projectorlauncher

import android.content.Context
import android.content.SharedPreferences

/**
 * Stored in device-protected storage so the boot receiver can read it on LOCKED_BOOT_COMPLETED,
 * before the user storage is unlocked.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.createDeviceProtectedStorageContext().getSharedPreferences("launcher_prefs", Context.MODE_PRIVATE)

    init {
        // Colour profile and screen filter were removed; drop any values an older version saved.
        val stale = sp.all.keys.filter { it.startsWith("color_") || it.startsWith("filter_") }
        if (stale.isNotEmpty()) sp.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    /** Master switch. When false, no boot launch and no keep-home service, whatever the other toggles say. */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var autoStartOnBoot: Boolean
        get() = sp.getBoolean("auto_start", true)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    var keepOnTop: Boolean
        get() = sp.getBoolean("keep_on_top", false)
        set(v) = sp.edit().putBoolean("keep_on_top", v).apply()

    /** Packages shown in the home dock, in display order. */
    var dockApps: List<String>
        get() = (sp.getString("dock_apps", "") ?: "").split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("dock_apps", v.joinToString(",")).apply()

    /** Per-button activity override, "package/class" (empty = automatic). Keyed by the button label. */
    fun target(label: String): String = sp.getString("target_$label", "") ?: ""
    fun setTarget(label: String, component: String) = sp.edit().putString("target_$label", component).apply()

    /** Size of the home dock: 0.8 small, 1 normal, 1.25 large, 1.5 extra large. */
    var dockScale: Float
        get() = sp.getFloat("dock_scale", 1f)
        set(v) = sp.edit().putFloat("dock_scale", v).apply()

    /** Size of the top bar (clock, date, readout and quick buttons): 0.8 small, 1 normal, 1.25 large, 1.5 extra large. */
    var topScale: Float
        get() = sp.getFloat("top_scale", 1f)
        set(v) = sp.edit().putFloat("top_scale", v).apply()

    /** Keep a still picture behind All apps even when a video wallpaper plays on the home screen. */
    var drawerStatic: Boolean
        get() = sp.getBoolean("drawer_static", false)
        set(v) = sp.edit().putBoolean("drawer_static", v).apply()

    /** Packages hidden from the All apps list (they stay installed, and can still be pinned to the dock). */
    var hiddenApps: Set<String>
        get() = (sp.getString("hidden_apps", "") ?: "").split(',').filter { it.isNotBlank() }.toSet()
        set(v) = sp.edit().putString("hidden_apps", v.joinToString(",")).apply()

    /** Package the Screencast button opens; empty means auto-detect by app name. */
    var screencastPackage: String
        get() = sp.getString("screencast_package", "") ?: ""
        set(v) = sp.edit().putString("screencast_package", v).apply()

    var stockPackage: String
        get() = sp.getString("stock_package", "") ?: ""
        set(v) = sp.edit().putString("stock_package", v.trim()).apply()

    /** "preset:<id>" or "file:<name>" (a file in the app's wallpapers folder). */
    var wallpaper: String
        get() = sp.getString("wallpaper", Wallpapers.DEFAULT) ?: Wallpapers.DEFAULT
        set(v) = sp.edit().putString("wallpaper", v).apply()

    var videoEnabled: Boolean
        get() = sp.getBoolean("video_enabled", false)
        set(v) = sp.edit().putBoolean("video_enabled", v).apply()

    /** Video URIs in play order (newline-separated, since URIs can contain commas). */
    var videoSources: List<String>
        get() = (sp.getString("video_sources", "") ?: "").split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString("video_sources", v.joinToString("\n")).apply()

    var showStats: Boolean
        get() = sp.getBoolean("show_stats", true)
        set(v) = sp.edit().putBoolean("show_stats", v).apply()

    /** Set when an app is opened from All apps; the launcher shows All apps again when it comes back. */
    var returnToApps: Boolean
        get() = sp.getBoolean("return_to_apps", false)
        set(v) = sp.edit().putBoolean("return_to_apps", v).apply()

    /** Only offer videos that run at 120 fps or more. */
    var videoOnly120: Boolean
        get() = sp.getBoolean("video_only_120", false)
        set(v) = sp.edit().putBoolean("video_only_120", v).apply()

    /** Debug builds only: how many tiles to show in All apps by repeating the installed apps. */
    val demoApps: Int get() = sp.getInt("demo_apps", 0)

    /** Test switch: behave as on a small-RAM device. */
    var forceLowMem: Boolean
        get() = sp.getBoolean("force_low_mem", false)
        set(v) = sp.edit().putBoolean("force_low_mem", v).apply()

    /** Hold the video still while the icons are showing; it plays only once they have faded out. */
    var videoPauseWhenActive: Boolean
        get() = sp.getBoolean("video_pause_active", false)
        set(v) = sp.edit().putBoolean("video_pause_active", v).apply()

    /** Ease the video down to a stop and back up to speed; when off it stops and starts at once. */
    var videoSmoothPause: Boolean
        get() = sp.getBoolean("video_smooth_pause", true)
        set(v) = sp.edit().putBoolean("video_smooth_pause", v).apply()

    /** Video playback speed multiplier (0.25x - 2x). */
    var videoSpeed: Float
        get() = sp.getFloat("video_speed", 1f)
        set(v) = sp.edit().putFloat("video_speed", v).apply()

    /** "" = the app's own folder, "media:<path>" = a shared-storage folder. */
    var videoFolder: String
        get() = sp.getString("video_folder", VideoLibrary.APP_FOLDER) ?: VideoLibrary.APP_FOLDER
        set(v) = sp.edit().putString("video_folder", v).apply()

    /** Seconds without input before the home screen hides its icons over a video wallpaper; 0 = never. */
    var idleSeconds: Int
        get() = sp.getInt("idle_seconds", 60)
        set(v) = sp.edit().putInt("idle_seconds", v).apply()

    /** Wall-clock millis; keep-home does nothing until then. */
    var pausedUntil: Long
        get() = sp.getLong("paused_until", 0L)
        set(v) = sp.edit().putLong("paused_until", v).apply()

    /** Boot count of the last boot we launched on, so LOCKED_BOOT and BOOT do not both launch. */
    var lastBootId: Int
        get() = sp.getInt("last_boot_id", -1)
        set(v) = sp.edit().putInt("last_boot_id", v).apply()

    companion object {
        const val BOOT_LAUNCH_DELAY_MS = 4_000L
        const val PAUSE_MS = 10 * 60_000L
    }
}
