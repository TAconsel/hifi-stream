package com.hifistream.sender

import android.os.SystemClock

/**
 * The receiver's view of the link, from its once-a-second HFS_RX reports, condensed into
 * the 0..4 bars of the status-bar icon:
 *
 *  4  playing cleanly: no dropouts, nothing lost, jitter low
 *  3  losses being recovered by resend, or jitter getting high
 *  2  packets lost for good, or jitter high
 *  1  dropouts (underruns) at the receiver in the last few seconds
 *  0  no report from the receiver for 3 s (not reachable, or not playing)
 */
object LinkHealth {
    private const val STALE_MS = 3_000L
    private const val WINDOW_MS = 10_000L    // events count against the bars for this long

    @Volatile var jitterMs = 0.0; private set
    @Volatile var bufferMs = 0.0; private set
    @Volatile var lost = 0L; private set
    @Volatile var recovered = 0L; private set
    @Volatile var underruns = 0L; private set
    @Volatile private var lastReportMs = 0L
    @Volatile private var lastLostMs = 0L
    @Volatile private var lastRecoveredMs = 0L
    @Volatile private var lastUnderrunMs = 0L

    fun reset() {
        jitterMs = 0.0; bufferMs = 0.0; lost = 0; recovered = 0; underruns = 0
        lastReportMs = 0; lastLostMs = 0; lastRecoveredMs = 0; lastUnderrunMs = 0
    }

    /** Parses "HFS_RX <jitter_ms> <lost> <recovered> <underruns> <buffer_ms>". */
    fun onReport(text: String) {
        val f = text.trim().split(' ')
        if (f.size < 6) return
        val now = SystemClock.elapsedRealtime()
        try {
            val j = f[1].toDouble(); val l = f[2].toLong(); val r = f[3].toLong(); val u = f[4].toLong(); val b = f[5].toDouble()
            if (l > lost) lastLostMs = now
            if (r > recovered) lastRecoveredMs = now
            if (u > underruns) lastUnderrunMs = now
            jitterMs = j; lost = l; recovered = r; underruns = u; bufferMs = b
            lastReportMs = now
        } catch (_: NumberFormatException) {
        }
    }

    val reporting: Boolean get() = lastReportMs != 0L && SystemClock.elapsedRealtime() - lastReportMs < STALE_MS

    fun bars(): Int {
        val now = SystemClock.elapsedRealtime()
        if (!reporting) return 0
        fun recent(t: Long) = t != 0L && now - t < WINDOW_MS
        return when {
            recent(lastUnderrunMs) -> 1
            recent(lastLostMs) || jitterMs > 6.0 -> 2
            recent(lastRecoveredMs) || jitterMs > 3.0 -> 3
            else -> 4
        }
    }

    fun summary(): String = if (!reporting) "no report from receiver" else
        "PC: jitter %.1f ms · buffer %.0f ms · %d lost · %d recovered · %d dropouts".format(jitterMs, bufferMs, lost, recovered, underruns)
}
