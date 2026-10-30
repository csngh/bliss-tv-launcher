@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.example.projectorlauncher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.Info
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class UiMessage(val text: String, val isError: Boolean)

/** Which home-screen item was focused last, kept for the life of the process so it can be restored on return. */
object HomeFocus {
    @Volatile var key: String = ""
    /** True after Back cleared the highlight; the next key press restores it instead of acting. */
    @Volatile var cleared: Boolean = false
    @Volatile var clearedAt: Long = 0L
}

private fun Modifier.trackHomeFocus(key: String): Modifier =
    onFocusChanged { if (it.hasFocus) {
            HomeFocus.key = key
            // Focus changes during the hand-off to the invisible holder must not undo the clear.
            if (android.os.SystemClock.uptimeMillis() - HomeFocus.clearedAt > 400) HomeFocus.cleared = false
        } }

/** Even darkening of the wallpaper behind All apps. */
const val DRAWER_DIM = 0.25f

const val MAX_DOCK = 6

private enum class Screen { HOME, APPS, SETTINGS, DOCK }

internal fun messageFor(label: String, result: LaunchResult): UiMessage? = when (result) {
    is LaunchResult.Opened -> if (result.skipped.isEmpty()) null else UiMessage(
        "$label: the standard action was unavailable, opened via ${result.via}\n" + result.skipped.first(),
        isError = false,
    )
    is LaunchResult.Failed -> UiMessage(
        "Unable to open $label (${result.errors.size} attempts)\n" + result.errors.take(3).joinToString("\n"),
        isError = true,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LauncherApp(homePresses: Int) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        val context = LocalContext.current
        val apps by rememberInstalledApps()
        val prefs = remember { Prefs(context) }
        var wallpaper by remember { mutableStateOf(prefs.wallpaper) }
        var hiddenApps by remember { mutableStateOf(prefs.hiddenApps) }
        var menuApp by remember { mutableStateOf<AppEntry?>(null) }
        var dockPackages by remember { mutableStateOf(prefs.dockApps.take(MAX_DOCK)) }
        val dockApps = remember(apps, dockPackages) { dockPackages.mapNotNull { pkg -> apps.firstOrNull { it.packageName == pkg } } }
        var screen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(if (prefs.returnToApps) Screen.APPS else Screen.HOME) }
        val hostView = LocalView.current
        var message by remember { mutableStateOf<UiMessage?>(null) }

        // A launcher is the root of the task: Back never leaves it.
        BackHandler(enabled = screen != Screen.HOME) { prefs.returnToApps = false; screen = Screen.HOME }
        LaunchedEffect(homePresses) { if (homePresses > 0) { prefs.returnToApps = false; screen = Screen.HOME } }
        LaunchedEffect(message) {
            if (message != null) {
                delay(10_000)
                message = null
            }
        }
        val onResult: (String, LaunchResult) -> Unit = { label, result -> messageFor(label, result)?.let { message = it } }

        Box(Modifier.fillMaxSize()) {
        AppBackground {
            when (screen) {
                Screen.SETTINGS -> LauncherSettingsScreen(
                    onClose = { screen = Screen.HOME },
                    onResult = onResult,
                    hiddenApps = hiddenApps,
                    onHiddenApps = { hiddenApps = it; prefs.hiddenApps = it },
                    wallpaper = wallpaper,
                    onWallpaper = { wallpaper = it; prefs.wallpaper = it },
                    onNotice = { text, isError -> message = UiMessage(text, isError) },
                )
                Screen.DOCK -> DockPickerScreen(
                    apps = apps,
                    selected = dockPackages,
                    wallpaper = wallpaper,
                    onNotice = { text -> message = UiMessage(text, true) },
                    onDone = { dockPackages = it.take(MAX_DOCK); prefs.dockApps = dockPackages; screen = Screen.HOME },
                    onCancel = { screen = Screen.HOME },
                )
                Screen.APPS -> AllAppsScreen(
                    apps = apps.let { real ->
                        // Debug builds only: repeat the list so paging can be tried with a few apps installed.
                        val demo = if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) prefs.demoApps else 0
                        if (demo <= 0 || real.isEmpty()) real else List(demo) { i -> real[i % real.size].let { AppEntry("${it.packageName}#$i", "${it.label} ${i + 1}", it.launchIntent) } }
                    }.filter { it.packageName !in hiddenApps },
                    wallpaper = if (prefs.videoEnabled && prefs.drawerStatic) "preset:midnight" else wallpaper,
                    videoSources = remember(screen) { if (prefs.videoEnabled && !prefs.drawerStatic) prefs.videoSources else emptyList() },
                    videoSpeed = remember(screen) { prefs.videoSpeed },
                    menuOpen = menuApp != null,
                    onOpenApp = { app ->
                        prefs.returnToApps = true      // coming back from the app lands here again, even if the launcher is recreated
                        SettingsLauncher.launchApp(context, app)?.let { message = UiMessage(it, true) }
                    },
                    onAppMenu = { menuApp = it },
                    onClose = { prefs.returnToApps = false; screen = Screen.HOME },
                )
                Screen.HOME -> HomeScreen(
                    apps = dockApps,
                    status = remember(screen) { homeStatus(context) },
                    wallpaper = wallpaper,
                    videoSources = remember(screen) { if (prefs.videoEnabled) prefs.videoSources else emptyList() },
                    idleSeconds = remember(screen) { prefs.idleSeconds },
                    videoSpeed = remember(screen) { prefs.videoSpeed },
                    pauseWhenActive = remember(screen) { prefs.videoPauseWhenActive },
                    smoothPause = remember(screen) { prefs.videoSmoothPause },
                    showStats = remember(screen) { prefs.showStats },
                    dockScale = remember(screen) { prefs.dockScale },
                    topScale = remember(screen) { prefs.topScale },
                    onOpenApp = { app -> SettingsLauncher.launchApp(context, app)?.let { message = UiMessage(it, true) } },
                    onSettings = { screen = Screen.SETTINGS },
                    onApps = { screen = Screen.APPS },
                    onEditDock = { screen = Screen.DOCK },
                    onQuick = { label ->
                        UTILITY_BUTTONS.firstOrNull { it.label == label }?.let { onResult(it.label, SettingsLauncher.launch(context, it)) }
                    },
                )
            }
            menuApp?.let { app ->
                AppMenu(
                    app,
                    onInfo = {
                        prefs.returnToApps = true
                        onResult("App info for ${app.label}", SettingsLauncher.openAppInfo(context, app))
                    },
                    onHide = {
                        hiddenApps = hiddenApps + app.packageName
                        prefs.hiddenApps = hiddenApps
                        message = UiMessage("${app.label} is hidden. It can be restored under Launcher settings, Hidden apps.", false)
                    },
                    onDismiss = { menuApp = null },
                )
            }
            message?.let { MessageBar(it, Modifier.align(Alignment.BottomCenter)) }
        }
        }
    }
}

