package com.hifistream.sender

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!StreamState.running) CaptureService.restoreSavedVolume(this)
        setContent { AppTheme { MainScreen() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val settings = remember { Settings(ctx) }
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(settings.host) }
    var port by remember { mutableStateOf(settings.port.toString()) }
    var rate by remember { mutableStateOf(settings.rate) }
    var format by remember { mutableStateOf(settings.format) }
    var mute by remember { mutableStateOf(settings.mutePhone) }
    var receivers by remember { mutableStateOf<List<Receiver>>(emptyList()) }
    var discovering by remember { mutableStateOf(false) }
    var discoverMessage by remember { mutableStateOf<String?>(null) }
    var testTone by remember { mutableStateOf(TestTone.playing) }

    fun startService(resultCode: Int, data: Intent) {
        settings.host = host.trim()
        settings.port = port.toIntOrNull() ?: StreamProtocol.DEFAULT_PORT
        settings.rate = rate
        settings.format = format
        settings.mutePhone = mute
        val intent = Intent(ctx, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            .putExtra(CaptureService.EXTRA_HOST, settings.host)
            .putExtra(CaptureService.EXTRA_PORT, settings.port)
            .putExtra(CaptureService.EXTRA_RATE, rate)
            .putExtra(CaptureService.EXTRA_FORMAT, format.id)
            .putExtra(CaptureService.EXTRA_MUTE, mute)
        ctx.startForegroundService(intent)
    }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) {
            startService(res.resultCode, data)
        } else {
            StreamState.error = "Screen/audio capture permission denied"
        }
    }

    fun requestProjection() {
        val mpm = ctx.getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Whole-display config skips the "single app" chooser; audio capture is system-wide anyway.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) requestProjection()
        else StreamState.error = "Microphone permission is required for playback capture"
    }

    fun onStart() {
        StreamState.error = null
        if (host.isBlank()) {
            StreamState.error = "Enter the receiver's IP address or tap Discover"
            return
        }
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) requestProjection() else permissionLauncher.launch(missing.toTypedArray())
    }

    fun onStop() {
        ctx.startService(CaptureService.stopIntent(ctx))
    }

    fun discover() {
        discovering = true
        discoverMessage = null
        scope.launch {
            val p = port.toIntOrNull() ?: StreamProtocol.DEFAULT_PORT
            val list = withContext(Dispatchers.IO) {
                try { Discovery.find(p) } catch (e: Exception) { emptyList() }
            }
            receivers = list
            discovering = false
            if (list.isEmpty()) discoverMessage = "No receiver found — is it running and on the same Wi-Fi?"
            else if (list.size == 1) host = list[0].address
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("HiFi Stream") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusCard()

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Receiver", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = host, onValueChange = { host = it },
                            label = { Text("PC address") }, singleLine = true,
                            enabled = !StreamState.running,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = port, onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("Port") }, singleLine = true,
                            enabled = !StreamState.running,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(96.dp)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { discover() }, enabled = !discovering && !StreamState.running) {
                            Text("Discover receivers")
                        }
                        if (discovering) CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    }
                    receivers.forEach { r ->
                        AssistChip(onClick = { host = r.address }, label = { Text("${r.name}  ·  ${r.address}") })
                    }
                    discoverMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Audio", style = MaterialTheme.typography.titleMedium)
                    Text("Sample rate", style = MaterialTheme.typography.labelMedium)
                    val rates = listOf(44100, 48000, 96000)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        rates.forEachIndexed { i, r ->
                            SegmentedButton(
                                selected = rate == r, onClick = { rate = r },
                                enabled = !StreamState.running,
                                shape = SegmentedButtonDefaults.itemShape(i, rates.size)
                            ) { Text(if (r % 1000 == 0) "${r / 1000} kHz" else "${r / 1000.0} kHz") }
                        }
                    }
                    Text("Android's playback capture is 16-bit / 48 kHz on most phones; 96 kHz is upsampled by Android.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Bit depth", style = MaterialTheme.typography.labelMedium)
                    val formats = StreamProtocol.Format.entries
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        formats.forEachIndexed { i, f ->
                            SegmentedButton(
                                selected = format == f, onClick = { format = f },
                                enabled = !StreamState.running,
                                shape = SegmentedButtonDefaults.itemShape(i, formats.size)
                            ) { Text(f.label) }
                        }
                    }
                    val kbps = rate * 2 * format.bytesPerSample * 8 / 1000
                    val fpp = StreamProtocol.framesPerPacket(rate, 2, format)
                    Text(
                        "$kbps kbit/s · $fpp frames per packet (${"%.1f".format(fpp * 1000.0 / rate)} ms)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Play test tone")
                            Text("1 kHz tone through the media path to check the link",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = testTone, onCheckedChange = {
                            testTone = it
                            if (it) TestTone.start(rate) else TestTone.stop()
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Mute phone while streaming")
                            Text("Sets media volume to 0 so only the PC plays; restored on stop",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = mute, onCheckedChange = { mute = it }, enabled = !StreamState.running)
                    }
                }
            }

            if (StreamState.running) {
                Button(onClick = { onStop() }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Stop streaming") }
            } else {
                Button(onClick = { onStart() }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Start streaming") }
            }

            Text(
                "Captures what other apps play (media, games) using Android's playback capture — no root. " +
                "Apps that opt out of capture (some DRM video/music apps) stay silent. Phone calls and " +
                "notifications are never captured.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun StatusCard() {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                StreamState.status,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            StreamState.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (StreamState.running) {
                Text("→ ${StreamState.host}", style = MaterialTheme.typography.bodyMedium)
                val s = StreamState.seconds
                Text(
                    "${StreamState.packets} packets · ${"%.0f".format(StreamState.kbps)} kbit/s · " +
                        "%d:%02d".format(s / 60, s % 60) +
                        (if (StreamState.readErrors > 0) " · ${StreamState.readErrors} errors" else ""),
                    style = MaterialTheme.typography.bodyMedium
                )
                when (StreamState.sourceBits) {
                    16 -> Text("Captured audio is 16-bit (Android's playback-capture limit on this phone)",
                        style = MaterialTheme.typography.bodySmall)
                    24 -> Text("Captured audio carries more than 16 bits" +
                        (if (StreamState.rate != 48000) " (includes Android's resampling)" else ""),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
