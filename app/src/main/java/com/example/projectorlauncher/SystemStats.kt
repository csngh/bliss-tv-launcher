package com.example.projectorlauncher

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

class Stats(val ramPercent: Int, val cpuPercent: Int?, val cpuIsSystem: Boolean)

/**
 * Live CPU and RAM use. RAM comes from ActivityManager and is system-wide. Since Android 8 apps may not read
 * system CPU from /proc/stat, so CPU tries, in order: /proc/stat (older or relaxed firmware), the CPU
 * frequency against its maximum (a load estimate, where the kernel exposes it), and finally this launcher's
 * own CPU use, flagged as not system-wide.
 */
class StatsReader(private val context: Context) {
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private var lastTotal = 0L
    private var lastIdle = 0L
    private var lastProcCpu = Process.getElapsedCpuTime()
    private var lastWall = SystemClock.elapsedRealtime()

    /** RAM only (instant); CPU appears with the first full reading. */
    fun readRamOnly(): Stats {
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return Stats((100 - (mi.availMem * 100 / mi.totalMem.coerceAtLeast(1))).toInt().coerceIn(0, 100), null, true)
    }

    fun read(): Stats {
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val ram = if (mi.totalMem > 0) (100 - (mi.availMem * 100 / mi.totalMem)).toInt().coerceIn(0, 100) else 0
        procStatCpu()?.let { return Stats(ram, it, true) }
        freqCpu()?.let { return Stats(ram, it, true) }
        return Stats(ram, ownCpu(), false)
    }

    private fun procStatCpu(): Int? = try {
        val f = File("/proc/stat").readLines().first().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        val idle = f[3] + f.getOrElse(4) { 0 }
        val total = f.sum()
        val dTotal = total - lastTotal
        val dIdle = idle - lastIdle
        lastTotal = total; lastIdle = idle
        if (dTotal <= 0 || lastTotal == total && dTotal == 0L) null else (100 * (dTotal - dIdle) / dTotal).toInt().coerceIn(0, 100)
    } catch (e: Exception) {
        null
    }

    private fun freqCpu(): Int? = try {
        var cur = 0L; var max = 0L; var n = 0
        for (i in 0 until cores) {
            val base = "/sys/devices/system/cpu/cpu$i/cpufreq/"
            val c = File(base + "scaling_cur_freq").readText().trim().toLong()
            val m = File(base + "cpuinfo_max_freq").readText().trim().toLong()
            if (m > 1000) { cur += c; max += m; n++ }
        }
        if (n == 0 || max == 0L) null else (100 * cur / max).toInt().coerceIn(0, 100)
    } catch (e: Exception) {
        null
    }

    private fun ownCpu(): Int {
        val cpu = Process.getElapsedCpuTime()
        val wall = SystemClock.elapsedRealtime()
        val pct = if (wall > lastWall) (100 * (cpu - lastProcCpu) / ((wall - lastWall) * cores)).toInt() else 0
        lastProcCpu = cpu; lastWall = wall
        return pct.coerceIn(0, 100)
    }
}

@Composable
fun rememberStats(enabled: Boolean): Stats? {
    val context = LocalContext.current.applicationContext
    // Starts with a RAM-only reading taken right now, so the line is there on the very first frame.
    var stats by remember { mutableStateOf<Stats?>(if (enabled) StatsReader(context).readRamOnly() else null) }
    val resumed = rememberIsResumed()
    LaunchedEffect(enabled, resumed) {
        if (!enabled || !resumed) { if (!enabled) stats = null; return@LaunchedEffect }
        val reader = StatsReader(context)
        reader.read()                       // primes the CPU deltas
        delay(500)                          // a short first interval, so CPU shows up quickly
        stats = withContext(Dispatchers.IO) { reader.read() }
        while (true) {
            delay(2_000)
            stats = withContext(Dispatchers.IO) { reader.read() }
        }
    }
    return stats
}