private fun homeStatus(context: android.content.Context): String? {
    val prefs = Prefs(context)
    return when {
        !prefs.enabled -> "Automation off"
        prefs.keepOnTop -> "Keep-home on"
        else -> null
    }
}

@Composable
private fun rememberNow(): Date {
    var now by remember { mutableStateOf(Date()) }
    val resumed = rememberIsResumed()
    LaunchedEffect(resumed) {
        // No ticking while another app is in front.
        while (resumed) {
            now = Date()
            delay(60_000 - System.currentTimeMillis() % 60_000)
        }
    }
    return now
}

private val TextShadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 2f), 10f))
/** Heavier halo for text that sits directly on the wallpaper with no panel behind it. */
private val StrongShadow = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 1f), Offset(0f, 0f), 22f))

@Composable
private fun HomeScreen(
    apps: List<AppEntry>,
    status: String?,
    wallpaper: String,
    videoSources: List<String>,
    idleSeconds: Int,
    videoSpeed: Float,
    pauseWhenActive: Boolean,
    smoothPause: Boolean,
    showStats: Boolean,
    dockScale: Float,
    topScale: Float,
    onOpenApp: (AppEntry) -> Unit,
    onSettings: () -> Unit,
    onApps: () -> Unit,
    onEditDock: () -> Unit,
    onQuick: (String) -> Unit,
) {
    val now = rememberNow()
    val requesters = remember { HashMap<String, FocusRequester>() }
    val requesterFor: (String) -> FocusRequester = { requesters.getOrPut(it) { FocusRequester() } }
    // Put focus back on the item that was last used; fall back to the first dock app (or the add/edit tile).
    val restoreFocus: suspend () -> Unit = {
        androidx.compose.runtime.withFrameNanos { }
        val saved = HomeFocus.key
        val fallback = if (apps.isNotEmpty()) "dock:${apps.first().packageName}" else "edit"
        runCatching { requesterFor(if (saved in requesters && (!saved.startsWith("dock:") || apps.any { "dock:${it.packageName}" == saved })) saved else fallback).requestFocus() }
    }
    LaunchedEffect(apps.isNotEmpty()) { restoreFocus() }
    // Returning from another app leaves the window without focus; put it back when we resume.
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) scope.launch { restoreFocus() } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // Back on the home screen only drops the highlight (it never leaves the launcher): focus moves to
    // the invisible root, so no button looks selected until the next navigation key.
    val focusHolder = remember { FocusRequester() }

    val hasVideo = videoSources.isNotEmpty()

    // Idle fade: with a video wallpaper and a timeout set, hide the UI after that long without input.
    val resumedNow = rememberIsResumed()
    LaunchedEffect(hasVideo, idleSeconds, resumedNow) {
        IdleState.idle = false
        if (!hasVideo || idleSeconds <= 0 || !resumedNow) return@LaunchedEffect
        IdleState.poke()
        while (true) {
            delay(500)
            IdleState.idle = IdleState.idleFor() >= idleSeconds * 1000L
        }
    }
    DisposableEffect(Unit) { onDispose { IdleState.idle = false } }
    val hidden = hasVideo && IdleState.idle
    val motion = animationsEnabled(LocalContext.current)
    val uiAlpha by animateFloatAsState(
        if (hidden) 0f else 1f,
        if (!motion) androidx.compose.animation.core.snap() else tween(if (hidden) 1_000 else 350),
        label = "uiAlpha",
    )

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            // First navigation key after Back brings the highlight back where it was, without also moving or clicking.
            val nav = e.key in setOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
            if (e.key == Key.Back || e.key == Key.Escape) {
                // Back never leaves the launcher; on home it only drops the highlight.
                if (e.type == KeyEventType.KeyUp) {
                    HomeFocus.cleared = true
                    HomeFocus.clearedAt = android.os.SystemClock.uptimeMillis()
                    runCatching { focusHolder.requestFocus() }
                }
                true
            } else if (HomeFocus.cleared && nav) {
                if (e.type == KeyEventType.KeyDown) { HomeFocus.cleared = false; scope.launch { restoreFocus() } }
                true
            } else false
        }.focusRequester(focusHolder).focusable(),
    ) {
        if (hasVideo) {
            // Opaque black underneath so nothing of the launcher's own gradient shows through while a
            // video fades in or dissolves into the next one.
            Box(Modifier.fillMaxSize().background(Color.Black))
            VideoWallpaper(videoSources, videoSpeed, active = !pauseWhenActive || hidden, smooth = smoothPause)
        } else {
            WallpaperLayer(wallpaper)
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = uiAlpha }) {
            // The whole middle of the screen is left to the wallpaper; scrims only protect text legibility.
                // Soft shades behind the clock, buttons and credit line so white text stays readable on bright wallpapers.
            Box(
                Modifier.fillMaxWidth().height(220.dp).align(Alignment.TopCenter)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.50f), Color.Transparent))),
            )
            Box(
                Modifier.fillMaxWidth().height(200.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.50f)))),
            )

            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 48.dp, vertical = 32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(now),
                        fontSize = 44.sp * topScale, fontWeight = FontWeight.Light, color = Ui.TextPrimary, style = TextShadow,
                    )
                    Text(
                        SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now),
                        fontSize = 17.sp * topScale, color = Ui.TextPrimary.copy(alpha = 0.85f), style = TextShadow,
                    )
                    val stats = rememberStats(showStats)
                    // The line always takes its space, so the rows beside it never move when the numbers arrive.
                    if (showStats) Box(Modifier.padding(top = 4.dp).height(18.dp * topScale)) {
                        if (stats != null) {
                            Text(
                                (if (stats.cpuPercent == null) "CPU --%    " else "CPU ${stats.cpuPercent}%    ") + "RAM ${stats.ramPercent}%",
                                fontSize = 13.sp * topScale, color = Ui.TextPrimary.copy(alpha = 0.55f), style = TextShadow,
                            )
                        }
                    }
                }
                if (status != null) {
                    Pill(status, if (status == "Automation off") Ui.Warn else Ui.Good, Modifier.padding(end = 24.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    QuickAction("Wi-Fi", QuickIcons.Wifi, requesterFor("quick:Wi-Fi"), scale = topScale) { onQuick("Wi-Fi Settings") }
                    QuickAction("Bluetooth", QuickIcons.Bluetooth, requesterFor("quick:Bluetooth"), scale = topScale) { onQuick("Bluetooth") }
                    QuickAction("Screencast", QuickIcons.Cast, requesterFor("quick:Screencast"), scale = topScale) { onQuick("Screencast") }
                    QuickAction("Settings", Icons.Filled.Settings, requesterFor("quick:Settings"), scale = topScale) { onQuick("Settings") }
                QuickAction("Launcher settings", QuickIcons.Tune, requester = requesterFor("quick:Launcher settings"), scale = topScale, onClick = onSettings)
                }
            }

            Dock(apps, requesterFor, onApps, onEditDock, onOpenApp, if (hasVideo) null else wallpaper, dockScale, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
            CreditLine(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
        }
    }
}

