package com.hifistream.sender

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** HFS1 wire format, see PROTOCOL.md in the repository root. */
object StreamProtocol {
    const val MAGIC = 0x31534648          // "HFS1"
    const val DEFAULT_PORT = 47100
    const val HEADER_SIZE = 24
    const val MAX_PAYLOAD = 1440          // keeps packets under the 1472-byte UDP/IPv4 MTU budget
    const val DISCOVER = "HFS_DISCOVER"
    const val HERE = "HFS_HERE"
    const val BYE = "HFS_BYE"
    const val VOLUME = "HFS_VOL"          // "HFS_VOL 0.63": phone media volume as a fraction 0..1
    const val NACK = "HFS_NACK"           // followed by uint16 LE sequence numbers the receiver wants again
    const val REPORT = "HFS_RX"           // receiver health report, see PROTOCOL.md
    const val HISTORY = 256               // packets kept for retransmission (≈ 0.5-1.3 s)

    enum class Format(val id: Int, val bytesPerSample: Int, val label: String) {
        S16(1, 2, "16-bit"),
        S24(2, 3, "24-bit"),
        F32(3, 4, "32-bit float");

        companion object {
            fun fromId(id: Int) = entries.firstOrNull { it.id == id } ?: S24
        }
    }

    /** Frames per packet: ~5 ms of audio, but never more than fits in one MTU. */
    fun framesPerPacket(rate: Int, channels: Int, format: Format): Int =
        minOf(rate / 200, MAX_PAYLOAD / (channels * format.bytesPerSample))

    fun writeHeader(buf: ByteBuffer, seq: Int, format: Format, channels: Int, rate: Int, frames: Int, tsUs: Long) {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC)
        buf.putShort((seq and 0xFFFF).toShort())
        buf.put(format.id.toByte())
        buf.put(channels.toByte())
        buf.putInt(rate)
        buf.putInt(frames)
        buf.putLong(tsUs)
    }

    /** Converts interleaved float samples to the wire format. */
    fun writeSamples(buf: ByteBuffer, samples: FloatArray, count: Int, format: Format) {
        when (format) {
            Format.S16 -> for (i in 0 until count) {
                val v = (samples[i] * 32768f).toInt().coerceIn(-32768, 32767)
                buf.putShort(v.toShort())
            }
            Format.S24 -> for (i in 0 until count) {
                val v = Math.round(samples[i] * 8388608f).coerceIn(-8388608, 8388607)
                buf.put(v.toByte())
                buf.put((v shr 8).toByte())
                buf.put((v shr 16).toByte())
            }
            Format.F32 -> for (i in 0 until count) buf.putFloat(samples[i])
        }
    }
}
