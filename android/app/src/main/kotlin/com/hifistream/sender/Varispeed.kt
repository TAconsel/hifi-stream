package com.hifistream.sender

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Varispeed resampler for ratios very close to 1: windowed-sinc interpolation with a
 * phase table, so a receiver without the CPU for a resampler (a microcontroller) can
 * ask the phone to run its clock a few hundred ppm faster or slower instead.
 *
 * [step] is the number of input frames consumed per output frame: 1.0003 means the phone
 * produces 0.03 % fewer frames than it captures (the receiver's buffer was running long).
 * At exactly 1.0 the audio is passed through untouched, so the bit-exact path is kept
 * whenever no correction is requested.
 *
 * Kernel: 32 taps, Blackman-Harris window, cutoff just below Nyquist, 256 phases with
 * linear interpolation between them. About 6 M multiply-adds per second at 96 kHz stereo.
 */
class Varispeed(private val channels: Int) {
    companion object {
        private const val TAPS = 32
        private const val HALF = TAPS / 2
        private const val PHASES = 256
        private const val CUTOFF = 0.97          // of Nyquist, keeps 20 kHz at 44.1/48 k intact

        /** table[(phase * TAPS) + k]: coefficient of input sample (i - HALF + 1 + k) for fractional position phase/PHASES. */
        private val table: FloatArray by lazy {
            val t = FloatArray((PHASES + 1) * TAPS)
            for (p in 0..PHASES) {
                val frac = p.toDouble() / PHASES
                var sum = 0.0
                for (k in 0 until TAPS) {
                    val x = (k - HALF + 1) - frac            // distance from the output position, in input samples
                    val sinc = if (abs(x) < 1e-9) CUTOFF else sin(PI * CUTOFF * x) / (PI * x)
                    val w = (x + HALF) / TAPS                // 0..1 across the kernel
                    val win = 0.35875 - 0.48829 * cos(2 * PI * w) + 0.14128 * cos(4 * PI * w) - 0.01168 * cos(6 * PI * w)
                    val c = sinc * win
                    t[p * TAPS + k] = c.toFloat()
                    sum += c
                }
                for (k in 0 until TAPS) t[p * TAPS + k] = (t[p * TAPS + k] / sum).toFloat()   // unity DC gain per phase
            }
            t
        }
    }

    @Volatile var step = 1.0
    private var target = 1.0

    private var hist = FloatArray(0)          // input history, interleaved, HALF+TAPS frames of context are kept
    private var histFrames = 0
    private var pos = 0.0                     // read position in hist, in frames

    /** Requested correction in ppm (positive: produce fewer frames). Slewed towards over the next calls. */
    fun request(ppm: Double) {
        target = 1.0 + (ppm.coerceIn(-5000.0, 5000.0)) * 1e-6
    }

    val active: Boolean get() = step != 1.0 || target != 1.0

    /**
     * Resamples [n] interleaved samples from [src]; returns the number of output samples
     * written to [dst] (a multiple of [channels]). Give [dst] room for about 2 n: the first
     * call produces nothing (kernel start-up), the next one catches up.
     */
    fun process(src: FloatArray, n: Int, dst: FloatArray): Int {
        // Move the step towards the target no faster than 50 ppm per call (a call is one packet).
        val d = target - step
        step = if (abs(d) <= 50e-6) target else step + if (d > 0) 50e-6 else -50e-6

        val inFrames = n / channels
        if (step == 1.0 && histFrames == 0) {
            System.arraycopy(src, 0, dst, 0, n)
            return n
        }
        // Append input to the history.
        val need = (histFrames + inFrames) * channels
        if (hist.size < need) hist = hist.copyOf(need + 4096 * channels)
        System.arraycopy(src, 0, hist, histFrames * channels, n)
        histFrames += inFrames

        var out = 0
        // The kernel needs HALF-1 frames before and HALF frames after the read position.
        // Output that does not fit in dst stays in the history for the next call.
        while (pos + HALF < histFrames && pos >= HALF - 1 && out + channels <= dst.size) {
            val i = pos.toInt()
            val frac = pos - i
            val ph = frac * PHASES
            val p0 = ph.toInt()
            val pf = (ph - p0).toFloat()
            val base0 = p0 * TAPS
            val base1 = (p0 + 1) * TAPS
            val start = (i - HALF + 1) * channels
            for (c in 0 until channels) {
                var acc = 0f
                var idx = start + c
                for (k in 0 until TAPS) {
                    val coef = table[base0 + k] + (table[base1 + k] - table[base0 + k]) * pf
                    acc += hist[idx] * coef
                    idx += channels
                }
                dst[out + c] = acc
            }
            out += channels
            pos += step
        }
        if (pos < HALF - 1) pos = (HALF - 1).toDouble()     // start-up: wait for enough history
        // Drop history that will never be needed again, keeping HALF frames before pos.
        val dropFrames = pos.toInt() - HALF
        if (dropFrames > 0) {
            System.arraycopy(hist, dropFrames * channels, hist, 0, (histFrames - dropFrames) * channels)
            histFrames -= dropFrames
            pos -= dropFrames
        }
        return out
    }
}