/** Compact app shelf along the bottom edge. Scrolls sideways with the D-pad or the mouse wheel. */
@Composable
private fun Dock(apps: List<AppEntry>, requesterFor: (String) -> FocusRequester, onApps: () -> Unit, onEdit: () -> Unit, onOpenApp: (AppEntry) -> Unit, backdrop: String?, scale: Float, modifier: Modifier) {
    val listState = rememberLazyListState()
    val zoom = Modifier
    GlassPanel(
        modifier = modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 16.dp),
        shape = RoundedCornerShape(28.dp),
        backdrop = backdrop,
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().mouseScroll(listState, horizontal = true),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("all-apps") {
                DockActionTile(label = "All apps", icon = QuickIcons.Apps, requester = requesterFor("apps"), focusKey = "apps", onClick = onApps, modifier = zoom, scale = scale)
            }
            itemsIndexed(apps, key = { _, app -> app.packageName }) { _, app ->
                DockTile(app, requesterFor("dock:${app.packageName}"), zoom, scale) { onOpenApp(app) }
            }
            item("edit") {
                DockActionTile(
                    label = if (apps.isEmpty()) "Add apps" else "Edit dock",
                    icon = if (apps.isEmpty()) Icons.Filled.Add else Icons.Filled.Edit,
                    requester = requesterFor("edit"),
                    onClick = onEdit,
                    modifier = zoom,
                    scale = scale,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DockTile(app: AppEntry, requester: FocusRequester?, hover: Modifier = Modifier, scale: Float = 1f, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = hover.size(72.dp * scale).trackHomeFocus("dock:${app.packageName}").mouseClick(requester, onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.2f),
        border = glassFocusBorder(16),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Ui.TextPrimary,
            focusedContainerColor = Color.White.copy(alpha = 0.24f),
            focusedContentColor = Color.White,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Image(
                bitmap = rememberAppIcon(app) ?: ImageBitmap(1, 1),
                contentDescription = app.label,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(52.dp * scale),
            )
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: ImageVector, requester: FocusRequester? = null, scale: Float = 1f, onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 8.dp).trackHomeFocus("quick:$label")) {
        CircleButton(onClick = onClick, requester = requester, scale = scale) { Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp * scale)) }
    }
}

