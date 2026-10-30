@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.example.projectorlauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class Section(val title: String, val blurb: String, val icon: ImageVector) {
    HOME("Home screen", "Dock and top bar size, system readout", QuickIcons.Apps),
    WALLPAPER("Wallpaper", "Select the home screen background", Icons.Filled.Star),
    VIDEO("Video wallpaper", "Looping video wallpaper for the home screen", QuickIcons.Movie),
    TARGETS("Button targets", "Select the destination of each quick button", Icons.Filled.Edit),
    SCREENCAST("Screencast app", "Select the application opened by Screencast", Icons.Filled.PlayArrow),
    SHORTCUTS("Shortcuts", "Direct access to system pages", Icons.Filled.Menu),
    HIDDEN("Hidden apps", "Applications hidden from All apps", Icons.Filled.Lock),
    AUTOMATION("Automation", "Start at boot and remain in front", Icons.Filled.Refresh),
    LAUNCHER("Stock launcher", "The system launcher to monitor", Icons.Filled.Home),
    PERMISSIONS("Permissions", "Permissions required for automation", Icons.Filled.Lock),
    DIAGNOSTICS("Diagnostics", "Settings packages installed on this device", Icons.Filled.Info);
}

/** These live on the home screen; Shortcuts lists only the rest. */
private val HOME_BUTTONS = setOf("Settings", "Wi-Fi Settings", "Bluetooth", "Screencast")
private val TARGET_BUTTONS = listOf("Settings", "Wi-Fi Settings", "Bluetooth")
private val TARGET_NAMES = mapOf("Settings" to "Settings", "Wi-Fi Settings" to "Wi-Fi", "Bluetooth" to "Bluetooth")

private val DOCK_SIZES = listOf(0.8f to "Small", 1f to "Normal", 1.25f to "Large", 1.5f to "Extra large")

private val VIDEO_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** Why a speed looks the way it does: frames are shown on a 60 Hz display, so only some ratios give even pacing. */
private fun speedNote(s: Float): String = when (s) {
    1f -> "Original speed"
    2f, 0.5f -> "Frame pacing is smooth at this speed"
    0.25f -> "Motion may appear stepped below 60 fps. Please refer to tools/smooth-slowmo.sh"
    else -> "Slight judder is possible. 0.5x, 1x and 2x give the smoothest result"
}

private fun speedLabel(s: Float) = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString().trimEnd('0')) + "x"

