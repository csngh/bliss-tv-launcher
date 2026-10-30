package com.example.projectorlauncher

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Plain translucent glass: a light tint over whatever is behind, and a barely-there hairline edge.
 * No sheen, no rim light, no highlights. [tint] is how dark the body is.
 */
fun Modifier.glass(shape: Shape, tint: Float = 0.30f): Modifier = this
    .clip(shape)
    .background(Color(0xFF0B1224).copy(alpha = tint))
    .drawWithContent {
        drawContent()
        drawOutline(shape.createOutline(size, layoutDirection, this), Color.White.copy(alpha = 0.14f), style = Stroke(1.dp.toPx()))
    }

/**
 * A glass panel. With [backdrop] set (a static wallpaper key) it blurs a copy of that wallpaper behind the
 * panel, aligned to the screen, which is what makes it read as frosted glass instead of a tinted box.
 * Video cannot be sampled from behind, so pass null there and rely on the stronger [tint].
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape,
    backdrop: String? = null,
    tint: Float = if (backdrop != null) 0.40f else 0.45f,
    content: @Composable BoxScope.() -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val config = LocalConfiguration.current
    val density = LocalDensity.current.density
    val screen = IntSize((config.screenWidthDp * density).roundToInt(), (config.screenHeightDp * density).roundToInt())
    Box(modifier.onGloballyPositioned { origin = it.positionInWindow() }.glass(shape, tint)) {
        if (backdrop != null && Build.VERSION.SDK_INT >= 31 && screen.width > 0 && !LowMem.isLow(androidx.compose.ui.platform.LocalContext.current)) {
            Box(Modifier.matchParentSize().clipToBounds()) {
                Box(
                    Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(Constraints.fixed(screen.width, screen.height))
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                placeable.place(-origin.x.roundToInt(), -origin.y.roundToInt())
                            }
                        }
                        .blur(26.dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded),
                ) { WallpaperLayer(backdrop) }
            }
            // The tint goes above the blurred copy, otherwise a bright wallpaper washes the panel out.
            Box(Modifier.matchParentSize().background(Color(0xFF0B1224).copy(alpha = tint)))
        }
        content()
    }
}
