package com.hifistream.sender

import android.media.AudioRecord
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.locks.LockSupport

/**
 * Turns the bursty delivery of [AudioRecord] into a smooth packet stream.
 *
 * Android hands captured audio over one HAL period at a time (2048 frames ≈ 21 ms on the
 * remote-submix path of most phones), so a loop that stamps each packet with "now" and sends
 * it straight away produces a burst of a dozen packets every 21 ms. The receiver's
 * inter-arrival jitter then hides the real 21 ms structure, and its adaptive buffer is fed a
 * misleadingly small number.
 *
 * Two threads share this object. The *reader* drains [AudioRecord] as fast as it can and
 * calls [onRead]; because it never has anything else to do, a `read()` that blocked is by
 * definition a HAL delivery and its return time is a clean observation of the capture clock.
 * The *sender* calls [timestampUs] and [waitUntilDue] to stamp and pace packets.
 *
 * The capture clock is a software audio clock (frames / rate) phase-locked to those delivery
 * times. Frames can never be delivered before they were captured, so "delivery time −
 * predicted capture time of the last delivered frame" (the slack) is ≥ 0 for a well-locked
 * clock and its minimum over a window should sit at a small constant. A PI controller slews
 * the clock a few microseconds per packet to keep it there, which follows the real capture
 * rate (the remote-submix pipe is software-timed and runs hundreds of ppm off nominal)
 * without ever producing a timestamp jump. [AudioRecord.getTimestamp] is deliberately not
 * used: that HAL stamps positions with the time of the query, so it carries the same
 * period-sized noise this class exists to remove.
 */
class PacketClock(private val rate: Int) {
    companion object {
        private const val TAG = "HiFiStream"
        private const val US = 1_000_000.0
        private const val PACE_MARGIN_US = 2_000L           // headroom above the latency envelope
        private const val PACE_DECAY_PER_S_US = 200L        // how fast the envelope may shrink
        private const val BLOCKED_US = 1_000L               // a read that took longer really waited for the HAL
        private const val REANCHOR_US = 250_000L            // capture stall: rebuild the clock
        private const val LATENCY_MAX_US = 100_000L         // a later delivery is a stall, not jitter to pace for
        private const val LOCK_WINDOW_US = 1_000_000L       // minimum slack is evaluated once per window
        private const val LOCK_TARGET_US = 500L             // where the minimum slack should sit
        private const val ANCHOR_HEADROOM_US = LOCK_TARGET_US // a fresh anchor starts at the target, no step to integrate
        private const val KP = 0.2                          // fraction of the error removed per second
        private const val KI = 0.02                         // integral gain, per second²
        private const val SLEW_MAX_US_PER_S = 3_000L        // ≈ 0.3 % rate change at most: enough for any real drift, gentle on the receiver
        private const val LATE_LOG_US = 4_000L              // a packet this late is worth a log line
        private const val LOG_INTERVAL_US = 1_000_000L
    }

    private val lock = Any()
    private var anchorFrame = 0L        // audio position of anchorUs
    private var anchorUs = 0L           // monotonic time of anchorFrame
    private var slewUsPerS = 0L         // current clock correction rate
    private var slewLastUs = 0L         // when the slew was last applied
    private var integral = 0.0
    private var windowSlackUs = Long.MAX_VALUE
    private var windowStartUs = 0L
    private var lastDecayUs = 0L
    private var lastLogUs = 0L

    /** Pacing delay in µs between a packet's capture time and its departure. */
    @Volatile var paceUs = 0L
        private set

    /** Capture latency envelope (delivery time − capture time of the first frame), µs. */
    @Volatile var latencyUs = 0L
        private set

    /** Measured HAL period in frames (size of the last delivery). */
    @Volatile var periodFrames = 0
        private set

    /** Packets that left more than a few ms after they were due. */
    @Volatile var latePackets = 0L
        private set

