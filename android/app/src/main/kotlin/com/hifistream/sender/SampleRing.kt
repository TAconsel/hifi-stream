package com.hifistream.sender

/** Single-producer / single-consumer ring of interleaved float samples. */
class SampleRing(capacity: Int) {
    private val data = FloatArray(capacity)
    private val lock = Object()
    private var head = 0        // next write index
    private var count = 0

    /** Samples discarded because the consumer fell more than a full ring behind. */
    @Volatile var overruns = 0L
        private set

    fun put(src: FloatArray, n: Int) = synchronized(lock) {
        if (count + n > data.size) {
            // Drop the oldest audio rather than block the reader: the reader's timing is
            // what the clock is locked to.
            val drop = count + n - data.size
            count -= drop
            overruns += drop
        }
        var i = 0
        while (i < n) {
            val idx = (head + i) % data.size
            val run = minOf(n - i, data.size - idx)
            System.arraycopy(src, i, data, idx, run)
            i += run
        }
        head = (head + n) % data.size
        count += n
        lock.notify()
    }

    /** Waits up to [timeoutMs] for [n] samples; false if they did not arrive. */
    fun take(dst: FloatArray, n: Int, timeoutMs: Long): Boolean = synchronized(lock) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (count < n) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return false
            lock.wait(left / 1_000_000, (left % 1_000_000).toInt())
        }
        val tail = (head - count + data.size) % data.size
        var i = 0
        while (i < n) {
            val idx = (tail + i) % data.size
            val run = minOf(n - i, data.size - idx)
            System.arraycopy(data, idx, dst, i, run)
            i += run
        }
        count -= n
        true
    }
}