/** Every app in a large grid, over a darkened wallpaper. Back or the header returns home. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AllAppsScreen(apps: List<AppEntry>, wallpaper: String, videoSources: List<String>, videoSpeed: Float, menuOpen: Boolean, onOpenApp: (AppEntry) -> Unit, onAppMenu: (AppEntry) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(apps.isNotEmpty()) {
        if (apps.isNotEmpty()) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (videoSources.isNotEmpty()) {
            Box(Modifier.fillMaxSize().background(Color.Black))
            VideoWallpaper(videoSources, videoSpeed)
        } else {
            WallpaperLayer(wallpaper)
        }
        // A very light even dim keeps white labels readable on a bright wallpaper (set DRAWER_DIM to 0 to remove).
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = DRAWER_DIM)))
        Box(Modifier.fillMaxWidth().height(220.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.50f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.50f)))))
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 24.dp)) {
            Surface(
                onClick = onClose,
                modifier = Modifier.mouseClick(onClick = onClose),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = Ui.TextPrimary,
                    focusedContainerColor = Color.White.copy(alpha = 0.24f),
                    focusedContentColor = Color.White,
                ),
                border = focusBorder(12),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ArrowBack, "Back to home", modifier = Modifier.size(24.dp))
                    Text("All apps", fontSize = 26.sp, fontWeight = FontWeight.Bold, style = TextShadow, modifier = Modifier.padding(start = 12.dp))
                    Pill("${apps.size}", Ui.TextPrimary, Modifier.padding(start = 12.dp))
                }
            }
            if (apps.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    EmptyState(Icons.Filled.Info, "No apps to show", "Hidden applications can be restored under Launcher settings, Hidden apps.", Tone.Blue)
                }
            } else PagedAppGrid(apps, first, menuOpen, onOpenApp, onAppMenu, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/**
 * Sizes the tiles so a whole number of columns and rows exactly fills the space, so no row is ever cut in half.
 * Scrolling moves in whole rows only: the wheel steps one row, and a drag or a focus move that ends between
 * rows snaps to the nearest one.
 */