    /** Reader: frames [firstFrame, firstFrame+frames) were just returned by `read()`, which took [blockedUs]. */
    fun onRead(firstFrame: Long, frames: Int, blockedUs: Long) {
        val now = nowUs()
        val end = firstFrame + frames
        val blocked = blockedUs >= BLOCKED_US
        synchronized(lock) {
            if (anchorUs == 0L) anchor(end, now)
            applySlew(now)
            if (!blocked) {
                // Audio that was already waiting says nothing about when it was delivered.
                paceUs = latencyUs + PACE_MARGIN_US
                return
            }
            periodFrames = frames

            val slack = now - timestampUs(end)
            if (slack < 0 || slack > REANCHOR_US) {
                Log.i(TAG, "clock re-anchored: slack ${slack / 1000} ms")
                anchor(end, now)
                return
            } else {
                windowSlackUs = minOf(windowSlackUs, slack)
                if (now - windowStartUs >= LOCK_WINDOW_US) {
                    val err = (windowSlackUs - LOCK_TARGET_US).toDouble()
                    val dt = (now - windowStartUs) / US
                    integral = (integral + err * dt).coerceIn(-SLEW_MAX_US_PER_S / KI, SLEW_MAX_US_PER_S / KI)
                    slewUsPerS = (KP * err + KI * integral).toLong().coerceIn(-SLEW_MAX_US_PER_S, SLEW_MAX_US_PER_S)
                    windowSlackUs = Long.MAX_VALUE
                    windowStartUs = now
                }
            }

            if (lastDecayUs == 0L) lastDecayUs = now
            val decay = (now - lastDecayUs) * PACE_DECAY_PER_S_US / 1_000_000L
            if (decay > 0) { lastDecayUs = now; latencyUs = maxOf(latencyUs - decay, 0) }
            val lat = now - timestampUs(firstFrame)
            if (lat > latencyUs && lat <= LATENCY_MAX_US) latencyUs = lat
            paceUs = latencyUs + PACE_MARGIN_US
        }
    }

    /** Monotonic capture time (µs) of the given frame position. */
    fun timestampUs(frame: Long): Long = synchronized(lock) {
        anchorUs + ((frame - anchorFrame) * US / rate).toLong()
    }

    /** Sender: blocks until the packet starting at [firstFrame] is due; returns false if it was already late. */
    fun waitUntilDue(firstFrame: Long): Boolean {
        val due = timestampUs(firstFrame) + paceUs
        var waited = false
        var now = nowUs()
        while (true) {
            val wait = due - now
            if (wait <= 0) break
            waited = true
            LockSupport.parkNanos(wait * 1000)
            now = nowUs()
        }
        val late = now - due
        if (late >= LATE_LOG_US) {
            latePackets++
            if (now - lastLogUs >= LOG_INTERVAL_US) {
                lastLogUs = now
                Log.i(TAG, "late packet: ${late / 1000} ms after due (" +
                    (if (waited) "woke late from sleep" else "audio arrived late") +
                    ") pace ${paceUs / 1000} ms latency ${latencyUs / 1000} ms slew $slewUsPerS µs/s")
            }
        }
        return waited
    }

    /** Applies the running slew to the anchor, spread over time so timestamps stay continuous. */
    private fun applySlew(now: Long) {
        if (slewLastUs != 0L && slewUsPerS != 0L) anchorUs += (now - slewLastUs) * slewUsPerS / 1_000_000L
        slewLastUs = now
    }

    /**
     * Re-bases the clock on a delivery and restarts the controller, with headroom so that
     * ordinary delivery jitter cannot push the slack negative before the first lock window
     * has run.
     */
    private fun anchor(frame: Long, atUs: Long) {
        anchorFrame = frame
        anchorUs = atUs - ANCHOR_HEADROOM_US
        slewLastUs = atUs
        slewUsPerS = 0
        integral = 0.0
        windowSlackUs = Long.MAX_VALUE
        windowStartUs = atUs
        latencyUs = 0           // measured against a clock that was wrong
        paceUs = PACE_MARGIN_US
    }

    private fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1000
}
