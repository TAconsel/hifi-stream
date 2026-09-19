package com.hifistream.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.IntentFilter
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
import android.os.Build
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
        const val EXTRA_SYSTEM = "system"
        const val EXTRA_FORWARD_VOLUME = "forwardVolume"
        const val EXTRA_PACE_GROUP = "paceGroup"
        const val DEFAULT_PACE_GROUP = 4      // packets sent back-to-back per pacing slot
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        private const val CHANNEL_ID = "streaming"
        private const val NOTIFICATION_ID = 1
        private const val CHANNELS = 2

        fun stopIntent(context: Context) = Intent(context, CaptureService::class.java).setAction(ACTION_STOP)

        /** Start intent for system mode from the saved settings (no consent dialog is needed there). */
        fun systemStartIntent(context: Context, settings: Settings) = Intent(context, CaptureService::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_SYSTEM, true)
            .putExtra(EXTRA_FORWARD_VOLUME, settings.forwardVolume)
            .putExtra(EXTRA_HOST, settings.host)
            .putExtra(EXTRA_PORT, settings.port)
            .putExtra(EXTRA_RATE, settings.rate)
            .putExtra(EXTRA_FORMAT, settings.format.id)
            .putExtra(EXTRA_MUTE, settings.mutePhone)

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
    private var systemSession: SystemCapture.Session? = null
    @Volatile private var phoneVolume = -1f          // fraction of max media volume, -1 = not forwarded
    @Volatile private var volumeDirty = false
    private var volumeReceiver: BroadcastReceiver? = null

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
        val system = intent.getBooleanExtra(EXTRA_SYSTEM, false)
        // Android 14+ requires the mediaProjection foreground service to be up
        // before the projection token is turned into a MediaProjection.
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"),
            if (system) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val host = intent.getStringExtra(EXTRA_HOST) ?: ""
        val port = intent.getIntExtra(EXTRA_PORT, StreamProtocol.DEFAULT_PORT)
        val rate = intent.getIntExtra(EXTRA_RATE, 48000)
        paceGroup = intent.getIntExtra(EXTRA_PACE_GROUP, DEFAULT_PACE_GROUP).coerceIn(1, 64)
        val format = StreamProtocol.Format.fromId(intent.getIntExtra(EXTRA_FORMAT, StreamProtocol.Format.S24.id))
        val mute = intent.getBooleanExtra(EXTRA_MUTE, true) && !system
        if (system) {
            startSystemMode(intent, host, port, rate, format)
            return
        }
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
        go(rec, host, port, rate, format, mute, system = false)
    }

    /**
     * Privileged path: route media exclusively into a loop-back mix (no local
     * playback, no consent dialog) and read it back. Float first, 16-bit if the
     * remote-submix HAL refuses float.
     */
    private fun startSystemMode(intent: Intent, host: String, port: Int, rate: Int, format: StreamProtocol.Format) {
        if (!SystemCapture.isPrivileged(this)) {
            fail("System mode needs the app installed as a system app (see System mode card)")
            return
        }
        val session = try {
            SystemCapture.open(this, rate, AudioFormat.ENCODING_PCM_FLOAT)
        } catch (e: Exception) {
            Log.w(TAG, "float loop-back failed (${e.message}), trying 16-bit")
            try {
                SystemCapture.open(this, rate, AudioFormat.ENCODING_PCM_16BIT)
            } catch (e2: Exception) {
                fail("System capture failed: ${e2.message}")
                return
            }
        }
        systemSession = session
        record = session.record
        if (intent.getBooleanExtra(EXTRA_FORWARD_VOLUME, true)) startVolumeForwarding()
        go(session.record, host, port, rate, format, mute = false, system = true)
    }

    private fun go(rec: AudioRecord, host: String, port: Int, rate: Int, format: StreamProtocol.Format, mute: Boolean, system: Boolean) {
        acquireLocks()
        if (mute) mutePhone()

        running = true
        readErrors = 0
        StreamState.running = true
        StreamState.systemMode = system
        StreamState.error = null
        StreamState.host = "$host:$port"
        StreamState.rate = rate
        StreamState.packets = 0
        StreamState.kbps = 0.0
        StreamState.seconds = 0
        StreamState.readErrors = 0
        StreamState.sourceBits = 0
        StreamState.phoneVolume = phoneVolume
        val modeLabel = if (system) "System" else "Streaming"
        StreamState.status = "$modeLabel ${rate / 1000.0} kHz ${format.label}"
        notifyText = "Streaming to $host · ${rate / 1000.0} kHz ${format.label}" + if (system) " · system mode" else ""
        signalLevel = readSignalLevel()
        notify(notifyText)
        StreamTileService.requestUpdate(this)

        worker = Thread({ streamLoop(rec, host, port, rate, format) }, "hfs-capture").also { it.start() }
    }

    // ---- phone volume → receiver ----------------------------------------------

    private fun readPhoneVolume(): Float {
        val am = getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max else 1f
    }

    private fun startVolumeForwarding() {
        // While forwarding, the media volume *is* the streaming volume, so it is kept
        // apart from the speaker volume: the speaker level is saved now and restored
        // when streaming stops, and the keys start from where they were last time.
        val am = getSystemService(AudioManager::class.java)
        val settings = Settings(this)
        var applyingStreamLevel = false
        try {
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            savedVolume = if (settings.savedVolume >= 0) settings.savedVolume else current
            settings.savedVolume = savedVolume
            val sv = settings.streamVolume
            if (sv >= 0 && sv != current) {
                // The loop-back route was just requested; let it take hold before the
                // level changes, so the speaker never plays at the streaming level.
                applyingStreamLevel = true
                mainHandler.postDelayed({
                    applyingStreamLevel = false
                    if (!running || volumeReceiver == null) return@postDelayed
                    try {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, sv, 0)
                    } catch (_: Exception) {
                    }
                    phoneVolume = readPhoneVolume()
                    volumeDirty = true
                    StreamState.phoneVolume = phoneVolume
                }, 400)
            }
        } catch (_: Exception) {
        }
        // Until the streaming level is applied, the receiver plays at that level too,
        // not at the speaker level the media volume still holds.
        phoneVolume = settings.streamVolume.takeIf { applyingStreamLevel && it >= 0 }
            ?.let { it.toFloat() / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC) } ?: readPhoneVolume()
        volumeDirty = true
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
                phoneVolume = readPhoneVolume()
                volumeDirty = true
                StreamState.phoneVolume = phoneVolume
                try {
                    settings.streamVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                } catch (_: Exception) {
                }
            }
        }
        registerReceiver(r, IntentFilter(VOLUME_CHANGED_ACTION))
        volumeReceiver = r
    }

    private fun stopVolumeForwarding() {
        volumeReceiver?.let { unregisterReceiver(it) }
        volumeReceiver = null
        phoneVolume = -1f
        StreamState.phoneVolume = -1f
    }

    private fun streamLoop(rec: AudioRecord, host: String, port: Int, rate: Int, format: StreamProtocol.Format) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val framesPerPacket = StreamProtocol.framesPerPacket(rate, CHANNELS, format)
        mainHandler.post { StreamState.framesPerPacket = framesPerPacket }
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

        // After an unclean end of a previous session (app killed while streaming) the
        // audio server can still hold the old loop-back client for a moment: retry.
        var attempts = 0
        while (true) {
            rec.startRecording()
            if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) break
            if (++attempts >= 5) {
                mainHandler.post { fail("Capture did not start (is another app capturing?)") }
                return
            }
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
            }
        }

        // Capture is split in two: a reader that only drains AudioRecord (so its blocking
        // reads are clean observations of when Android delivered audio) and this thread,
        // which stamps and paces packets out of the ring buffer (see PacketClock).
        val clock = PacketClock(rate)
        val ring = SampleRing(rate * CHANNELS)              // one second
        val reader = Thread({ readLoop(rec, rate, clock, ring, framesPerPacket) }, "hfs-read").also { it.start() }

        val samples = FloatArray(framesPerPacket * CHANNELS)
        val buf = ByteBuffer.allocate(StreamProtocol.HEADER_SIZE + samples.size * format.bytesPerSample)
            .order(ByteOrder.LITTLE_ENDIAN)
        val packet = DatagramPacket(buf.array(), 0, address, port)
        // Every packet sent is kept for a while so the receiver can ask for it again
        // (HFS_NACK) when it notices a gap: a resend usually lands well inside its
        // jitter buffer, which is how a lost packet becomes silence-free.
        val history = PacketHistory(StreamProtocol.HISTORY, buf.capacity())
        val nackThread = Thread({ nackLoop(socket, history, address, port) }, "hfs-nack").also { it.start() }
        var seq = 0
        var framePos = 0L          // audio position of the next frame to send
        var packets = 0L
        var bytes = 0L
        var sendErrors = 0L
        val startMs = SystemClock.elapsedRealtime()
        var lastStatMs = startMs
        var windowBytes = 0L
        try {
            var lastVolMs = 0L
            var groupSendUs = 0L
            while (running) {
                if (!ring.take(samples, samples.size, 200)) continue
                // Packets leave in small groups: one frame per TXOP would cost the Wi-Fi
                // driver its A-MPDU aggregation and congest the air, one burst per HAL
                // period is what the pacing exists to avoid.
                if (seq % paceGroup == 0) {
                    clock.waitUntilDue(framePos)
                    groupSendUs = SystemClock.elapsedRealtimeNanos() / 1000
                }
                // The wire timestamp is the departure time (shared by a group), so the
                // receiver's jitter figure measures the network and nothing else.
                buf.clear()
                StreamProtocol.writeHeader(buf, seq, format, CHANNELS, rate, framesPerPacket, groupSendUs)
                StreamProtocol.writeSamples(buf, samples, samples.size, format)
                packet.setData(buf.array(), 0, buf.position())
                try {
                    socket.send(packet)
                } catch (e: Exception) {
                    sendErrors++
                }
                history.put(seq, buf.array(), buf.position())
                framePos += framesPerPacket
                seq++
                packets++
                bytes += buf.position()
                windowBytes += buf.position()

                val now = SystemClock.elapsedRealtime()
                val vol = phoneVolume
                if (vol >= 0f && (volumeDirty || now - lastVolMs >= 1000)) {
                    volumeDirty = false
                    lastVolMs = now
                    try {
                        val msg = "${StreamProtocol.VOLUME} %.4f".format(java.util.Locale.ROOT, vol).toByteArray()
                        socket.send(DatagramPacket(msg, msg.size, address, port))
                    } catch (_: Exception) {
                    }
                }
                if (now - lastStatMs >= 500) {
                    val kbps = windowBytes * 8.0 / (now - lastStatMs)
                    val p = packets; val e = sendErrors + readErrors + ring.overruns; val secs = (now - startMs) / 1000
                    val lat = clock.latencyUs / 1000.0; val pace = clock.paceUs / 1000.0
                    val period = 1000.0 * clock.periodFrames / rate
                    val lp = clock.latePackets
                    val rs = history.resent
                    mainHandler.post {
                        updateSignalIcon()
                        StreamState.resent = rs
                        StreamState.packets = p
                        StreamState.kbps = kbps
                        StreamState.seconds = secs
                        StreamState.readErrors = e
                        StreamState.captureLatencyMs = lat
                        StreamState.capturePeriodMs = period
                        StreamState.paceMs = pace
                        StreamState.latePackets = lp
                    }
                    windowBytes = 0
                    lastStatMs = now
                }
            }
        } finally {
            try {
                reader.join(2000)
            } catch (_: InterruptedException) {
            }
            try {
                val bye = StreamProtocol.BYE.toByteArray()
                socket.send(DatagramPacket(bye, bye.size, address, port))
            } catch (_: Exception) {
            }
            socket.close()          // also unblocks the NACK thread
            try {
                nackThread.join(1000)
            } catch (_: InterruptedException) {
            }
            Log.i(TAG, "sent $packets packets, $bytes bytes")
        }
    }

    /** Answers the receiver's retransmission requests from the packet history. */
    private fun nackLoop(socket: DatagramSocket, history: PacketHistory, address: InetAddress, port: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val nack = StreamProtocol.NACK.toByteArray()
        val rx = ByteArray(512)
        val rxPacket = DatagramPacket(rx, rx.size)
        val resend = DatagramPacket(ByteArray(0), 0, address, port)
        while (running) {
            try {
                rxPacket.setData(rx, 0, rx.size)
                socket.receive(rxPacket)
            } catch (_: Exception) {
                if (socket.isClosed) return
                continue
            }
            val n = rxPacket.length
            if (n < nack.size + 2 || !rx.copyOfRange(0, nack.size).contentEquals(nack)) continue
            var i = nack.size
            while (i + 1 < n) {
                val seq = (rx[i].toInt() and 0xFF) or ((rx[i + 1].toInt() and 0xFF) shl 8)
                i += 2
                history.get(seq)?.let { (data, len) ->
                    resend.setData(data, 0, len)
                    try {
                        socket.send(resend)
                        history.resent++
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    @Volatile private var readErrors = 0L
    private var paceGroup = DEFAULT_PACE_GROUP

    /** Drains AudioRecord into the ring as fast as Android delivers, feeding the clock. */
    private fun readLoop(rec: AudioRecord, rate: Int, clock: PacketClock, ring: SampleRing, framesPerRead: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // A blocking read of one packet returns the moment a HAL period lands; the rest of
        // that period is then drained without blocking so the clock sees the whole delivery
        // (first frame, last frame, time) as one observation.
        val samples = FloatArray(rate * CHANNELS / 4)          // 250 ms, more than any HAL period
        val shorts = if (rec.audioFormat == AudioFormat.ENCODING_PCM_16BIT) ShortArray(samples.size) else null
        val head = framesPerRead * CHANNELS
        var framePos = 0L
        var analysed = 0L          // non-zero samples inspected for the source resolution
        var hiRes = false
        fun read(offset: Int, count: Int, mode: Int): Int =
            if (shorts != null) {
                val m = rec.read(shorts, offset, count, mode)
                for (i in offset until offset + maxOf(m, 0)) samples[i] = shorts[i] / 32768f
                m
            } else {
                rec.read(samples, offset, count, mode)
            }
        while (running) {
            val readStartUs = SystemClock.elapsedRealtimeNanos() / 1000
            var n = read(0, head, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                readErrors++
                if (n < 0) {
                    mainHandler.post { fail("Capture read error $n") }
                    running = false
                    break
                }
                continue
            }
            val blockedUs = SystemClock.elapsedRealtimeNanos() / 1000 - readStartUs
            if (n == head) {
                val more = read(head, samples.size - head, AudioRecord.READ_NON_BLOCKING)
                if (more > 0) n += more
            }
            val frames = n / CHANNELS
            clock.onRead(framePos, frames, blockedUs)
            framePos += frames
            ring.put(samples, n)
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
        stopVolumeForwarding()
        systemSession?.let {
            // Put the speaker volume back *while* the loop-back still owns the media
            // players, and give AudioService a moment to apply it; otherwise the
            // speaker plays at the streaming level between the two steps.
            restoreVolume()
            try {
                Thread.sleep(150)
            } catch (_: InterruptedException) {
            }
            it.close()          // stops/releases the record and unregisters the policy
            systemSession = null
            record = null
        }
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
        StreamState.systemMode = false
        restoreVolume()
        releaseLocks()
        StreamState.running = false
        StreamState.status = reason
        StreamState.kbps = 0.0
        StreamTileService.requestUpdate(this)
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

    // ---- status-bar signal icon ------------------------------------------------

    private var notifyText = ""
    @Volatile private var signalLevel = 4          // 0..4 bars, from the Wi-Fi link

    /** Wi-Fi signal level as 0..4 bars, like the system's own icon. */
    private fun readSignalLevel(): Int {
        return try {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val rssi = wifi.connectionInfo.rssi
            if (Build.VERSION.SDK_INT >= 30) {
                val max = wifi.maxSignalLevel
                if (max > 0) wifi.calculateSignalLevel(rssi) * 4 / max else 4
            } else {
                @Suppress("DEPRECATION")
                WifiManager.calculateSignalLevel(rssi, 5)
            }
        } catch (_: Exception) {
            4
        }.coerceIn(0, 4)
    }

    /** Called from the stats loop: refreshes the notification icon when the bars change. */
    private fun updateSignalIcon() {
        if (!running) return
        val level = readSignalLevel()
        if (level != signalLevel) {
            signalLevel = level
            notify(notifyText)
        }
    }

    private fun signalIcon() = when (signalLevel) {
        0 -> R.drawable.ic_signal_0
        1 -> R.drawable.ic_signal_1
        2 -> R.drawable.ic_signal_2
        3 -> R.drawable.ic_signal_3
        else -> R.drawable.ic_signal_4
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
            .setSmallIcon(if (running) signalIcon() else R.drawable.ic_stat_stream)
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
