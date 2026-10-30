package com.example.projectorlauncher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

object Ui {
    // Surfaces and the accent: one fixed Midnight theme (see ThemeTone).
    val BgTop: Color get() = ThemeTone.surface(0.60f, 0.16f)
    val BgBottom: Color get() = ThemeTone.surface(0.55f, 0.065f)
    val Card: Color get() = ThemeTone.surface(0.50f, 0.20f)
    val CardFocused: Color get() = ThemeTone.surface(0.55f, 0.33f)
    val Accent: Color get() = ThemeTone.accent
    val Good = Color(0xFF66BB6A)
    val Bad = Color(0xFFEF5350)
    val Warn = Color(0xFFFFC857)
    val TextPrimary = Color(0xFFF4F6FB)
    val TextSecondary = Color(0xFF9AA6C4)
}

/**
 * Mouse support. The TV Surface ignores pointer taps, so this adds them. Only an actual click does
 * anything: the item takes focus and runs [onClick]. Moving or resting the pointer never changes focus.
 */
fun Modifier.mouseClick(requester: FocusRequester? = null, onClick: (() -> Unit)? = null, onLongClick: (() -> Unit)? = null): Modifier = composed {
    val r = requester ?: remember { FocusRequester() }
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    var coords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val hoverScope = androidx.compose.runtime.rememberCoroutineScope()
    /** True only when this item is completely inside its list's viewport, so focusing it can never scroll the list. */
    fun fullyVisible(): Boolean {
        val c = coords ?: return false
        if (!c.isAttached) return false
        val me = c.boundsInWindow()
        val centre = me.center
        // Every scrolling list that contains this item's centre must contain the whole item.
        return ScrollAreas.rects.values.none { area ->
            area.contains(centre) && (me.left < area.left - 1f || me.top < area.top - 1f || me.right > area.right + 1f || me.bottom > area.bottom + 1f)
        }
    }
    // Focus asks the surrounding list to scroll the focused item (and its enlarged outline) fully into view. When the
    // mouse caused the focus, answer that the item is already in view (a single point at its centre), so nothing scrolls.
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    val noScrollOnHover = remember {
        object : androidx.compose.foundation.relocation.BringIntoViewResponder {
            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
            override fun calculateRectForParent(localRect: androidx.compose.ui.geometry.Rect): androidx.compose.ui.geometry.Rect =
                if (HoverFocus.recent()) androidx.compose.ui.geometry.Rect(localRect.center, localRect.center) else localRect
            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
            override suspend fun bringChildIntoView(localRect: () -> androidx.compose.ui.geometry.Rect?) {}
        }
    }
    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    val responder = Modifier.bringIntoViewResponder(noScrollOnHover)
    responder
        .focusRequester(r)
        .onGloballyPositioned { coords = it }
        .pointerInput(Unit) {
            // Hover moves the highlight here, the way the remote moves focus. The system must have just reported
            // real mouse movement, so a list scrolling under a resting pointer never steals focus.
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val ch = e.changes.firstOrNull() ?: continue
                    if (ch.type != PointerType.Mouse) continue
                    // Hover only moves the highlight onto items that are fully on screen. A partly hidden item at the
                    // edge of a list would make the list scroll, and moving the mouse must never scroll anything.
                    if ((e.type == PointerEventType.Enter || e.type == PointerEventType.Move) && MouseActivity.recently() && fullyVisible()) {
                        ScrollAreas.freeze(hoverScope)
                        runCatching { r.requestFocus() }
                    }
                }
            }
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { runCatching { r.requestFocus() }; click?.invoke() },
                onLongPress = { offset -> longClick?.let { runCatching { r.requestFocus() }; it() } },
            )
        }
}

/** When the mouse last moved the highlight. A short window after that, focus changes may not scroll any list. */
object HoverFocus {
    @Volatile var at = 0L
    fun mark() { at = android.os.SystemClock.uptimeMillis() }
    fun recent() = android.os.SystemClock.uptimeMillis() - at < 600
}

/** On-screen bounds of every scrolling list, keyed by list. */
object ScrollAreas {
    val rects = java.util.concurrent.ConcurrentHashMap<Any, androidx.compose.ui.geometry.Rect>()
    val states = java.util.concurrent.ConcurrentHashMap<Any, ScrollableState>()

