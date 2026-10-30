package com.example.projectorlauncher

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.TextureView
import android.view.animation.LinearInterpolator
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * Looping video wallpaper built from two stacked TextureViews and two muted players. While one plays,
 * the other sits prepared on the next clip's first frame. Shortly before the playing clip ends the
 * standby starts and its view cross-fades in, so a loop (the "next clip" of a single video is itself)
 * and a change of clip are both a dissolve rather than a cut. Audio is disabled outright, buffers are
 * small, and everything is released when the screen stops, so a fullscreen app keeps the hardware decoder.
 */
@OptIn(UnstableApi::class)
class VideoLoop(
    private val context: Context,
    private val views: Array<TextureView>,
    private val sources: List<Uri>,
    private val speed: Float = 1f,
    startActive: Boolean = true,
    private val smooth: Boolean = true,
) {
    private var active = startActive      // whether the video should be moving
    private var rate = if (startActive) speed else 0f
    private var rampTarget = rate
    private val handler = Handler(Looper.getMainLooper())
    private val players = arrayOfNulls<ExoPlayer>(2)
    private val held = intArrayOf(0, 0)
    private var front = 0
    private var transitioning = false
    private var shown = false
    private var released = false
    private var errors = 0
    /** Set when a second decoder could not start: the wallpaper then cuts between clips instead of dissolving. */
    private var singleMode = false

    fun start() {
        if (sources.isEmpty()) return
        views.forEach { it.animate().cancel(); it.alpha = 0f }
        // One decoder only. The second one is created shortly before a dissolve and released right after it,
        // which keeps memory and CPU low on small devices.
        players[front] = buildPlayer(front)
        load(front, 0)
        players[front]?.playWhenReady = active
        handler.post(monitor)
    }

    private fun createStandby() {
        val slot = 1 - front
        if (players[slot] != null || singleMode || released) return
        players[slot] = buildPlayer(slot)
        load(slot, nextOf(held[front]))
    }

    private fun releaseStandby() {
        val slot = 1 - front
        players[slot]?.release()
        players[slot] = null
    }

    /** No dissolve possible: jump straight to the next clip (or back to the start of a single clip). */
    private fun hardCut() {
        val p = players[front] ?: return
        releaseStandby()
        if (sources.size == 1) {
            p.seekTo(0)
        } else {
            load(front, nextOf(held[front]))
        }
        p.setPlaybackSpeed(if (rate > 0f) rate else speed)
        p.playWhenReady = active
    }

    /**
     * Pauses or resumes the wallpaper. Pausing is a short controlled slowdown to a cadence-based cutoff speed
     * (the slowest speed at which a 24-30 fps source still looks like motion) followed by a real pause with
     * playWhenReady. Resuming starts again at that cutoff and ramps up to the wallpaper speed. The speed
     * parameter never creates intermediate frames; it only changes how long each source frame is held.
     */
    fun setActive(value: Boolean) {
        if (active == value || released) return
        active = value
        requestedAt = SystemClock.elapsedRealtime()
        applyActive()
    }

    private var rampFrom = 0f
    private var rampStart = 0L
    private var rampLength = 1L
    private var pauseAtEnd = false
    private var suspended = false
    /** Bumped for every new ramp so a stale completion can never pause (or resume) a newer request. */
    private var rampGen = 0
    private var requestedAt = 0L
    private var resumeAt = 0L

    /**
     * Slowest useful moving speed for the clip: 1000 / (fps * MAX_FRAME_GAP_MS), clamped to 0.25..0.5.
     * About 0.33x at 30 fps and 0.42x at 24 fps; [CUTOFF_UNKNOWN] when the frame rate is not known.
     */
    private fun cutoffSpeed(): Float {
        val fps = players[front]?.videoFormat?.frameRate ?: return CUTOFF_UNKNOWN
        if (fps <= 0f) return CUTOFF_UNKNOWN
        return (1000f / (fps * MAX_FRAME_GAP_MS)).coerceIn(CUTOFF_MIN, CUTOFF_MAX)
    }

    /**
     * Hard pause while the launcher is not in front (another app on top, screen off, a floating window).
     * Immediate, no ramp: nothing should decode behind someone else's app. On release the video simply
     * continues if it should be playing; if it should be paused (icons showing) it stays paused.
     */
    fun setHeld(value: Boolean) {
        if (suspended == value || released) return
        suspended = value
        rampGen++
        handler.removeCallbacks(ramp)
        if (value) {
            players.forEach { it?.playWhenReady = false; it?.setPlaybackSpeed(speed) }
            rate = if (active) speed else 0f
        } else if (active) {
            rate = speed
            players[front]?.setPlaybackSpeed(speed)
            players[front]?.playWhenReady = true
            if (transitioning) players[1 - front]?.playWhenReady = true
        }
    }

    private fun applyActive() {
        if (suspended || transitioning) return  // suspended: applied on release; transitioning: after the dissolve
        rampGen++
        handler.removeCallbacks(ramp)
        if (!smooth) {
            // No easing: stop and start at once, at the chosen speed.
            rate = if (active) speed else 0f
            players[front]?.setPlaybackSpeed(speed)
            players[front]?.playWhenReady = active
            if (active) resumeAt = SystemClock.elapsedRealtime()
            return
        }
        val cutoff = cutoffSpeed()
        val p = players[front]
        if (active) {
            if (rate == speed) return
            // A paused video restarts at the cutoff (or directly at full speed when that is slower).
            val from = if (rate <= 0f) minOf(cutoff, speed) else rate
            if (rate <= 0f) { p?.setPlaybackSpeed(from); resumeAt = SystemClock.elapsedRealtime() }
            p?.playWhenReady = true
            startRamp(from, speed, RESUME_MS, speed - cutoff, false)
        } else {
            if (rate <= 0f) return
            // Stop from a higher speed than the resume start: the slow, sparse-frame tail is what looked laggy.
            val stop = minOf(cutoff * PAUSE_STOP_FACTOR, speed)
            if (rate <= stop) { pauseNow(); return }        // already slow enough: pause directly
            startRamp(rate, stop, PAUSE_MS, speed - stop, true)
        }
    }

    /** Pause in place: keep the displayed frame, then restore the normal speed parameter while paused. */
    private fun pauseNow() {
        val p = players[front]
        p?.playWhenReady = false
        p?.setPlaybackSpeed(speed)
        rate = 0f
        logTiming("paused")
    }

    private fun startRamp(from: Float, target: Float, fullMs: Long, span: Float, pause: Boolean) {
        rate = from
        rampFrom = from
        rampTarget = target
        pauseAtEnd = pause
        rampStart = SystemClock.elapsedRealtime()
        // Scale the full-range duration to the remaining speed distance, so a reversal is not slower than needed.
        rampLength = maxOf(MIN_RAMP_MS, (fullMs * Math.abs(target - from) / span.coerceAtLeast(0.01f)).toLong())
        logTiming("ramp ${if (pause) "down" else "up"} ${"%.2f".format(from)}x -> ${"%.2f".format(target)}x over ${rampLength} ms")
        handler.post(ramp)
    }

    private fun logTiming(what: String) {
        if (debuggable) Log.i("VideoRamp", "$what (+${SystemClock.elapsedRealtime() - requestedAt} ms after the request)")
    }

    private val ramp = object : Runnable {
        override fun run() {
            if (released) return
            val gen = rampGen
            // Progress comes from the monotonic clock: a late callback samples the curve where it is now.
            val t = ((SystemClock.elapsedRealtime() - rampStart).toFloat() / rampLength).coerceIn(0f, 1f)
            // Slowing down brakes late (progress ~ t^2); starting up leaves the cutoff quickly (1-(1-t)^2).
            val eased = if (active) 1f - (1f - t) * (1f - t) else t * t
            rate = rampFrom + (rampTarget - rampFrom) * eased
            val p = players[front]
            if (t < 1f) {
                p?.setPlaybackSpeed(rate)
                handler.postDelayed(this, RAMP_TICK_MS)
                return
            }
            if (gen != rampGen) return
            if (pauseAtEnd) pauseNow() else { rate = rampTarget; p?.setPlaybackSpeed(rate); logTiming("at speed") }
        }
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        views.forEach { it.animate().cancel() }
        players.forEach { it?.release() }
        players.fill(null)
    }

    private fun nextOf(i: Int) = (i + 1) % sources.size

    private fun buildPlayer(slot: Int): ExoPlayer =
        ExoPlayer.Builder(context)
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_500, 4_000, 250, 500).build())
            .build()
            .apply {
                volume = 0f
                trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                repeatMode = Player.REPEAT_MODE_OFF
                setPlaybackSpeed(speed)
                setVideoTextureView(views[slot])
                if (debuggable) setVideoFrameMetadataListener(pacingProbe(slot))
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        if (slot == front && !shown) {
                            shown = true
                            views[slot].animate().alpha(1f).setDuration(FADE_IN_MS).setInterpolator(LinearInterpolator()).start()
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        // Safety net if the cross-fade window was missed: switch to the standby, or cut.
                        if (state == Player.STATE_ENDED && slot == front && !transitioning) {
                            val standby = players[1 - front]
                            if (standby != null && standby.playbackState == Player.STATE_READY) beginTransition() else hardCut()
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) = onError(slot, error)
                })
            }

    private val debuggable = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Debug builds only: logs how many 60 Hz refreshes each source frame is held for. An even count
     * (all 4s, all 8s) means smooth pacing; a mix such as 2/3/3 is judder from a speed that does not
     * divide the display rate.
     */
    private fun pacingProbe(slot: Int) = object : androidx.media3.exoplayer.video.VideoFrameMetadataListener {
        private var lastNs = 0L
        private val holds = HashMap<Long, Int>()
        private var frames = 0
        override fun onVideoFrameAboutToBeRendered(presentationTimeUs: Long, releaseTimeNs: Long, format: androidx.media3.common.Format, mediaFormat: android.media.MediaFormat?) {
            if (slot != front || transitioning) { lastNs = 0; return }
            if (resumeAt != 0L) { Log.i("VideoRamp", "first frame ${SystemClock.elapsedRealtime() - resumeAt} ms after resume"); resumeAt = 0L }
            if (lastNs != 0L) {
                val refreshes = Math.round((releaseTimeNs - lastNs) / 16_666_667.0)
                holds[refreshes] = (holds[refreshes] ?: 0) + 1
                if (++frames % 90 == 0) {
                    Log.i("VideoPacing", "speed=${speed}x  frame hold in 60Hz refreshes (count): ${holds.toSortedMap()}")
                    holds.clear()
                }
            }
            lastNs = releaseTimeNs
        }
    }

    private fun load(slot: Int, index: Int) {
        val p = players[slot] ?: return
        held[slot] = index
        p.setMediaItem(MediaItem.fromUri(sources[index]))
        p.playWhenReady = false
        p.prepare()
    }

    private val monitor = object : Runnable {
        override fun run() {
            if (released) return
            val p = players[front]
            if (active && !suspended && !transitioning && p != null && p.playbackState == Player.STATE_READY && p.duration != C.TIME_UNSET) {
                // Speed stretches or squeezes the clip on screen, so measure the remaining time and the
                // dissolve in wall-clock terms, not media time.
                val wallRemaining = ((p.duration - p.currentPosition) / speed).toLong()
                val fade = fadeFor((p.duration / speed).toLong())
                val standby = players[1 - front]
                if (standby == null && !singleMode && wallRemaining <= fade + LEAD_MS) createStandby()
                if (standby != null && wallRemaining <= fade && standby.playbackState == Player.STATE_READY) beginTransition()
                else if (wallRemaining <= HARD_CUT_MS && (standby == null || standby.playbackState != Player.STATE_READY)) hardCut()
            }
            handler.postDelayed(this, 80)
        }
    }

    private fun fadeFor(duration: Long) = minOf(CROSSFADE_MS, duration / 3)

    /**
     * The two views are stacked in a fixed order (index [TOP] is drawn over the other), so the dissolve
     * always fades the top view: in when it is the incoming clip, out when it is the outgoing one. The
     * lower view simply sits at full opacity underneath.
     */
    private fun beginTransition() {
        val incoming = 1 - front
        val outgoing = front
        val inPlayer = players[incoming] ?: return
        transitioning = true
        inPlayer.setPlaybackSpeed(if (rate > 0f) rate else speed)   // carry the current (possibly mid-ramp) speed over
        inPlayer.playWhenReady = true
        val duration = fadeFor(((players[outgoing]?.duration?.takeIf { it != C.TIME_UNSET } ?: (CROSSFADE_MS * 3)) / speed).toLong())
        views.forEach { it.animate().cancel() }
        val top = views[TOP]
        if (incoming == TOP) {
            top.alpha = 0f
            top.animate().alpha(1f)
        } else {
            views[incoming].alpha = 1f
            top.animate().alpha(0f)
        }
        top.animate().setDuration(duration).setInterpolator(LinearInterpolator())
            .withEndAction { finishTransition(outgoing, incoming) }.start()
    }

    private fun finishTransition(outgoing: Int, incoming: Int) {
        if (released) return
        front = incoming
        views[incoming].alpha = 1f
        views[outgoing].alpha = 0f
        // The finished clip's decoder is released; a new one is created again before the next dissolve.
        players[outgoing]?.release()
        players[outgoing] = null
        transitioning = false
        if (!active) applyActive()
    }

    private fun onError(slot: Int, error: PlaybackException) {
        Log.w(TAG, "player $slot failed on ${sources[held[slot]]}", error)
        if (released) return
        if (slot != front) {
            // The second decoder could not start (common on small devices). Keep playing with one, cut between clips.
            singleMode = true
            if (transitioning) {
                views.forEach { it.animate().cancel() }
                views[front].alpha = 1f
                views[slot].alpha = 0f
                transitioning = false
            }
            players[slot]?.release()
            players[slot] = null
            return
        }
        if (++errors > sources.size * 2 + 2) return    // give up: the static wallpaper stays visible
        load(slot, nextOf(held[slot]))
        if (slot == front) {
            players[slot]?.playWhenReady = true
            if (!shown) return
        }
    }

    companion object {
        private const val TAG = "VideoLoop"
        private const val TOP = 1
        private const val PAUSE_MS = 2_200L         // full-range slowdown
        private const val PAUSE_STOP_FACTOR = 1.15f  // the pause lands at cutoff x this
        private const val RESUME_MS = 600L
        private const val MIN_RAMP_MS = 150L
        private const val MAX_FRAME_GAP_MS = 100f   // longest gap between distinct frames that still reads as motion
        private const val CUTOFF_MIN = 0.25f
        private const val CUTOFF_MAX = 0.50f
        private const val CUTOFF_UNKNOWN = 0.4f
        private const val RAMP_TICK_MS = 40L
        /** How long before a dissolve the second decoder is created, so it is ready with its first frame. */
        private const val LEAD_MS = 3_000L
        private const val HARD_CUT_MS = 150L
        const val CROSSFADE_MS = 1_200L
        const val FADE_IN_MS = 900L
    }
}

@Composable
fun VideoWallpaper(sources: List<String>, speed: Float = 1f, active: Boolean = true, smooth: Boolean = true, modifier: Modifier = Modifier.fillMaxSize()) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val views = remember { Array(2) { TextureView(context).apply { alpha = 0f; keepScreenOn = true } } }
    var running by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) running = true
            if (event == Lifecycle.Event.ON_STOP) running = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val currentActive by rememberUpdatedState(active)
    var loopRef by remember { mutableStateOf<VideoLoop?>(null) }
    DisposableEffect(running, sources, speed, smooth) {
        val loop = if (running) VideoLoop(context, views, sources.map(Uri::parse), speed, currentActive, smooth).also { it.start() } else null
        loopRef = loop
        onDispose { loop?.release(); loopRef = null }
    }
    LaunchedEffect(active, loopRef) { loopRef?.setActive(active) }

    // Pause the instant the launcher stops being the foreground (PAUSE), long before STOP releases it.
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            if (event == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed, loopRef) { loopRef?.setHeld(!resumed) }

    Box(modifier) {
        AndroidView(factory = { views[0] }, modifier = Modifier.fillMaxSize())
        AndroidView(factory = { views[1] }, modifier = Modifier.fillMaxSize())
    }
}
