package com.hifistream.sender

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Observable state shared between the capture service and the UI. */
object StreamState {
    var running by mutableStateOf(false)
    var status by mutableStateOf("Idle")
    var error by mutableStateOf<String?>(null)
    var packets by mutableStateOf(0L)
    var kbps by mutableStateOf(0.0)
    var seconds by mutableStateOf(0L)
    var readErrors by mutableStateOf(0L)
    var framesPerPacket by mutableStateOf(0)
    var sourceBits by mutableStateOf(0)   // 0 = not yet known, 16 or 24 (= more than 16)
    var captureLatencyMs by mutableStateOf(0.0)   // worst delivery latency of a HAL period recently
    var capturePeriodMs by mutableStateOf(0.0)    // Android's HAL period as measured
    var paceMs by mutableStateOf(0.0)             // delay added to smooth packets out
    var latePackets by mutableStateOf(0L)         // sent > 4 ms after they were due
    var resent by mutableStateOf(0L)              // packets resent on the receiver's request
    var host by mutableStateOf("")
    var rate by mutableStateOf(0)
    var systemMode by mutableStateOf(false)
    var phoneVolume by mutableStateOf(-1f)
}

/** Persistent user settings. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(v) = prefs.edit().putString("host", v).apply()

    var port: Int
        get() = prefs.getInt("port", StreamProtocol.DEFAULT_PORT)
        set(v) = prefs.edit().putInt("port", v).apply()

    var rate: Int
        get() = prefs.getInt("rate", 48000)
        set(v) = prefs.edit().putInt("rate", v).apply()

    var format: StreamProtocol.Format
        get() = StreamProtocol.Format.fromId(prefs.getInt("format", StreamProtocol.Format.S24.id))
        set(v) = prefs.edit().putInt("format", v.id).apply()

    /** Phone media volume saved while streaming (muted or forwarded); -1 when nothing is pending. */
    var savedVolume: Int
        get() = prefs.getInt("savedVolume", -1)
        set(v) = prefs.edit().putInt("savedVolume", v).apply()

    /** The streaming volume (media-volume index) the volume keys last set while forwarding; -1 = none yet. */
    var streamVolume: Int
        get() = prefs.getInt("streamVolume", -1)
        set(v) = prefs.edit().putInt("streamVolume", v).apply()

    var systemMode: Boolean
        get() = prefs.getBoolean("systemMode", false)
        set(v) = prefs.edit().putBoolean("systemMode", v).apply()

    var forwardVolume: Boolean
        get() = prefs.getBoolean("forwardVolume", true)
        set(v) = prefs.edit().putBoolean("forwardVolume", v).apply()

    var mutePhone: Boolean
        get() = prefs.getBoolean("mute", true)
        set(v) = prefs.edit().putBoolean("mute", v).apply()
}
