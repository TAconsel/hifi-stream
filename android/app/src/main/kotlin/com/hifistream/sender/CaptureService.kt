package com.hifistream.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Foreground service that captures everything other apps play (AudioPlaybackCapture,
 * the same mechanism RootlessJamesDSP uses) and streams it as raw PCM over UDP.
 */
class CaptureService : Service() {

    companion object {
        private const val TAG = "HiFiStream"
        const val ACTION_START = "com.hifistream.sender.START"
        const val ACTION_STOP = "com.hifistream.sender.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_RATE = "rate"
        const val EXTRA_FORMAT = "format"
        const val EXTRA_MUTE = "mute"
        private const val CHANNEL_ID = "streaming"
        private const val NOTIFICATION_ID = 1
        private const val CHANNELS = 2

        fun stopIntent(context: Context) = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)

        /** Restores a media volume left muted by an earlier, killed session, if any. */
        fun restoreSavedVolume(context: Context) {
            val settings = Settings(context)
            val v = settings.savedVolume
            if (v < 0) return
            try {
                context.getSystemService(AudioManager::class.java)
                    .setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
            } catch (_: Exception) {
            }
            settings.savedVolume = -1
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedVolume = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> {
                stopStreaming("Stopped")
                stopSelf()
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        if (running) return
        createChannel()
        // Android 14+ requires the mediaProjection foreground service to be up
        // before the projection token is turned into a MediaProjection.
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val host = intent.getStringExtra(EXTRA_HOST) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, StreamProtocol.DEFAULT_PORT)
        val rate = intent.getIntExtra(EXTRA_RATE, 48000)
        val format = StreamProtocol.Format.fromId(intent.getIntExtra(EXTRA_FORMAT, StreamProtocol.Format.S24.id))
        val mute = intent.getBooleanExtra(EXTRA_MUTE, true)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultData == null) {
            fail("No screen capture permission")
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = try {
            mpm.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            null
        }
        if (proj == null) {
            fail("Capture permission was not granted")
            return
        }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // The user revoked capture from the system UI (also fires on our own stop()).
                if (!running) return
                stopStreaming("Capture stopped by system")
                stopSelf()
            }
        }, mainHandler)
        projection = proj

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(proj)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(rate)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        if (minBuf <= 0) {
            fail("Sample rate $rate Hz not supported for capture")
            return
        }
        val rec = try {
            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(maxOf(minBuf * 2, rate / 5 * CHANNELS * 4))  // >= 200 ms of slack
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            fail("Cannot open capture: ${e.message}")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            fail("AudioRecord failed to initialise")
            return
        }
        record = rec

        acquireLocks()
        if (mute) mutePhone()

        running = true
        StreamState.running = true
        StreamState.error = null
        StreamState.host = "$host:$port"
        StreamState.rate = rate
        StreamState.packets = 0
        StreamState.kbps = 0.0
        StreamState.seconds = 0
        StreamState.readErrors = 0
        StreamState.sourceBits = 0
        StreamState.status = "Streaming ${rate / 1000.0} kHz ${format.label}"
        notify("Streaming to $host · ${rate / 1000.0} kHz ${format.label}")

        worker = Thread({ streamLoop(rec, host, port, rate, format) }, "hfs-capture").also { it.start() }
    }

    private fun streamLoop(rec: AudioRecord, host: String, port: Int, rate: Int, format: StreamProtocol.Format) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val framesPerPacket = StreamProtocol.framesPerPacket(rate, CHANNELS, format)
        mainHandler.post { StreamState.framesPerPacket = framesPerPacket }
        val samples = FloatArray(framesPerPacket * CHANNELS)
        val buf = ByteBuffer.allocate(StreamProtocol.HEADER_SIZE + samples.size * format.bytesPerSample)
            .order(ByteOrder.LITTLE_ENDIAN)
        val socket: DatagramSocket
        val address: InetAddress
        try {
            address = InetAddress.getByName(host)
            socket = DatagramSocket()
            socket.trafficClass = 0xB8          // DSCP EF → Wi-Fi WMM voice queue
            socket.sendBufferSize = 256 * 1024
        } catch (e: Exception) {
            mainHandler.post { fail("Bad receiver address: $host") }
            return
        }
        val packet = DatagramPacket(buf.array(), 0, address, port)

        var seq = 0
        var analysed = 0L          // non-zero samples inspected for the source resolution
        var hiRes = false
        var packets = 0L
        var bytes = 0L
        var readErrors = 0L
        val startMs = SystemClock.elapsedRealtime()
        var lastStatMs = startMs
        var windowBytes = 0L

        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            mainHandler.post { fail("Capture did not start (is another app capturing?)") }
            return
        }
        try {
            while (running) {
                val n = rec.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) {
                    readErrors++
                    if (n < 0) {
                        mainHandler.post { fail("Capture read error $n") }
                        break
                    }
                    continue
                }
                val frames = n / CHANNELS
                if (!hiRes && analysed < rate * 4L) {
                    // Android's playback capture is 16-bit on most devices: detect whether
                    // any sample carries more than 16 bits so the UI can say so honestly.
                    for (i in 0 until n) {
                        val v = samples[i]
                        if (v == 0f) continue
                        analysed++
                        val scaled = v * 32768f
                        if (scaled != Math.round(scaled).toFloat()) { hiRes = true; break }
                    }
                    if (hiRes || analysed >= rate * 4L) {
                        val bits = if (hiRes) 24 else 16
                        mainHandler.post { StreamState.sourceBits = bits }
                    }
                }
                val tsUs = SystemClock.elapsedRealtimeNanos() / 1000
                buf.clear()
                StreamProtocol.writeHeader(buf, seq, format, CHANNELS, rate, frames, tsUs)
                StreamProtocol.writeSamples(buf, samples, frames * CHANNELS, format)
                packet.setData(buf.array(), 0, buf.position())
                try {
                    socket.send(packet)
                } catch (e: Exception) {
                    readErrors++
                }
                seq++
                packets++
                bytes += buf.position()
                windowBytes += buf.position()

                val now = SystemClock.elapsedRealtime()
                if (now - lastStatMs >= 500) {
                    val kbps = windowBytes * 8.0 / (now - lastStatMs)
                    val p = packets; val e = readErrors; val secs = (now - startMs) / 1000
                    mainHandler.post {
                        StreamState.packets = p
                        StreamState.kbps = kbps
                        StreamState.seconds = secs
                        StreamState.readErrors = e
                    }
                    windowBytes = 0
                    lastStatMs = now
                }
            }
        } finally {
            try {
                val bye = StreamProtocol.BYE.toByteArray()
                socket.send(DatagramPacket(bye, bye.size, address, port))
            } catch (_: Exception) {
            }
            socket.close()
            Log.i(TAG, "sent $packets packets, $bytes bytes")
        }
    }

    private fun stopStreaming(reason: String) {
        running = false
        worker?.let { w ->
            try {
                w.join(2000)
            } catch (_: InterruptedException) {
            }
        }
        worker = null
        record?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        record = null
        projection?.stop()
        projection = null
        restoreVolume()
        releaseLocks()
        StreamState.running = false
        StreamState.status = reason
        StreamState.kbps = 0.0
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        StreamState.error = message
        stopStreaming("Failed")
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopStreaming("Stopped")
        super.onDestroy()
    }

    // ---- housekeeping -------------------------------------------------------

    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "HiFiStream").also { it.acquire() }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HiFiStream:capture").also { it.acquire() }
    }

    private fun releaseLocks() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun mutePhone() {
        val am = getSystemService(AudioManager::class.java)
        val settings = Settings(this)
        try {
            // If a previous session was killed before it could restore the volume,
            // keep its saved value instead of "saving" the muted state.
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            savedVolume = if (settings.savedVolume >= 0) settings.savedVolume else current
            settings.savedVolume = savedVolume
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (e: Exception) {
            savedVolume = -1
        }
    }

    private fun restoreVolume() {
        if (savedVolume < 0) return
        restoreSavedVolume(this)
        savedVolume = -1
    }



    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getForegroundService(
            this, 1, stopIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stream)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
