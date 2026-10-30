package com.example.projectorlauncher

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Tracks user activity for the idle fade. [poke] is called for every key, touch and mouse event
 * (cheap: one volatile write), and the home screen polls [last] a couple of times a second.
 */
object IdleState {
    @Volatile var last = SystemClock.uptimeMillis()
    var idle by mutableStateOf(false)

    fun poke() {
        last = SystemClock.uptimeMillis()
        if (idle) idle = false
    }

    fun idleFor() = SystemClock.uptimeMillis() - last
}

val IDLE_CHOICES = listOf(0, 10, 30, 60, 120, 300, 600)

fun idleLabel(seconds: Int): String = when {
    seconds <= 0 -> "Off"
    seconds < 60 -> "$seconds seconds"
    seconds == 60 -> "1 minute"
    else -> "${seconds / 60} minutes"
}

/** When the system last reported real mouse movement (hover or drag). Layout changes under a resting pointer do not count. */
object MouseActivity {
    @Volatile var lastMove = 0L
    fun moved() { lastMove = SystemClock.uptimeMillis() }
    fun recently(windowMs: Long = 200) = SystemClock.uptimeMillis() - lastMove < windowMs
}

/**
 * Tracks the select key (centre or Enter). A long press opens a menu while the key is still down, and the
 * remaining repeats and the release must not reach the menu, or its first row is activated and the menu
 * closes at once. [swallowUntilRelease] consumes the select key until it is let go.
 */
object SelectKey {
    @Volatile var held = false
    @Volatile private var swallowing = false
    fun isSelect(code: Int) = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
    fun swallowUntilRelease() { swallowing = held }

    /** True when the event must be consumed. */
    fun filter(event: android.view.KeyEvent): Boolean {
        if (!isSelect(event.keyCode)) return false
        val up = event.action == android.view.KeyEvent.ACTION_UP
        held = !up
        if (!swallowing) return false
        if (up) swallowing = false
        return true
    }
}
