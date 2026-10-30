package com.example.projectorlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Polls UsageStats once a second. When the configured stock launcher becomes the foreground app
 * it starts our launcher. It never touches other apps' processes.
 */
class KeepHomeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var running = false
    private var startedAt = 0L
    private var lastEventTime = 0L
    private var foregroundPkg: String? = null
    private var lastLaunch = 0L
    private val recentLaunches = ArrayDeque<Long>()
    private var status = ""
    private var bootPending = false
    private var bootAttempts = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        status = "Starting..."
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(status), type)
        if (intent?.getBooleanExtra(EXTRA_BOOT_LAUNCH, false) == true) beginBootLaunch()
        if (!running) {
            running = true
            startedAt = SystemClock.elapsedRealtime()
            lastEventTime = System.currentTimeMillis() - 10_000
            handler.post(tick)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!prefs.enabled || (!prefs.keepOnTop && !bootPending)) {
                stopSelf()
                return
            }
            try {
                if (prefs.keepOnTop) poll()
            } catch (e: Exception) {
                Log.w(TAG, "poll failed", e)
            }
            handler.postDelayed(this, POLL_MS)
        }
    }

    private fun bootId() = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun beginBootLaunch() {
        val id = bootId()
        if (bootPending || (id != -1 && id == prefs.lastBootId)) return
        bootPending = true
        bootAttempts = 0
        setStatus("Starting the launcher after boot")
        // Let the stock launcher finish starting first.
        handler.postDelayed(::tryBootLaunch, Prefs.BOOT_LAUNCH_DELAY_MS)
    }

    private fun tryBootLaunch() {
        if (!prefs.enabled) { bootPending = false; return }
        bootAttempts++
        try {
            startActivity(KeepHome.mainActivityIntent(this))
        } catch (e: Exception) {
            Log.w(TAG, "boot launch failed", e)
        }
        // startActivity does not throw when Android silently blocks it, so check the result.
        handler.postDelayed({
            if (MainActivity.visible) {
                prefs.lastBootId = bootId()
                bootPending = false
            } else if (bootAttempts < BOOT_ATTEMPTS) {
                tryBootLaunch()
            } else {
                bootPending = false
                setStatus("Unable to start at boot. Please grant Display over other apps")
            }
        }, 2_500)
    }

    private fun poll() {
        val stock = prefs.stockPackage
        val now = System.currentTimeMillis()
        when {
            stock.isEmpty() -> return setStatus("Please set the stock launcher package in Launcher settings")
            KeepHome.isProtected(this, stock) -> return setStatus("$stock is protected. Please pick the actual stock launcher")
            !KeepHome.hasUsageAccess(this) -> return setStatus("Requires Usage access permission")
            !KeepHome.canDrawOverlays(this) -> return setStatus("Requires Display over other apps permission")
        }

        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(lastEventTime, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) foregroundPkg = event.packageName
        }
        lastEventTime = now - 500

        if (SystemClock.elapsedRealtime() - startedAt < STARTUP_GRACE_MS) return setStatus("Starting...")
        if (now < prefs.pausedUntil) return setStatus("Paused")
        setStatus("Watching for $stock")

        if (foregroundPkg != stock || now - lastLaunch < RELAUNCH_GUARD_MS) return
        lastLaunch = now
        recentLaunches.addLast(now)
        while (recentLaunches.isNotEmpty() && now - recentLaunches.first() > LOOP_WINDOW_MS) recentLaunches.removeFirst()
        if (recentLaunches.size > LOOP_LIMIT) {
            // Something is fighting us: give up rather than risk locking the user out.
            prefs.keepOnTop = false
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID + 1, buildNotification("Keep-home was disabled after repeated relaunches", ongoing = false))
            stopSelf()
            return
        }
        startActivity(KeepHome.mainActivityIntent(this))
    }

    private fun setStatus(text: String) {
        if (text == status) return
        status = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Keep launcher on top", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val off = PendingIntent.getBroadcast(
            this, 1,
            Intent(ControlReceiver.ACTION_TURN_OFF).setComponent(ComponentName(this, ControlReceiver::class.java)),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Bliss Launcher is active")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .addAction(0, "Turn off", off)
            .build()
    }

    companion object {
        const val EXTRA_BOOT_LAUNCH = "boot_launch"
        private const val BOOT_ATTEMPTS = 3
        private const val TAG = "KeepHomeService"
        private const val CHANNEL_ID = "keep_home"
        private const val NOTIF_ID = 42
        private const val POLL_MS = 1_000L
        private const val STARTUP_GRACE_MS = 5_000L
        private const val RELAUNCH_GUARD_MS = 2_000L
        private const val LOOP_WINDOW_MS = 30_000L
        private const val LOOP_LIMIT = 5
    }
}
