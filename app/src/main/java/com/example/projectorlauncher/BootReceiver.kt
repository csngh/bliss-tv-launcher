package com.example.projectorlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (!prefs.enabled) return
        // Android blocks background activity starts from a receiver, but allows them from a foreground
        // service (with Display over other apps), so the service does the delayed launch itself.
        // It also restarts keep-home when that is enabled.
        if (prefs.autoStartOnBoot) KeepHome.startForBoot(context)
        else KeepHome.sync(context)
    }
}

/** Target of the notification's "Turn off" action. */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TURN_OFF) KeepHome.turnAllOff(context)
    }

    companion object {
        const val ACTION_TURN_OFF = "com.example.projectorlauncher.TURN_OFF"
    }
}