    /**
     * Holds every scrolling list still for a moment. A higher-priority scroll that does nothing makes Android drop any
     * automatic scroll (such as "bring the focused item into view") for its duration. The wheel, a drag and the
     * remote's own scrolling are not affected by this.
     */
    fun freeze(scope: kotlinx.coroutines.CoroutineScope, ms: Long = 450) {
        HoverFocus.mark()
        states.values.forEach { st ->
            scope.launch { st.scroll(androidx.compose.foundation.MutatePriority.UserInput) { kotlinx.coroutines.delay(ms) } }
        }
    }
}

/**
 * Mouse scrolling for a list: the wheel scrolls it, and dragging with the primary button held pulls the
 * content along (a mouse drag never scrolls a Compose list on its own). It watches events on the
 * Initial pass so it works over clickable rows; once a drag passes the touch slop it consumes the
 * pointer, which cancels the click on the row underneath.
 */
fun Modifier.mouseScroll(state: ScrollableState, horizontal: Boolean = false, onWheel: ((Int) -> Unit)? = null): Modifier = composed {
    // Report this scroll area's on-screen bounds, so hover can tell when an item is cut off by it.
    val key = remember { Any() }
    androidx.compose.runtime.DisposableEffect(key) {
        ScrollAreas.states[key] = state
        onDispose { ScrollAreas.rects.remove(key); ScrollAreas.states.remove(key) }
    }
    onGloballyPositioned { ScrollAreas.rects[key] = it.boundsInWindow() }.pointerInput(state, horizontal) {
        val slop = viewConfiguration.touchSlop
        awaitPointerEventScope {
            var start: androidx.compose.ui.geometry.Offset? = null
            var last = androidx.compose.ui.geometry.Offset.Zero
            var dragging = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: continue
                when (event.type) {
                    PointerEventType.Scroll -> {
                        val d = change.scrollDelta
                        if (onWheel != null) { onWheel(if (d.x + d.y > 0) 1 else -1); change.consume(); continue }
                        state.dispatchRawDelta((if (horizontal) d.x + d.y else d.y) * 120f)
                        change.consume()
                    }
                    PointerEventType.Press -> if (change.type == PointerType.Mouse) {
                        start = change.position
                        last = change.position
                        dragging = false
                    }
                    PointerEventType.Move -> if (start != null && change.pressed) {
                        val total = change.position - start
                        if (!dragging && kotlin.math.abs(if (horizontal) total.x else total.y) > slop) dragging = true
                        if (dragging) {
                            val delta = change.position - last
                            state.dispatchRawDelta(-(if (horizontal) delta.x else delta.y))
                            change.consume()
                        }
                        last = change.position
                    }
                    PointerEventType.Release -> {
                        if (dragging) change.consume()
                        start = null
                        dragging = false
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
fun AppBackground(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Ui.BgTop, Ui.BgBottom))),
    ) {
        // Soft accent glow in the corner for depth.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        listOf(Ui.Accent.copy(alpha = 0.10f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(1700f, 0f),
                        radius = 1100f,
                    ),
                ),
        )
        content()
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun focusBorder(radius: Int = 16) = ClickableSurfaceDefaults.border(
    focusedBorder = Border(BorderStroke(2.dp, Ui.Accent), shape = RoundedCornerShape(radius.dp)),
)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun glassFocusBorder(radius: Int = 16) = ClickableSurfaceDefaults.border(
    focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), shape = RoundedCornerShape(radius.dp)),
)

