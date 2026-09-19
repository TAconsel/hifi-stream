package com.hifistream.sender

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a float test tone through the normal media path so the whole chain
 * (capture → Wi-Fi → receiver) can be checked without another app.
 * 1 kHz at -12 dBFS plus an inaudible 3 kHz at -80 dBFS that only survives a
 * >16-bit path, which lets the receiver's WAV dump prove the bit depth.
 */
object TestTone {
    @Volatile private var thread: Thread? = null

    val playing: Boolean get() = thread != null

    fun start(rate: Int = 48000) {
        if (thread != null) return
        val t = Thread({
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(maxOf(minBuf, rate / 5 * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val block = FloatArray(rate / 50 * 2)   // 20 ms
            var n = 0L
            track.play()
            try {
                while (!Thread.currentThread().isInterrupted) {
                    for (i in 0 until block.size / 2) {
                        val t0 = (n + i).toDouble() / rate
                        val v = (0.25 * sin(2 * PI * 1000 * t0) + 1e-4 * sin(2 * PI * 3000 * t0)).toFloat()
                        block[2 * i] = v
                        block[2 * i + 1] = v
                    }
                    n += block.size / 2
                    if (track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING) < 0) break
                }
            } finally {
                try { track.stop() } catch (_: Exception) {}
                track.release()
            }
        }, "hfs-testtone")
        thread = t
        t.start()
    }

    fun stop() {
        thread?.let {
            it.interrupt()
            try { it.join(1000) } catch (_: InterruptedException) {}
        }
        thread = null
    }
}
