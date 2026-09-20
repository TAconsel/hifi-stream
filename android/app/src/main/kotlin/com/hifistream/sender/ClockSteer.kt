package com.hifistream.sender

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Lossless rate matching: trims the phone's kernel clock (adjtimex, root) by the ppm the
 * receiver asks for. Android's remote-submix pipe and mixer are timed by kernel sleeps and
 * CLOCK_MONOTONIC, so the whole media pipeline — every app's playback included — then runs
 * exactly at the receiver's clock and no sample is ever resampled anywhere. Measured on an
 * Xperia 1 II: a +400 ppm trim moves the captured rate by +400 ppm within a few seconds.
 *
 * The wall clock drifts by the trim while streaming (a few seconds per hour at most); the
 * accumulated offset is slewed back out when streaming stops.
 */
object ClockSteer {
    private const val TAG = "HiFiStream"
    private const val BUSYBOX = "/data/adb/magisk/busybox"
    private const val MAX_PPM = 450.0            // the kernel allows ±500
    private const val SCALE = 65536.0            // adjtimex frequency unit: 2^-16 ppm

    private val exec = Executors.newSingleThreadExecutor { Thread(it, "hfs-clock") }

    @Volatile var available: Boolean? = null
        private set
    @Volatile var appliedPpm = 0.0
        private set
    private var appliedSinceMs = 0L
    private var offsetUs = 0.0                   // wall-clock offset accumulated by the trim
    @Volatile private var wantedPpm = 0.0
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastApplyMs = 0L
    private const val MIN_INTERVAL_MS = 1000L    // at most one adjtimex call per second

    /** Probes once whether adjtimex works here (root + busybox). */
    fun probe(): Boolean {
        available?.let { return it }
        val ok = RootInstaller.runAsRoot("$BUSYBOX adjtimex").log.contains("freq.adjust")
        available = ok
        return ok
    }

    /**
     * Asks for a trim of [ppm] (positive = clock runs faster). Cheap to call often: only one
     * root command is ever in flight, and at most one per second.
     */
    fun request(ppm: Double) {
        wantedPpm = ppm.coerceIn(-MAX_PPM, MAX_PPM)
        if (abs(wantedPpm - appliedPpm) < 1.0) return
        if (SystemClock.elapsedRealtime() - lastApplyMs < MIN_INTERVAL_MS) return
        if (!pending.compareAndSet(false, true)) return
        exec.execute {
            try {
                apply(wantedPpm)
            } finally {
                pending.set(false)
            }
        }
    }

    /** Back to a normal clock, slewing out the offset the trim accumulated. */
    fun release() {
        wantedPpm = 0.0
        exec.execute {
            apply(0.0)
            val off = offsetUs.roundToLong()
            offsetUs = 0.0
            if (abs(off) < 2_000) return@execute
            // adjtimex -o slews a single-shot offset (kernel: ±500 ppm until done). The sign
            // is "add this to the clock": we accumulated +off, so ask for -off.
            RootInstaller.runAsRoot("$BUSYBOX adjtimex -o ${-off}")
            Log.i(TAG, "clock steering released, slewing out ${off / 1000} ms of wall-clock offset")
        }
    }

    private fun apply(ppm: Double) {
        val now = SystemClock.elapsedRealtime()
        lastApplyMs = now
        if (appliedSinceMs != 0L) offsetUs += appliedPpm * (now - appliedSinceMs) / 1000.0   // ppm × s = µs
        val r = RootInstaller.runAsRoot("$BUSYBOX adjtimex -f ${(ppm * SCALE).roundToLong()}")
        if (!r.log.contains("freq.adjust")) {
            Log.w(TAG, "adjtimex failed: ${r.log.take(120)}")
            available = false
            return
        }
        appliedPpm = ppm
        appliedSinceMs = now
    }
}