@OptIn(ExperimentalTvMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
@Composable
private fun PagedAppGrid(apps: List<AppEntry>, first: FocusRequester, menuOpen: Boolean, onOpenApp: (AppEntry) -> Unit, onAppMenu: (AppEntry) -> Unit, modifier: Modifier) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // When the app menu closes, focus returns to the tile it was opened from (not to the header).
    val requesters = remember { HashMap<String, FocusRequester>() }
    var menuFrom by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(menuOpen) {
        if (!menuOpen) menuFrom?.let { pkg -> kotlinx.coroutines.delay(60); runCatching { requesters[pkg]?.requestFocus() } }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.TopCenter) {
        val gap = 16.dp
        val edge = 16.dp            // room inside the grid for the enlarged, outlined tile on every side
        val cols = 6
        val tileW = (maxWidth - edge * 2 - gap * (cols - 1)) / cols
        val availH = maxHeight - edge * 2
        val rows = 3
        val tileH = (availH - gap * (rows - 1)) / rows
        val viewportH = tileH * rows + gap * (rows - 1) + edge * 2
        val totalRows = (apps.size + cols - 1) / cols
        val maxTopRow = (totalRows - rows).coerceAtLeast(0)
        val strideDp = tileH + gap
        val stridePx = with(androidx.compose.ui.platform.LocalDensity.current) { strideDp.toPx() }

        fun currentTopRow(): Int {
            val r = gridState.firstVisibleItemIndex / cols
            return (if (gridState.firstVisibleItemScrollOffset > stridePx / 2f) r + 1 else r).coerceIn(0, maxTopRow)
        }
        // Whenever the position rests between rows (after a drag, a flick or a focus move), settle on a whole row.
        LaunchedEffect(gridState, cols, maxTopRow, stridePx) {
            snapshotFlow { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
                .debounce(180)
                .collect { (index, offset) ->
                    if (offset != 0 || index % cols != 0) gridState.animateScrollToItem(currentTopRow() * cols)
                }
        }
        // Up and down move by whole rows: scroll first (when the target row is off screen), then focus the tile.
        // Left to the focus search, a row that is not composed yet is skipped and focus jumps to the header.
        var moveJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        fun moveRow(from: Int, dir: Int): Boolean {
            val lastRow = totalRows - 1
            val row = from / cols
            val target = row + dir
            if (target < 0 || target > lastRow) return false      // first row: let focus leave for the header
            val targetIndex = minOf(target * cols + from % cols, apps.size - 1)
            val top = currentTopRow()
            val newTop = when {
                target < top -> target
                target > top + rows - 1 -> (target - rows + 1).coerceIn(0, maxTopRow)
                else -> null
            }
            moveJob?.cancel()
            if (newTop == null) {
                // The target row is already on screen: move focus in the same frame, with no coroutine hop.
                runCatching { requesters[apps[targetIndex].packageName]?.requestFocus() }
                return true
            }
            moveJob = scope.launch {
                gridState.animateScrollToItem(newTop * cols)
                runCatching { requesters[apps[targetIndex].packageName]?.requestFocus() }
            }
            return true
        }
        val spec = remember(stridePx) { minimalBringIntoView(slackPx = stridePx * 0.2f) }
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides spec) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(cols),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            contentPadding = PaddingValues(horizontal = edge, vertical = edge),
            modifier = Modifier.fillMaxWidth().height(viewportH)
                .mouseScroll(gridState, onWheel = { dir ->
                    scope.launch { gridState.animateScrollToItem((currentTopRow() + dir).coerceIn(0, maxTopRow) * cols) }
                }),
        ) {
            gridItemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                GridTile(app, tileH, if (index == 0) first.also { requesters[app.packageName] = it } else requesters.getOrPut(app.packageName) { FocusRequester() }, onLongClick = { menuFrom = app.packageName; onAppMenu(app) },
                    keys = Modifier.onPreviewKeyEvent { e ->
                        if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) false
                        else when (e.key) {
                            androidx.compose.ui.input.key.Key.DirectionUp -> moveRow(index, -1)
                            androidx.compose.ui.input.key.Key.DirectionDown -> moveRow(index, 1)
                            else -> false
                        }
                    }) { onOpenApp(app) }
            }
        }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GridTile(app: AppEntry, height: androidx.compose.ui.unit.Dp, requester: FocusRequester?, onLongClick: () -> Unit, keys: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().height(height).then(keys).mouseClick(requester, onClick, onLongClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
        border = glassFocusBorder(16),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Ui.TextPrimary,
            focusedContainerColor = Color.White.copy(alpha = 0.24f),
            focusedContentColor = Color.White,
        ),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(bitmap = rememberAppIcon(app) ?: ImageBitmap(1, 1), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.weight(1f, fill = false).heightIn(max = height * 0.5f).aspectRatio(1f))
            Text(
                app.label,
                modifier = Modifier.padding(top = 8.dp),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Ui.TextPrimary,
                style = StrongShadow,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Options for one app in All apps. Disabling is Android's own page: an app cannot silently disable another one. */
@Composable
private fun AppMenu(app: AppEntry, onInfo: () -> Unit, onHide: () -> Unit, onDismiss: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val firstRow = remember { FocusRequester() }
    // Opened by holding select: ignore the rest of that press so it cannot activate a row.
    remember { SelectKey.swallowUntilRelease() }
    LaunchedEffect(Unit) { runCatching { firstRow.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(560.dp).background(Ui.BgTop, RoundedCornerShape(24.dp)).pointerInput(Unit) { detectTapGestures { } }.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(bitmap = rememberAppIcon(app) ?: ImageBitmap(1, 1), contentDescription = null, modifier = Modifier.size(40.dp))
                Text(app.label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ui.TextPrimary, modifier = Modifier.padding(start = 16.dp))
            }
            RowCard("App info", subtitle = "Force stop, clear data, uninstall, and disable where permitted",
                modifier = Modifier.focusRequester(firstRow).padding(top = 8.dp)) { onInfo(); onDismiss() }
            RowCard("Hide from All apps", subtitle = "Removes the app from this list. It can be restored under Launcher settings, Hidden apps") { onHide(); onDismiss() }
            RowCard("Cancel", subtitle = "Close the menu") { onDismiss() }
            Text("This launcher cannot disable applications directly. Android permits this only from the application's information page.", fontSize = 12.sp, color = Ui.TextSecondary, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DockActionTile(label: String, icon: ImageVector, requester: FocusRequester, focusKey: String = "edit", modifier: Modifier = Modifier, scale: Float = 1f, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(72.dp * scale).trackHomeFocus(focusKey).mouseClick(requester, onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.2f),
        border = glassFocusBorder(16),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White.copy(alpha = 0.8f),
            focusedContainerColor = Color.White.copy(alpha = 0.24f),
            focusedContentColor = Color.White,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, modifier = Modifier.size(34.dp * scale)) }
    }
}

/** Dialog: every installed app with a checkbox, at most [MAX_DOCK] ticked. Done saves, Cancel/Back discards. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DockPickerScreen(
    apps: List<AppEntry>,
    selected: List<String>,
    wallpaper: String,
    onNotice: (String) -> Unit,
    onDone: (List<String>) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember { mutableStateOf(selected) }
    val first = remember { FocusRequester() }
    LaunchedEffect(apps.isNotEmpty()) {
        if (apps.isNotEmpty()) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
    Box(Modifier.fillMaxSize()) {
        WallpaperLayer(wallpaper)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
        Column(
            Modifier
                .align(Alignment.Center)
                .width(880.dp)
                .fillMaxHeight(0.9f)
                .background(Ui.BgTop.copy(alpha = 0.97f), RoundedCornerShape(28.dp))
                .padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Choose dock apps", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ui.TextPrimary)
                    Text("Select up to $MAX_DOCK apps for the dock at the bottom of the home screen.", fontSize = 15.sp, color = Ui.TextSecondary)
                }
                Pill("${draft.size} / $MAX_DOCK", if (draft.size >= MAX_DOCK) Ui.Warn else Ui.Accent)
            }
            val pickerState = rememberLazyListState()
            LazyColumn(
                state = pickerState,
                modifier = Modifier.weight(1f).padding(vertical = 16.dp).fadingEdges(pickerState).mouseScroll(pickerState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                    val checked = app.packageName in draft
                    RowCard(
                        app.label,
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                        subtitle = app.packageName,
                        leading = { Image(bitmap = rememberAppIcon(app) ?: ImageBitmap(1, 1), contentDescription = null, modifier = Modifier.size(40.dp)) },
                        trailing = { Checkbox(checked) },
                    ) {
                        draft = when {
                            checked -> draft - app.packageName
                            draft.size >= MAX_DOCK -> {
                                onNotice("The dock is full ($MAX_DOCK apps maximum). Please deselect one first.")
                                draft
                            }
                            else -> draft + app.packageName
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                DialogButton("Cancel", primary = false, onClick = onCancel)
                Box(Modifier.width(14.dp))
                DialogButton("Done", primary = true) { onDone(draft) }
            }
        }
    }
}

@Composable
internal fun Checkbox(checked: Boolean) {
    Box(
        Modifier
            .size(30.dp)
            .background(if (checked) Ui.Accent else Color.Transparent, RoundedCornerShape(8.dp))
            .border(2.dp, if (checked) Ui.Accent else Ui.TextSecondary, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Color(0xFF06101F), modifier = Modifier.size(22.dp))
    }
}