/** Full-width focusable row used for every setting, toggle and shortcut. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RowCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color = Ui.TextPrimary,
    container: Color = Ui.Card,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().mouseClick(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = Ui.TextPrimary,
            focusedContainerColor = Ui.CardFocused,
            focusedContentColor = Ui.TextPrimary,
        ),
        border = focusBorder(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Box(Modifier.size(16.dp))
            }
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(title, color = titleColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle, color = Ui.TextSecondary, fontSize = 11.sp, lineHeight = 15.sp,
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (trailing != null) {
                Box(Modifier.size(16.dp))
                trailing()
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CircleButton(onClick: () -> Unit, requester: FocusRequester? = null, scale: Float = 1f, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.mouseClick(requester, onClick).glass(CircleShape, tint = 0.22f),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White.copy(alpha = 0.22f),
            focusedContentColor = Color.White,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.7f)), shape = CircleShape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.32f),
    ) {
        Box(Modifier.padding(16.dp * scale), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = Ui.Accent,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp),
    )
}

@Composable
internal fun MessageBar(message: UiMessage, modifier: Modifier) {
    val color = if (message.isError) Ui.Bad else Ui.Good
    Box(
        modifier
            .padding(horizontal = 48.dp, vertical = 32.dp)
            .background(Color(0xFF0B1020).copy(alpha = 0.96f), RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Text(message.text, fontSize = 16.sp, color = Ui.TextPrimary, modifier = Modifier.padding(start = 16.dp))
        }
    }
}


@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DialogButton(label: String, primary: Boolean, modifier: Modifier = Modifier, destructive: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.mouseClick(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) (if (destructive) Ui.Bad else Ui.Accent) else Ui.Card,
            contentColor = if (primary) Color(0xFF06101F) else Ui.TextPrimary,
            focusedContainerColor = if (primary) (if (destructive) Color(0xFFFF8A80) else Color.White) else Ui.CardFocused,
            focusedContentColor = if (primary) Color(0xFF06101F) else Ui.TextPrimary,
        ),
        border = focusBorder(12),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ChipButton(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.mouseClick(onClick = onClick),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Ui.Accent.copy(alpha = 0.22f) else Ui.CardFocused.copy(alpha = 0.45f),
            contentColor = if (selected) Ui.Accent else Ui.TextPrimary,
            focusedContainerColor = Ui.Accent,
            focusedContentColor = Color(0xFF06101F),
        ),
        border = focusBorder(12),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}


// ---- Shared pieces added for the design brief -----------------------------------------------------------

const val CREDIT = "Developed by Chetan Singh"

/** Small, quiet credit line. Pinned to the very bottom of a screen by the caller. */
@Composable
fun CreditLine(modifier: Modifier = Modifier) {
    Text(CREDIT, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f), style = androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(0f, 1f), 6f)), modifier = modifier)
}

/**
 * Icon tone: an icon colour and a soft tint. Every tone name now resolves to the one theme tone, taken from the
 * wallpaper, so all icons in the launcher share one colour instead of a rainbow.
 */
class Tone(val icon: Color, val tint: Color) {
    companion object {
        val Blue: Tone get() = ThemeTone.tone
        val Green: Tone get() = ThemeTone.tone
        val Amber: Tone get() = ThemeTone.tone
        val Violet: Tone get() = ThemeTone.tone
        val Teal: Tone get() = ThemeTone.tone
        val Pink: Tone get() = ThemeTone.tone
    }
}

object ThemeTone {
    private var hue by androidx.compose.runtime.mutableStateOf(222f)
    private var sat by androidx.compose.runtime.mutableStateOf(0.55f)
    var accent by androidx.compose.runtime.mutableStateOf(Color(0xFF7AB0FF))
        private set

    /** A dark surface in the theme hue: [satFactor] scales its colour strength, [value] its brightness. */
    fun surface(satFactor: Float, value: Float): Color =
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, (sat * satFactor / 0.55f).coerceIn(0.12f, 0.66f), value)))

    var tone by androidx.compose.runtime.mutableStateOf(Tone(Color(0xFF6DB0F8), Color(0xFF14253B)))
        private set

    /** Bright icon colour and dark tint, in the hue (and strength) of [accent]. */
    init { set(WallpaperAccent.midnight) }      // fixed: the look is always Midnight, whatever the wallpaper is

    private fun set(from: Color) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(from.toArgb(), hsv)
        val strength = hsv[1].coerceIn(0.35f, 0.80f)
        hue = hsv[0]
        sat = strength
        accent = Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], strength * 0.7f, 1f)))
        val icon = android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], strength * 0.75f, 1f))
        val tint = android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], strength * 0.7f, 0.24f))
        tone = Tone(Color(icon), Color(tint))
    }
}