private val SHORTCUT_BLURBS = mapOf(
    "Settings" to "System settings of this device",
    "Developer Options" to "USB debugging and build options",
    "About Device" to "Model, version and status information",
    "Wi-Fi Settings" to "Network connection settings",
    "Bluetooth" to "Pairing of remotes, keyboards and speakers",
    "Screencast" to "Opens the application selected under Screencast app",
    "Install Unknown Apps" to "Installation permission for each application",
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LauncherSettingsScreen(
    onClose: () -> Unit,
    onResult: (String, LaunchResult) -> Unit,
    hiddenApps: Set<String>,
    onHiddenApps: (Set<String>) -> Unit,
    wallpaper: String,
    onWallpaper: (String) -> Unit,
    onNotice: (String, Boolean) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    // Bumped whenever we come back from a system settings page, to re-read permission state.
    var refresh by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    var section by remember { mutableStateOf(Section.entries.first()) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var autoStart by remember { mutableStateOf(prefs.autoStartOnBoot) }
    var keepOnTop by remember { mutableStateOf(prefs.keepOnTop) }
    var stockText by remember { mutableStateOf(prefs.stockPackage) }
    var savedStock by remember { mutableStateOf(prefs.stockPackage) }
    var pausedUntil by remember { mutableStateOf(prefs.pausedUntil) }
    var editing by remember { mutableStateOf(false) }

    val overlayOk = remember(refresh) { KeepHome.canDrawOverlays(context) }
    val usageOk = remember(refresh) { KeepHome.hasUsageAccess(context) }
    val notifOk = remember(refresh) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).areNotificationsEnabled()
    }
    val homeApps = remember {
        AppRepository.queryActivities(
            context.packageManager,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        ).map { it.activityInfo.packageName }.distinct().filter { !KeepHome.isProtected(context, it) }
    }
    var wallpaperTick by remember { mutableIntStateOf(0) }
    val wallpaperFiles = remember(refresh, wallpaperTick) { Wallpapers.files(context) }
    val wallpaperDir = remember { Wallpapers.dir(context)?.path ?: "(storage unavailable)" }
    var screencastPkg by remember { mutableStateOf(prefs.screencastPackage) }
    var confirm by remember { mutableStateOf<ConfirmSpec?>(null) }
    var dockScale by remember { mutableStateOf(prefs.dockScale) }
    var topScale by remember { mutableStateOf(prefs.topScale) }
    var showStats by remember { mutableStateOf(prefs.showStats) }
    var videoEnabled by remember { mutableStateOf(prefs.videoEnabled) }
    var videoSel by remember { mutableStateOf(prefs.videoSources) }
    var videoFolder by remember { mutableStateOf(prefs.videoFolder) }
    var idleSeconds by remember { mutableStateOf(prefs.idleSeconds) }
    var videoSpeed by remember { mutableStateOf(prefs.videoSpeed) }
    var pauseWhenActive by remember { mutableStateOf(prefs.videoPauseWhenActive) }
    var smoothPause by remember { mutableStateOf(prefs.videoSmoothPause) }
    var videoTick by remember { mutableIntStateOf(0) }
    var videoView by remember { mutableStateOf(0) }   // 0 usable, 1 selected, 2 all
    var drawerStatic by remember { mutableStateOf(prefs.drawerStatic) }
    var videoOnly120 by remember { mutableStateOf(prefs.videoOnly120) }
    val screenAspect = remember { VideoLibrary.screenAspect(context) }
    val (screenW, screenH) = remember { VideoLibrary.screenSize(context) }
    var videos by remember { mutableStateOf<List<VideoItem>?>(null) }
    // The folder is chosen in the device's own file manager, so no folder list lives in this app.
    fun useFolder(path: String) {
        videoFolder = "media:$path"; prefs.videoFolder = videoFolder
        videoSel = emptyList(); prefs.videoSources = emptyList(); videoTick++
        onNotice("Video folder set to $path", false)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val path = uri?.let { VideoLibrary.treeToPath(it) }
        when {
            uri == null -> onNotice("No folder was selected.", true)
            path == null -> onNotice("Unable to use that location. Please select a folder on the device or on a USB drive.", true)
            else -> useFolder(path)
        }
    }
    // Devices without a folder picker: the file manager's file picker is used instead. Choosing any video selects its folder.
    val filePicker = rememberLauncherForActivityResult(PickVideoFile()) { uri ->
        val path = uri?.let { VideoLibrary.fileToFolder(context, it) }
        when {
            uri == null -> onNotice("No video was selected.", true)
            path == null -> onNotice("Unable to find the folder of that video. Please try another file manager.", true)
            else -> useFolder(path)
        }
    }
    fun openFolderPicker() {
        try {
            if (VideoLibrary.hasFolderPicker(context)) folderPicker.launch(null)
            else {
                onNotice("This device has no folder picker. Please select any video inside the required folder.", false)
                filePicker.launch(Unit)
            }
        } catch (e: Exception) { onNotice("Unable to open a file manager on this device. Please install one.", true) }
    }
    val videoPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openFolderPicker() else onNotice("Without video access only the app folder can be used.", true)
    }
    LaunchedEffect(section, videoFolder, videoTick) {
        if (section == Section.VIDEO) {
            videos = withContext(Dispatchers.IO) {
                if (videoFolder.isNotEmpty() && !VideoLibrary.hasPermission(context)) emptyList() else VideoLibrary.scan(context, videoFolder)
            }
        }
    }
    /** Why a video cannot be used, or null when it can. */
    fun blockOf(v: VideoItem): String? = when {
        v.unreadable -> "UNREADABLE"
        v.tooLong -> "TOO LONG"
        !v.landscape -> "NOT LANDSCAPE"
        !v.fitsScreen(screenAspect) -> "WRONG SHAPE"
        v.tooLarge(screenW, screenH) -> "TOO LARGE"
        videoOnly120 && !v.is120 -> "NOT 120 FPS"
        else -> null
    }
    fun pruneSelection() {
        val list = videos ?: return
        val ok = list.filter { blockOf(it) == null }.map { it.key }.toSet()
        val kept = videoSel.filter { it in ok }
        if (kept.size != videoSel.size) { videoSel = kept; prefs.videoSources = kept }
    }
    LaunchedEffect(videos) { if (section == Section.VIDEO) pruneSelection() }
    var allApps by remember { mutableStateOf<List<AppEntry>?>(null) }
    val screencastAuto = remember { SettingsLauncher.autoScreencastLabel(context) }
    LaunchedEffect(section) {
        if ((section == Section.SCREENCAST || section == Section.HIDDEN) && allApps == null) {
            allApps = withContext(Dispatchers.IO) { AppRepository.load(context) }
        }
    }
    var targetFor by remember { mutableStateOf<UtilityButton?>(null) }     // button being edited
    var targetPkg by remember { mutableStateOf<String?>(null) }            // package being browsed
    var targetManual by remember { mutableStateOf(false) }
    var targetText by remember { mutableStateOf("") }
    var targetTick by remember { mutableIntStateOf(0) }
    val targetPackages = remember(targetFor) {
        if (targetFor == null) emptyList() else {
            val pm = context.packageManager
            val launchable = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap {
                AppRepository.queryActivities(pm, Intent(Intent.ACTION_MAIN).addCategory(it))
            }.map { it.activityInfo.packageName }
            @Suppress("DEPRECATION")
            val named = pm.getInstalledPackages(0).map { it.packageName }.filter { Regex("(?i)setting|lumina|htc|wifi|bluetooth").containsMatchIn(it) }
            (launchable + named).distinct().filter { it != context.packageName }.sorted()
        }
    }
    val targetActivities = remember(targetPkg) {
        targetPkg?.let { pkg ->
            try {
                @Suppress("DEPRECATION")
                val info = if (android.os.Build.VERSION.SDK_INT >= 33)
                    context.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_ACTIVITIES.toLong()))
                else context.packageManager.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES)
                info.activities.orEmpty().filter { it.exported && it.enabled }.map { it.name }.sorted()
            } catch (e: Exception) { emptyList() }
        }
    }
    var diagnostics by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(section) {
        if (section == Section.DIAGNOSTICS && diagnostics == null) {
            diagnostics = withContext(Dispatchers.IO) { collectDebugLines(context) }
        }
    }

    fun saveStock(value: String): Boolean {
        val pkg = value.trim()
        if (pkg.isNotEmpty() && KeepHome.isProtected(context, pkg)) {
            onNotice("$pkg is Settings/system or this launcher, so it is protected and cannot be selected.", true)
            return false
        }
        prefs.stockPackage = pkg
        stockText = pkg
        savedStock = pkg
        onNotice(if (pkg.isEmpty()) "Stock launcher package cleared" else "Stock launcher saved: $pkg", false)
        return true
    }

    fun openPermission(label: String, attempts: List<Pair<String, Intent>>) =
        onResult(label, SettingsLauncher.launchAttempts(context, attempts))

    // Back steps out of the target editor (package -> list) before it leaves settings altogether.
    BackHandler(enabled = targetFor != null) {
        if (targetPkg != null) targetPkg = null else targetFor = null
    }

    val pkgUri = Uri.parse("package:${context.packageName}")
    val firstRail = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { firstRail.requestFocus() }
    }

    @Composable
    fun Toggle(title: String, subtitle: String, on: Boolean, onClick: () -> Unit) =
        RowCard(title, subtitle = subtitle, trailing = { ToggleSwitch(on) }, onClick = onClick)

    @Composable
    fun PermissionRow(title: String, subtitle: String, ok: Boolean, okText: String, badText: String, badColor: Color, onOpen: () -> Unit) =
        RowCard(
            title, subtitle = subtitle,
            trailing = { Pill(if (ok) okText else badText, if (ok) Ui.Good else badColor) },
            onClick = onOpen,
        )

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
        // Side navigation
        val navScroll = rememberScrollState()
        val navScope = androidx.compose.runtime.rememberCoroutineScope()
        val navMargin = with(androidx.compose.ui.platform.LocalDensity.current) { 16.dp.toPx() }
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(navMargin) { minimalBringIntoView(marginPx = navMargin) }) {
        Column(
            Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).fillMaxHeight().background(Color.Black.copy(alpha = 0.28f))
                .fadingEdges(navScroll).mouseScroll(navScroll)
                .verticalScroll(navScroll)
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Row(Modifier.padding(start = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Ui.BgTop))
                Text("Bliss Launcher", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Ui.TextPrimary, modifier = Modifier.padding(start = 8.dp))
            }
            Surface(
                onClick = onClose,
                modifier = Modifier.padding(bottom = 16.dp).mouseClick(onClick = onClose)
                    .onFocusChanged { if (it.isFocused) navScope.launch { kotlinx.coroutines.delay(120); navScroll.animateScrollTo(0) } },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = Ui.TextPrimary,
                    focusedContainerColor = Ui.CardFocused,
                    focusedContentColor = Ui.TextPrimary,
                ),
                border = focusBorder(12),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.ArrowBack, "Back to home", modifier = Modifier.size(18.dp))
                    Text("  Launcher settings", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            Section.entries.forEachIndexed { i, s ->
                NavItem(
                    s.title, s.icon, Tone.Blue, selected = s == section,
                    requester = if (i == 0) firstRail else null,
                    onFocus = {
                        section = s
                        // The first and last sections reveal the header and the bottom padding too.
                        if (i == 0) navScope.launch { kotlinx.coroutines.delay(120); navScroll.animateScrollTo(0) }
                        else if (i == Section.entries.lastIndex) navScope.launch { kotlinx.coroutines.delay(120); navScroll.animateScrollTo(navScroll.maxValue) }
                    },
                )
            }
        }
        }

        // Content
        Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 24.dp, vertical = 24.dp)) {
            Text(section.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ui.TextPrimary)
            Text(section.blurb, fontSize = 13.sp, color = Ui.TextSecondary, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))

            val listState = rememberLazyListState()
            // Keeps the focused row clear of the edge, so the list padding stays visible at both ends.
            val listMargin = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() }
            // Each section starts at the top.
            LaunchedEffect(section, targetFor, targetPkg) { listState.scrollToItem(0) }
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(listMargin) { minimalBringIntoView(marginPx = listMargin) }) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().fadingEdges(listState).mouseScroll(listState),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (section) {
                    Section.SHORTCUTS -> UTILITY_BUTTONS.filter { it.label !in HOME_BUTTONS }.forEach { b ->
                        item(b.label) {
                            RowCard(b.label, subtitle = SHORTCUT_BLURBS[b.label]) { onResult(b.label, SettingsLauncher.launch(context, b)) }
                        }
                    }

                    Section.TARGETS -> {
                        val editing = targetFor
                        if (editing == null) {
                            UTILITY_BUTTONS.filter { it.label in TARGET_BUTTONS }.forEach { b ->
                                item("t-${b.label}") {
                                    val current = remember(targetTick) { prefs.target(b.label) }
                                    RowCard(
                                        TARGET_NAMES[b.label] ?: b.label,
                                        subtitle = if (current.isEmpty()) "Automatic" else current,
                                        trailing = { Pill(if (current.isEmpty()) "AUTO" else "CUSTOM", if (current.isEmpty()) Ui.TextSecondary else Ui.Accent) },
                                    ) { targetFor = b; targetPkg = null; targetManual = false; targetText = current }
                                }
                            }
                            item("t-tip") { InfoCard("If the selected activity fails to open, the automatic order is used.") }
                        } else {
                            item("t-back-top") { RowCard("Back to list", subtitle = "Return without making changes") { targetFor = null; targetPkg = null } }
                            item("t-head") { SectionLabel("Target for ${TARGET_NAMES[editing.label] ?: editing.label}") }
                            item("t-auto") {
                                RowCard("Automatic", subtitle = "Use the built-in order for this device") {
                                    prefs.setTarget(editing.label, "")
                                    onNotice("${editing.label}: automatic", false)
                                    targetFor = null; targetTick++
                                }
                            }
                            if (!targetManual) {
                                item("t-manual") { RowCard("Enter an activity", subtitle = "Enter a class name or package/class") { targetManual = true } }
                            } else {
                                item("t-field") { PackageField(targetText) { targetText = it } }
                                item("t-save") {
                                    RowCard("Save and test", subtitle = "Saves the destination and opens it for verification") {
                                        val comp = SettingsLauncher.resolveComponent(context, targetText)
                                        if (comp == null) onNotice("Unable to find an installed package for that. Please use package/class, or a class name whose package is installed.", true)
                                        else {
                                            prefs.setTarget(editing.label, comp.flattenToString())
                                            onResult(editing.label, SettingsLauncher.launch(context, editing))
                                            targetFor = null; targetTick++
                                        }
                                    }
                                }
                            }
                            val pkg = targetPkg
                            if (pkg == null) {
                                item("t-pk-label") { SectionLabel("Installed packages") }
                                targetPackages.forEach { p ->
                                    item("t-pk-$p") { RowCard(p, subtitle = "View the activities of this package") { targetPkg = p } }
                                }
                            } else {
                                item("t-act-label") { SectionLabel("Activities in $pkg") }
                                item("t-back") { RowCard("Back to packages") { targetPkg = null } }
                                val acts = targetActivities.orEmpty()
                                if (acts.isEmpty()) item("t-none") { InfoCard("No exported activities found for this package.") }
                                acts.forEach { a ->
                                    item("t-a-$a") {
                                        RowCard(a.removePrefix("$pkg."), subtitle = a) {
                                            prefs.setTarget(editing.label, "$pkg/$a")
                                            onResult(editing.label, SettingsLauncher.launch(context, editing))
                                            targetFor = null; targetPkg = null; targetTick++
                                        }
                                    }
                                }
                            }
                            item("t-cancel") { RowCard("Cancel") { targetFor = null; targetPkg = null } }
                        }
                    }

                    Section.SCREENCAST -> {
                        item("sc-auto") {
                            RowCard(
                                "Automatic",
                                subtitle = "Detected automatically: ${screencastAuto ?: "none found on this device"}",
                                trailing = { if (screencastPkg.isEmpty()) Pill("SELECTED", Ui.Good) },
                            ) {
                                screencastPkg = ""
                                prefs.screencastPackage = ""
                            }
                        }
                        item("sc-label") { SectionLabel("Installed apps") }
                        val apps = allApps
                        if (apps == null) item("sc-loading") { InfoCard("Loading apps...") }
                        else apps.forEach { app ->
                            item("sc-${app.packageName}") {
                                RowCard(
                                    app.label,
                                    subtitle = app.packageName,
                                    trailing = { if (screencastPkg == app.packageName) Pill("SELECTED", Ui.Good) },
                                ) {
                                    screencastPkg = app.packageName
                                    prefs.screencastPackage = app.packageName
                                    onNotice("Screencast will open ${app.label}", false)
                                }
                            }
                        }
                    }

                    Section.VIDEO -> {
                        item("v-enabled") {
                            RowCard(
                                "Video wallpaper",
                                subtitle = "${videoSel.size} video${if (videoSel.size == 1) "" else "s"} selected. Muted playback with a dissolve between clips",
                                trailing = { ToggleSwitch(videoEnabled) },
                            ) {
                                videoEnabled = !videoEnabled
                                prefs.videoEnabled = videoEnabled
                                if (videoEnabled && videoSel.isEmpty()) onNotice("Please select at least one video below.", true)
                            }
                        }
                        item("v-idle") {
                            RowCard(
                                "Hide icons after",
                                subtitle = if (idleSeconds == 0) "Icons remain visible at all times" else "Icons fade out after ${idleLabel(idleSeconds)} without input",
                                trailing = { Pill(idleLabel(idleSeconds), if (idleSeconds == 0) Ui.TextSecondary else Ui.Accent) },
                            ) {
                                idleSeconds = IDLE_CHOICES[(IDLE_CHOICES.indexOf(idleSeconds) + 1) % IDLE_CHOICES.size]
                                prefs.idleSeconds = idleSeconds
                            }
                        }
                        item("v-pause") {
                            RowCard(
                                "Pause while icons are visible",
                                subtitle = when {
                                    !pauseWhenActive -> "The video plays continuously behind the icons"
                                    idleSeconds == 0 -> "Requires \"Hide icons after\" to be set, otherwise the video does not play"
                                    else -> "The video pauses during use and resumes when the icons fade out"
                                },
                                trailing = { ToggleSwitch(pauseWhenActive) },
                            ) {
                                pauseWhenActive = !pauseWhenActive
                                prefs.videoPauseWhenActive = pauseWhenActive
                            }
                        }
                        item("v-smooth") {
                            RowCard(
                                "Smooth pause and resume",
                                subtitle = if (smoothPause) "The video eases to a stop and back up to speed" else "The video stops and starts immediately",
                                trailing = { ToggleSwitch(smoothPause) },
                            ) { smoothPause = !smoothPause; prefs.videoSmoothPause = smoothPause }
                        }
                        item("v-speed") {
                            Column(
                                Modifier.fillMaxWidth().background(Ui.Card, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Playback speed", color = Ui.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Pill(speedLabel(videoSpeed), if (videoSpeed == 1f) Ui.TextSecondary else Ui.Accent)
                                }
                                Text(
                                    speedNote(videoSpeed),
                                    color = Ui.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    VIDEO_SPEEDS.forEach { sp ->
                                        ChipButton(speedLabel(sp), selected = sp == videoSpeed, modifier = Modifier.weight(1f)) {
                                            videoSpeed = sp
                                            prefs.videoSpeed = sp
                                        }
                                    }
                                }
                            }
                        }
                        item("v-folder-label") { SectionLabel("Video folder") }
                        item("v-folder") {
                            RowCard(
                                if (videoFolder.isEmpty()) "App folder" else "Shared folder",
                                subtitle = VideoLibrary.folderLabel(context, videoFolder),
                                trailing = { Pill("CURRENT", Ui.Good) },
                            ) { videoTick++ }
                        }
                        item("v-choose") {
                            RowCard("Choose folder", subtitle = "Opens the file manager. If no folder picker exists, select any video in the folder") {
                                if (VideoLibrary.hasPermission(context)) openFolderPicker() else videoPermission.launch(VideoLibrary.permission)
                            }
                        }
                        if (videoFolder.isNotEmpty()) item("v-default") {
                            RowCard("Use the app folder", subtitle = "No additional permission required") {
                                videoFolder = VideoLibrary.APP_FOLDER
                                prefs.videoFolder = videoFolder
                            }
                        }
                        item("v-drawer") {
                            RowCard(
                                "Static wallpaper in app drawer",
                                subtitle = if (drawerStatic) "All apps retains the Midnight background"
                                else "All apps also shows the video wallpaper",
                                trailing = { ToggleSwitch(drawerStatic) },
                            ) { drawerStatic = !drawerStatic; prefs.drawerStatic = drawerStatic }
                        }
                        item("v-60") {
                            RowCard(
                                "Only 120 fps videos",
                                subtitle = if (videoOnly120) "Videos below 120 fps cannot be selected" else "Videos of any frame rate are permitted",
                                trailing = { ToggleSwitch(videoOnly120) },
                            ) {
                                videoOnly120 = !videoOnly120
                                prefs.videoOnly120 = videoOnly120
                                if (videoOnly120) pruneSelection()
                            }
                        }
                        item("v-rule") { InfoCard("Screen ${screenW}x${screenH}. Only landscape videos of the same aspect ratio are permitted.") }
                        item("v-list-label") { SectionLabel("Videos (30 seconds or shorter)") }
                        val all = videos
                        if (all == null) item("v-loading") { InfoCard("Reading videos...") }
                        else {
                            val usable = all.filter { blockOf(it) == null }
                            val chosen = all.filter { it.key in videoSel }
                            // When nothing in the folder is usable, show every file with its reason instead of an empty list.
                            val view = if (videoView == 0 && usable.isEmpty() && all.isNotEmpty()) 2 else videoView
                            val list = when (view) { 0 -> usable; 1 -> chosen; else -> all }
                            if (all.size > 8 || usable.size < all.size) {
                                item("v-view") {
                                    RowCard(
                                        "Show: ${listOf("Usable only", "Selected only", "All videos")[view]}",
                                        subtitle = "${usable.size} usable, ${chosen.size} selected, ${all.size} in folder",
                                        trailing = { Pill("${list.size} shown", Ui.Accent) },
                                    ) { videoView = (view + 1) % 3 }
                                }
                                item("v-all") {
                                    RowCard("Select all usable (${usable.size})", subtitle = "Selects every video that meets the screen and filter requirements") {
                                        videoSel = (videoSel + usable.map { it.key }).distinct(); prefs.videoSources = videoSel
                                        onNotice("${usable.size} videos selected", false)
                                    }
                                }
                                if (videoSel.isNotEmpty()) item("v-none") {
                                    RowCard("Clear selection (${videoSel.size})", subtitle = "Deselect all videos") {
                                        confirm = ConfirmSpec("Clear selection", "All ${videoSel.size} selected videos will be deselected.", "Clear", destructive = true) {
                                            videoSel = emptyList(); prefs.videoSources = emptyList()
                                        }
                                    }
                                }
                            }
                            if (list.isEmpty() && all.isNotEmpty()) item("v-none-shown") {
                                EmptyState(QuickIcons.Movie, if (view == 1) "Nothing selected yet" else "No usable videos here", if (view == 1) "Please switch to Usable only or All videos to make a selection." else "Please switch to All videos to view the reason each video is unavailable.", Tone.Teal)
                            }
                            if (all.isEmpty()) item("v-empty") {
                                if (videoFolder.isNotEmpty() && !VideoLibrary.hasPermission(context)) {
                                    EmptyState(Icons.Filled.Lock, "Video access not granted", "Please use the app folder or select a folder again.", Tone.Amber)
                                } else {
                                    EmptyState(QuickIcons.Movie, "No videos yet", "Please copy .mp4 files of 30 seconds or less into ${VideoLibrary.folderLabel(context, videoFolder)}, then select Refresh video list.", Tone.Teal)
                                }
                            }
                            list.forEach { v ->
                                item("v-${v.key}") {
                                    val on = v.key in videoSel
                                    val seconds = (v.durationMs + 500) / 1000
                                    val fits = v.fitsScreen(screenAspect)
                                    val block = blockOf(v)
                                    RowCard(
                                        v.name,
                                        leading = {
                                            val th = rememberVideoThumb(v)
                                            Box(Modifier.size(width = 64.dp, height = 36.dp).clip(RoundedCornerShape(8.dp)).background(Ui.BgBottom), contentAlignment = Alignment.Center) {
                                                if (th != null) androidx.compose.foundation.Image(th, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                            }
                                        },
                                        subtitle = when {
                                            v.unreadable -> "Unable to read this file"
                                            v.tooLong -> "${seconds} s is over the ${VideoLibrary.MAX_MS / 1000} s limit"
                                            !v.landscape -> "${v.width}x${v.height} is not landscape. Only landscape videos are allowed"
                                            v.tooLarge(screenW, screenH) -> "${v.width}x${v.height} is larger than the ${screenW}x${screenH} screen and is too heavy to play smoothly"
                                        v.tooLarge(screenW, screenH) -> "${v.width}x${v.height} is larger than the ${screenW}x${screenH} screen and too heavy to play smoothly"
                                        !fits -> "${v.width}x${v.height} does not match the ${screenW}x${screenH} screen, so it would be stretched, cropped or letterboxed"
                                            videoOnly120 && !v.is120 -> "${"%.0f".format(v.fps)} fps. The 120 fps filter is active"
                                            else -> "${seconds} s, ${v.width}x${v.height}, ${if (v.fps > 0) "%.0f fps, ".format(v.fps) else ""}${"%.1f".format(v.sizeBytes / 1_048_576.0)} MB" +
                                                if (v.width < screenW) ". Smaller than the screen. It is scaled up proportionally" else ""
                                        },
                                        trailing = {
                                            if (block != null) Pill(block, if (block == "NOT 120 FPS") Ui.Warn else Ui.Bad)
                                            else Row(verticalAlignment = Alignment.CenterVertically) {
                                                Pill("FITS SCREEN", Ui.Good, Modifier.padding(end = 16.dp))
                                                Checkbox(on)
                                            }
                                        },
                                    ) {
                                        if (block != null) onNotice(
                                            when (block) {
                                                "TOO LONG" -> "Wallpaper videos must be ${VideoLibrary.MAX_MS / 1000} seconds or shorter. This one is ${seconds} s."
                                                "UNREADABLE" -> "Unable to read this video."
                                                "TOO LARGE" -> "This video is larger than the ${screenW}x${screenH} screen. Please convert it to the screen size."
                                                "NOT 120 FPS" -> "The 120 fps filter is active. This video runs at ${"%.0f".format(v.fps)} fps."
                                                else -> "Only landscape videos that match the ${screenW}x${screenH} screen shape are allowed. This one is ${v.width}x${v.height}."
                                            }, true,
                                        ) else {
                                            videoSel = if (on) videoSel - v.key else videoSel + v.key
                                            prefs.videoSources = videoSel
                                        }
                                    }
                                }
                            }
                            item("v-refresh") { RowCard("Refresh video list", subtitle = "Rescan the folder") { videoTick++ } }
                        }
                    }

                    Section.HIDDEN -> {
                        val apps = allApps
                        val hiddenList = apps?.filter { it.packageName in hiddenApps }.orEmpty()
                        if (apps == null) item("hid-loading") { InfoCard("Loading apps...") }
                        else if (hiddenList.isEmpty()) item("hid-none") {
                            EmptyState(Icons.Filled.Lock, "No hidden apps", "Please press and hold an app in All apps and choose Hide from All apps.", Tone.Pink)
                        } else {
                            item("hid-all") { RowCard("Show all (${hiddenList.size})", subtitle = "Restore every hidden application to All apps") { onHiddenApps(emptySet()) } }
                            hiddenList.forEach { app ->
                                item("hid-${app.packageName}") {
                                    RowCard(app.label, subtitle = app.packageName,
                                        leading = { androidx.compose.foundation.Image(bitmap = rememberAppIcon(app) ?: androidx.compose.ui.graphics.ImageBitmap(1, 1), contentDescription = null, modifier = Modifier.size(32.dp)) },
                                        trailing = { Pill("SHOW", Ui.Good) }) { onHiddenApps(hiddenApps - app.packageName) }
                                }
                            }
                        }
                    }

                    Section.HOME -> {
                        item("home-dock") {
                            Column(Modifier.fillMaxWidth().background(Ui.Card, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Dock size", color = Ui.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Pill(DOCK_SIZES.firstOrNull { it.first == dockScale }?.second ?: "Custom", Ui.Accent)
                                }
                                Text("Adjusts the size of the dock and its icons", color = Ui.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    DOCK_SIZES.forEach { (v, name) ->
                                        ChipButton(name, selected = v == dockScale, modifier = Modifier.weight(1f)) { dockScale = v; prefs.dockScale = v }
                                    }
                                }
                            }
                        }
                        item("home-top") {
                            Column(Modifier.fillMaxWidth().background(Ui.Card, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Top bar size", color = Ui.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    Pill(DOCK_SIZES.firstOrNull { it.first == topScale }?.second ?: "Custom", Ui.Accent)
                                }
                                Text("Adjusts the clock, date, readout and quick buttons together", color = Ui.TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    DOCK_SIZES.forEach { (v, name) ->
                                        ChipButton(name, selected = v == topScale, modifier = Modifier.weight(1f)) { topScale = v; prefs.topScale = v }
                                    }
                                }
                            }
                        }
                        item("home-stats") {
                            RowCard(
                                "CPU and RAM readout",
                                subtitle = "Displays CPU and RAM usage below the date",
                                trailing = { ToggleSwitch(showStats) },
                            ) {
                                showStats = !showStats
                                prefs.showStats = showStats
                            }
                        }
                    }

                    Section.WALLPAPER -> {
                        item("wp-built-in") { SectionLabel("Built-in") }
                        Wallpapers.presets.forEach { p ->
                            item("wp-${p.id}") {
                                RowCard(
                                    p.name,
                                    trailing = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (wallpaper == "preset:${p.id}") Pill("SELECTED", Ui.Good, Modifier.padding(end = 16.dp))
                                            Box(Modifier.size(width = 84.dp, height = 44.dp).background(p.brush, RoundedCornerShape(8.dp)))
                                        }
                                    },
                                ) { onWallpaper("preset:${p.id}") }
                            }
                        }
                        item("wp-yours") { SectionLabel("Your images") }
                        if (wallpaperFiles.isEmpty()) {
                            item("wp-empty") { EmptyState(Icons.Filled.Star, "No images yet", "Please copy .jpg, .png or .webp files into $wallpaperDir, then select Refresh image list. Landscape images of 1920x1080 or larger are recommended.", Tone.Pink) }
                        }
                        wallpaperFiles.forEach { f ->
                            item("wp-f-${f.name}") {
                                RowCard(
                                    f.name,
                                    subtitle = "${f.length() / 1024} KB",
                                    trailing = { if (wallpaper == "file:${f.name}") Pill("SELECTED", Ui.Good) },
                                ) { onWallpaper("file:${f.name}") }
                            }
                        }
                        item("wp-refresh") { RowCard("Refresh image list", subtitle = "Rescan the folder") { wallpaperTick++ } }
                    }

                    Section.AUTOMATION -> {
                        item("emergency") {
                            RowCard(
                                "All automation",
                                subtitle = if (enabled) "Enabled. Disable to stop all automation immediately" else "Disabled. Start at boot and keep on top are inactive",
                                trailing = { ToggleSwitch(enabled) },
                            ) {
                                enabled = !enabled
                                prefs.enabled = enabled
                                KeepHome.sync(context)
                                onNotice(if (enabled) "Automation enabled" else "Automation disabled. Start at boot and keep on top are inactive", false)
                            }
                        }
                        item("boot") {
                            Toggle("Start launcher at boot", "Opens the launcher shortly after the device starts", autoStart) {
                                autoStart = !autoStart
                                prefs.autoStartOnBoot = autoStart
                            }
                        }
                        item("keep") {
                            Toggle("Keep my launcher on top", "Returns to this launcher when the system launcher appears", keepOnTop) {
                                if (!keepOnTop && savedStock.isBlank()) {
                                    onNotice("Please select the stock launcher first (Stock launcher section), then enable this option.", true)
                                } else {
                                    keepOnTop = !keepOnTop
                                    prefs.keepOnTop = keepOnTop
                                    KeepHome.sync(context)
                                }
                            }
                        }
                        item("pause") {
                            val paused = pausedUntil > System.currentTimeMillis()
                            RowCard(
                                if (paused) "Resume keep-home" else "Pause keep-home for 10 minutes",
                                subtitle = if (paused) "Paused until ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(pausedUntil))}" else "Allows temporary use of the stock launcher",
                                trailing = { if (paused) Pill("PAUSED", Ui.Warn) },
                            ) {
                                pausedUntil = if (paused) 0L else System.currentTimeMillis() + Prefs.PAUSE_MS
                                prefs.pausedUntil = pausedUntil
                            }
                        }
                        item("safety") {
                            InfoCard("No other application is ever stopped. The feature disables itself after 5 relaunches within 30 seconds.")
                        }
                    }

                    Section.LAUNCHER -> {
                        item("current") {
                            InfoCard("Current: ${savedStock.ifEmpty { "(not set)" }}", highlight = savedStock.isNotEmpty())
                        }
                        if (homeApps.isEmpty()) item("none") { EmptyState(Icons.Filled.Home, "No other home apps detected", "Please enter the package of the stock launcher manually below.", Tone.Violet) }
                        homeApps.forEach { pkg ->
                            item(pkg) {
                                RowCard(
                                    pkg, subtitle = "Detected launcher",
                                    trailing = { if (pkg == savedStock) Pill("SELECTED", Ui.Good) },
                                ) { saveStock(pkg) }
                            }
                        }
                        if (!editing) {
                            item("manual") { RowCard("Enter a package manually", subtitle = "For a launcher that was not detected") { editing = true } }
                        } else {
                            item("field") { PackageField(stockText) { stockText = it } }
                            item("save") { RowCard("Save package", subtitle = "Saves $stockText") { if (saveStock(stockText)) editing = false } }
                        }
                    }

                    Section.PERMISSIONS -> {
                        item("overlay") {
                            PermissionRow("Display over other apps", "Allows the launcher to start from the background", overlayOk, "GRANTED", "NOT GRANTED", Ui.Bad) {
                                openPermission("Display over other apps", listOf(
                                    "overlay page for this app" to Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri),
                                    "overlay page" to Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                                    "generic settings" to Intent(Settings.ACTION_SETTINGS),
                                ))
                            }
                        }
                        item("usage") {
                            PermissionRow("Usage access", "Allows keep-home to detect the foreground application", usageOk, "GRANTED", "NOT GRANTED", Ui.Bad) {
                                openPermission("Usage access", listOf(
                                    "usage page for this app" to Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, pkgUri),
                                    "usage page" to Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                                    "generic settings" to Intent(Settings.ACTION_SETTINGS),
                                ))
                            }
                        }
                        item("notif") {
                            PermissionRow("Notifications", "Displays the notification Android requires for the service", notifOk, "ALLOWED", "BLOCKED", Ui.Warn) {
                                openPermission("App notifications", listOf(
                                    "app notification page" to Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                                    "app details" to Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri),
                                    "generic settings" to Intent(Settings.ACTION_SETTINGS),
                                ))
                            }
                        }
                        item("tip") { InfoCard("Select a row to open its page. The status refreshes on return.") }
                    }

                    Section.DIAGNOSTICS -> {
                        val rows = diagnostics
                        if (rows == null) item("loading") { InfoCard("Reading package information...") }
                        else {
                            item("refresh") { RowCard("Refresh", subtitle = "Read the package information again") { diagnostics = null } }
                            items(rows.size) { i ->
                                val line = rows[i]
                                if (line.startsWith("# ")) SectionLabel(line.removePrefix("# "))
                                else MonoLine(line)
                            }
                        }
                    }
                }
            }
            }
        }
    }
    confirm?.let { ConfirmDialog(it) { confirm = null } }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavItem(title: String, icon: ImageVector, tone: Tone, selected: Boolean, requester: FocusRequester?, onFocus: () -> Unit) {
    Surface(
        onClick = onFocus,
        modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth().mouseClick(requester, onFocus).onFocusChanged { if (it.isFocused || it.hasFocus) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Ui.Accent.copy(alpha = 0.16f) else Color.Transparent,
            contentColor = if (selected) Ui.Accent else Ui.TextSecondary,
            focusedContainerColor = Ui.Accent,
            focusedContentColor = Color(0xFF06101F),
        ),
        border = focusBorder(12),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tone, size = 32)
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

// Focusable so the D-pad can scroll through explanatory text.
@Composable
private fun InfoCard(text: String, highlight: Boolean = false) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text,
        fontSize = 13.sp,
        color = if (highlight) Ui.Good else Ui.TextSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(if (focused) Ui.CardFocused else Ui.Card.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

@Composable
private fun MonoLine(text: String) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = Ui.TextPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(if (focused) Ui.CardFocused else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun PackageField(value: String, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Ui.TextPrimary, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(Ui.Accent),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(requester)
            .onFocusChanged { focused = it.isFocused }
            .border(BorderStroke(if (focused) 3.dp else 1.dp, if (focused) Ui.Accent else Ui.TextSecondary), RoundedCornerShape(12.dp))
            .padding(16.dp),
    )
}
