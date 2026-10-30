package com.example.projectorlauncher

import android.content.Intent
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private var homePresses by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent { LauncherApp(homePresses) }
    }

    private val swallowedKeys = mutableSetOf<Int>()
    private var swallowTouch = false

    // A press that wakes a faded home screen only wakes it; it must not also open whatever is focused.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (SelectKey.filter(event)) return true
        if (event.action == KeyEvent.ACTION_UP && swallowedKeys.remove(event.keyCode)) return true
        val wasIdle = IdleState.idle
        IdleState.poke()
        if (wasIdle && event.action == KeyEvent.ACTION_DOWN) {
            swallowedKeys += event.keyCode
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val wasIdle = IdleState.idle
        IdleState.poke()
        if (ev.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) MouseActivity.moved()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && wasIdle) swallowTouch = true
        if (swallowTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowTouch = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        IdleState.poke()
        if (ev.isFromSource(android.view.InputDevice.SOURCE_MOUSE) &&
            (ev.actionMasked == MotionEvent.ACTION_HOVER_MOVE || ev.actionMasked == MotionEvent.ACTION_HOVER_ENTER)) MouseActivity.moved()
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onStart() {
        super.onStart()
        KeepHome.sync(this)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        IdleState.poke()           // coming back to the launcher counts as activity: icons show
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Everything but the dock's own icons can be decoded again on demand.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            IconStore.trim()
            AppRepository.cache = emptyList()
        }
    }

    companion object {
        /** Read by the service (same process) to confirm a background launch really showed the launcher. */
        @Volatile var visible = false
    }

    // singleTask: pressing Home while we are already running lands here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homePresses++
    }
}