@Composable
fun IconTile(icon: androidx.compose.ui.graphics.vector.ImageVector, tone: Tone, size: Int = 36, modifier: Modifier = Modifier) {
    Box(modifier.size(size.dp).background(tone.tint, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        androidx.tv.material3.Icon(icon, contentDescription = null, tint = tone.icon, modifier = Modifier.size((size * 0.56f).dp))
    }
}

/** Icon, a short headline and one helpful sentence, for lists with nothing in them yet. */
@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, tone: Tone = Tone.Blue) {
    Column(
        Modifier.fillMaxWidth().background(Ui.Card.copy(alpha = 0.5f), RoundedCornerShape(16.dp)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconTile(icon, tone, size = 44)
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ui.TextPrimary, modifier = Modifier.padding(top = 16.dp))
        Text(body, fontSize = 12.sp, color = Ui.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}

class ConfirmSpec(val title: String, val message: String, val action: String, val destructive: Boolean = false, val onConfirm: () -> Unit)

/**
 * Confirmation for hard-to-undo actions. States what will happen, asks "Do you want to continue?", and has a
 * subdued Cancel plus a confirm button named after the action. Back and a click outside both cancel.
 * Cancel holds focus first so a stray OK press cannot confirm.
 */
@Composable
fun ConfirmDialog(spec: ConfirmSpec, onDismiss: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val cancelFocus = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(640.dp).background(Ui.BgTop, RoundedCornerShape(28.dp)).pointerInput(Unit) { detectTapGestures { } }.padding(32.dp),
        ) {
            Text(spec.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ui.TextPrimary)
            Text(spec.message, fontSize = 14.sp, color = Ui.TextSecondary, modifier = Modifier.padding(top = 8.dp))
            Text("Do you want to continue?", fontSize = 14.sp, color = Ui.TextPrimary, modifier = Modifier.padding(top = 16.dp))
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.End) {
                DialogButton("Cancel", primary = false, modifier = Modifier.focusRequester(cancelFocus), onClick = onDismiss)
                Box(Modifier.width(16.dp))
                DialogButton(spec.action, primary = true, destructive = spec.destructive) { spec.onConfirm(); onDismiss() }
            }
        }
    }
}

/** Fades the top and bottom edge of a scroll area, each only while more content lies in that direction. */
fun Modifier.fadingEdges(state: androidx.compose.foundation.gestures.ScrollableState, height: Int = 28): Modifier = composed {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val animate = remember { animationsEnabled(ctx) }
    val top by androidx.compose.animation.core.animateFloatAsState(
        if (state.canScrollBackward) 1f else 0f, if (animate) androidx.compose.animation.core.tween(180) else androidx.compose.animation.core.snap(), label = "fadeTop",
    )
    val bottom by androidx.compose.animation.core.animateFloatAsState(
        if (state.canScrollForward) 1f else 0f, if (animate) androidx.compose.animation.core.tween(180) else androidx.compose.animation.core.snap(), label = "fadeBottom",
    )
    this
        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val h = height.dp.toPx()
            if (top > 0f) drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 1f - top), Color.Black), startY = 0f, endY = h),
                size = androidx.compose.ui.geometry.Size(size.width, h), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
            if (bottom > 0f) drawRect(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black, Color.Black.copy(alpha = 1f - bottom)), startY = size.height - h, endY = size.height),
                topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }
}

/** False when the system "remove animations" setting is on; animations then snap instead of easing. */
fun animationsEnabled(context: android.content.Context): Boolean =
    android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f


/** A small on/off switch. The whole row is the button, so this only shows the state. */
@Composable
fun ToggleSwitch(on: Boolean) {
    val knob by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, androidx.compose.animation.core.tween(160), label = "knob")
    val track by androidx.compose.animation.animateColorAsState(if (on) Ui.Accent else Ui.TextSecondary.copy(alpha = 0.35f), androidx.compose.animation.core.tween(160), label = "track")
    Box(Modifier.size(width = 48.dp, height = 28.dp).background(track, RoundedCornerShape(14.dp)).padding(3.dp)) {
        Box(
            Modifier.size(22.dp).graphicsLayer { translationX = knob * 20.dp.toPx() }
                .background(if (on) Color(0xFF06101F) else Color.White, androidx.compose.foundation.shape.CircleShape),
        )
    }
}

/**
 * How a list reveals the focused item: the way Android's own lists do. An item that is already inside
 * the viewport (give or take [slackPx], for its enlarged outline) causes no scroll at all; otherwise the
 * list moves just far enough to bring it to the nearest edge. Compose's default instead pulls an item
 * towards the middle of the viewport, which made the focus appear to hold still while the list moved.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun minimalBringIntoView(slackPx: Float = 0f, marginPx: Float = 0f) = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val trailing = offset + size
        return when {
            offset >= marginPx - slackPx && trailing <= containerSize - marginPx + slackPx -> 0f
            offset < 0f && trailing > containerSize -> 0f
            offset < marginPx -> offset - marginPx
            else -> trailing - containerSize + marginPx
        }
    }
}
