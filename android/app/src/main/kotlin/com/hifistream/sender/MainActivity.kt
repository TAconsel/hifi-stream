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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!StreamState.running) CaptureService.restoreSavedVolume(this)
        setContent { AppTheme { App() } }
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


/** Which screen is showing; a tiny navigation state instead of a nav library. */
private sealed class Screen {
    data object Devices : Screen()
    data class Device(val id: String) : Screen()
    data object AppSettings : Screen()
}

/**
 * Starting a stream needs permission and (outside system mode) the MediaProjection consent
 * dialog, which are activity concerns; this object bundles them so any screen can start.
 */
private class Starter(
    private val activity: Activity,
    private val store: DeviceStore,
    private val requestProjection: (Device) -> Unit,
    private val requestPermissions: (Array<String>, Device) -> Unit,
) {
    fun start(device: Device) {
        StreamState.error = null
        if (device.host.isBlank()) {
            StreamState.error = "This device has no address yet — open its settings"
            return
        }
        store.select(device)
        StreamState.deviceName = device.name
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { activity.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) requestProjection(device) else requestPermissions(missing.toTypedArray(), device)
    }

    fun stop() {
        activity.startService(CaptureService.stopIntent(activity))
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val settings = remember { Settings(ctx) }
    val store = remember { DeviceStore(ctx) }
    var screen by remember { mutableStateOf<Screen>(Screen.Devices) }
    var devices by remember { mutableStateOf(store.list()) }
    val privileged = remember { SystemCapture.isPrivileged(ctx) }
    var pendingDevice by remember { mutableStateOf<Device?>(null) }

    fun launchService(device: Device, resultCode: Int, data: Intent?) {
        val system = settings.systemMode && privileged
        val intent = Intent(ctx, CaptureService::class.java)
            .setAction(CaptureService.ACTION_START)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_RESULT_DATA, data)
            .putExtra(CaptureService.EXTRA_SYSTEM, system)
            .putExtra(CaptureService.EXTRA_FORWARD_VOLUME, device.forwardVolume)
            .putExtra(CaptureService.EXTRA_HOST, device.host)
            .putExtra(CaptureService.EXTRA_PORT, device.port)
            .putExtra(CaptureService.EXTRA_RATE, device.rate)
            .putExtra(CaptureService.EXTRA_FORMAT, device.format.id)
            .putExtra(CaptureService.EXTRA_MUTE, device.mute)
        ctx.startForegroundService(intent)
    }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        val d = pendingDevice
        if (res.resultCode == Activity.RESULT_OK && data != null && d != null) launchService(d, res.resultCode, data)
        else StreamState.error = "Screen/audio capture permission denied"
    }
    fun requestProjection(device: Device) {
        if (settings.systemMode && privileged) {
            launchService(device, 0, null)     // no consent dialog needed in system mode
            return
        }
        pendingDevice = device
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
        val d = pendingDevice
        if (grants[Manifest.permission.RECORD_AUDIO] == true && d != null) requestProjection(d)
        else StreamState.error = "Microphone permission is required for playback capture"
    }
    val starter = remember {
        Starter(activity, store, ::requestProjection) { missing, d -> pendingDevice = d; permissionLauncher.launch(missing) }
    }

    when (val sc = screen) {
        Screen.Devices -> DeviceListScreen(
            devices = devices,
            privileged = privileged,
            onConnect = { d ->
                if (StreamState.running && StreamState.host == d.address) starter.stop() else starter.start(d)
            },
            onStop = { starter.stop() },
            onDeviceSettings = { screen = Screen.Device(it.id) },
            onAppSettings = { screen = Screen.AppSettings },
            onAdd = { d -> store.save(d); devices = store.list(); if (devices.size == 1) store.select(d) },
        )
        is Screen.Device -> {
            val d = devices.firstOrNull { it.id == sc.id }
            if (d == null) screen = Screen.Devices
            else DeviceSettingsScreen(
                device = d, privileged = privileged,
                connected = StreamState.running && StreamState.host == d.address,
                onChange = { nd -> store.save(nd); devices = store.list() },
                onForget = { store.remove(d.id); devices = store.list(); screen = Screen.Devices },
                onBack = { screen = Screen.Devices },
            )
        }
        Screen.AppSettings -> AppSettingsScreen(onBack = { screen = Screen.Devices })
    }
}

// ---- Device list ------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceListScreen(
    devices: List<Device>,
    privileged: Boolean,
    onConnect: (Device) -> Unit,
    onStop: () -> Unit,
    onDeviceSettings: (Device) -> Unit,
    onAppSettings: () -> Unit,
    onAdd: (Device) -> Unit,
) {
    var showAdd by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HiFi Stream") },
                actions = { IconButton(onClick = onAppSettings) { Icon(Icons.Default.Settings, contentDescription = "App settings") } }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) }, text = { Text("Add device") })
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            StatusCard(onStop = onStop)
            Card {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text("Saved devices", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    if (devices.isEmpty()) {
                        Text("No devices yet. Tap “Add device” to find the receiver on this Wi-Fi.",
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                    }
                    devices.forEachIndexed { i, d ->
                        val connected = StreamState.running && StreamState.host == d.address
                        val busyElsewhere = StreamState.running && !connected
                        ListItem(
                            modifier = Modifier.clickable { onConnect(d) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = {
                                Icon(painterResource(if (connected) barsIcon(StreamState.linkBars) else R.drawable.ic_stat_stream),
                                    contentDescription = null, modifier = Modifier.size(28.dp),
                                    tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            headlineContent = { Text(d.name, fontWeight = if (connected) FontWeight.SemiBold else FontWeight.Normal) },
                            supportingContent = {
                                Text(
                                    when {
                                        connected -> "Connected · ${StreamState.linkSummary.ifBlank { "streaming" }}"
                                        busyElsewhere -> "${d.address} · tap to switch"
                                        else -> "${d.address} · ${rateLabel(d.rate)} · ${d.format.label}"
                                    },
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { onDeviceSettings(d) }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Device settings")
                                }
                            }
                        )
                        if (i < devices.lastIndex) HorizontalDivider()
                    }
                }
            }
            Text(
                "Tap a device to stream to it; tap again to stop. The gear opens that device's own settings " +
                "(address, sample rate, bit depth…). Long-press the Quick Settings tile to come back here.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(72.dp))
        }
    }
    if (showAdd) AddDeviceDialog(existing = devices, onAdd = { onAdd(it); showAdd = false }, onDismiss = { showAdd = false })
}

private fun barsIcon(bars: Int) = when (bars) {
    0 -> R.drawable.ic_signal_0
    1 -> R.drawable.ic_signal_1
    2 -> R.drawable.ic_signal_2
    3 -> R.drawable.ic_signal_3
    else -> R.drawable.ic_signal_4
}

private fun rateLabel(r: Int) = if (r % 1000 == 0) "${r / 1000} kHz" else "${r / 1000.0} kHz"

/** Bluetooth-style pairing dialog: scans for receivers, lists them, or takes an address by hand. */
@Composable
private fun AddDeviceDialog(existing: List<Device>, onAdd: (Device) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(true) }
    var found by remember { mutableStateOf<List<Receiver>>(emptyList()) }
    var manual by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(StreamProtocol.DEFAULT_PORT.toString()) }
    var name by remember { mutableStateOf("") }
    var scanNo by remember { mutableStateOf(0) }

    LaunchedEffect(scanNo) {
        scanning = true
        found = withContext(Dispatchers.IO) { try { Discovery.find(StreamProtocol.DEFAULT_PORT) } catch (_: Exception) { emptyList() } }
        scanning = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (manual) "Add device by address" else "Available devices") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!manual) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (scanning) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Looking for receivers on this Wi-Fi…", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(if (found.isEmpty()) "No receiver answered. Is it running on the PC, on the same Wi-Fi?"
                                 else "Tap a receiver to add it.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    found.forEach { r ->
                        val already = existing.any { it.host == r.address }
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable(enabled = !already) {
                                onAdd(Device(name = r.name, host = r.address))
                            },
                            leadingContent = { Icon(painterResource(R.drawable.ic_stat_stream), contentDescription = null) },
                            headlineContent = { Text(r.name) },
                            supportingContent = { Text(if (already) "${r.address} · already added" else r.address) },
                        )
                    }
                } else {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("PC address") },
                            singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = port, onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("Port") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp))
                    }
                }
            }
        },
        confirmButton = {
            if (manual) {
                TextButton(enabled = host.isNotBlank(), onClick = {
                    onAdd(Device(name = name.ifBlank { host.trim() }, host = host.trim(),
                        port = port.toIntOrNull() ?: StreamProtocol.DEFAULT_PORT))
                }) { Text("Add") }
            } else {
                TextButton(enabled = !scanning, onClick = { scanNo++ }) {
                    Icon(Icons.Default.Refresh, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Scan again")
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { manual = !manual }) { Text(if (manual) "Scan instead" else "Enter address") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

// ---- Per-device settings ----------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSettingsScreen(
    device: Device,
    privileged: Boolean,
    connected: Boolean,
    onChange: (Device) -> Unit,
    onForget: () -> Unit,
    onBack: () -> Unit,
) {
    var name by remember(device.id) { mutableStateOf(device.name) }
    var host by remember(device.id) { mutableStateOf(device.host) }
    var port by remember(device.id) { mutableStateOf(device.port.toString()) }
    var confirmForget by remember { mutableStateOf(false) }
    val editable = !connected
    fun commitText() {
        val p = port.toIntOrNull() ?: StreamProtocol.DEFAULT_PORT
        val nd = device.copy(name = name.trim().ifBlank { device.name }, host = host.trim(), port = p)
        if (nd != device) onChange(nd)
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(device.name) },
            navigationIcon = { IconButton(onClick = { commitText(); onBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (connected) Text("Connected — stop streaming to change the address or format.", style = MaterialTheme.typography.bodySmall)
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Device", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("PC address") },
                            singleLine = true, enabled = editable, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = port, onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("Port") }, singleLine = true, enabled = editable,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(96.dp))
                    }
                    TextButton(onClick = { commitText() }) { Text("Save") }
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Audio", style = MaterialTheme.typography.titleMedium)
                    Text("Sample rate", style = MaterialTheme.typography.labelMedium)
                    val rates = listOf(44100, 48000, 96000)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        rates.forEachIndexed { i, r ->
                            SegmentedButton(selected = device.rate == r, onClick = { onChange(device.copy(rate = r)) },
                                enabled = editable, shape = SegmentedButtonDefaults.itemShape(i, rates.size)) { Text(rateLabel(r)) }
                        }
                    }
                    Text("Bit depth", style = MaterialTheme.typography.labelMedium)
                    val formats = StreamProtocol.Format.entries
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        formats.forEachIndexed { i, f ->
                            SegmentedButton(selected = device.format == f, onClick = { onChange(device.copy(format = f)) },
                                enabled = editable, shape = SegmentedButtonDefaults.itemShape(i, formats.size)) { Text(f.label) }
                        }
                    }
                    val kbps = device.rate * 2 * device.format.bytesPerSample * 8 / 1000
                    val fpp = StreamProtocol.framesPerPacket(device.rate, 2, device.format)
                    Text("$kbps kbit/s · $fpp frames per packet (${"%.1f".format(fpp * 1000.0 / device.rate)} ms)",
                        style = MaterialTheme.typography.bodySmall)
                    if (!privileged) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Mute phone while streaming")
                                Text("Sets media volume to 0 so only the PC plays; restored on stop", style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = device.mute, onCheckedChange = { onChange(device.copy(mute = it)) }, enabled = editable)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Forward phone volume to PC")
                                Text("Volume keys set this receiver's volume while streaming (kept apart from the speaker volume)",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = device.forwardVolume, onCheckedChange = { onChange(device.copy(forwardVolume = it)) }, enabled = editable)
                        }
                    }
                }
            }
            OutlinedButton(onClick = { confirmForget = true }, enabled = editable, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Delete, contentDescription = null); Spacer(Modifier.width(8.dp)); Text("Forget device")
            }
        }
    }
    if (confirmForget) AlertDialog(
        onDismissRequest = { confirmForget = false },
        title = { Text("Forget ${device.name}?") },
        text = { Text("Its address and settings are removed from this phone.") },
        confirmButton = { TextButton(onClick = { confirmForget = false; onForget() }) { Text("Forget") } },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancel") } },
    )
}

// ---- App settings (system mode, root, HAL, tile, test tone) -----------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val scope = rememberCoroutineScope()
    var testTone by remember { mutableStateOf(TestTone.playing) }
    var systemMode by remember { mutableStateOf(settings.systemMode) }
    val privileged = remember { SystemCapture.isPrivileged(ctx) }
    var hasRoot by remember { mutableStateOf<Boolean?>(null) }
    var moduleInstalled by remember { mutableStateOf<Boolean?>(null) }
    var rootBusy by remember { mutableStateOf(false) }
    var rootMessage by remember { mutableStateOf<String?>(null) }
    var needsReboot by remember { mutableStateOf(false) }
    var halPatched by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val r = RootInstaller.hasRoot()
            val m = if (r) RootInstaller.isModuleInstalled() else false
            val h = if (m) HalPatcher.isInstalled() else false
            hasRoot = r; moduleInstalled = m; halPatched = h
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } })
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("System mode (rooted phones)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (privileged) "Installed as a privileged system app ✓ — media is routed to the PC like an external audio device: the phone stays silent, no screen-share prompt, volume keys control the stream."
                        else "Installs a Magisk module that makes this app a privileged system app (CAPTURE_AUDIO_OUTPUT + MODIFY_AUDIO_ROUTING). Then audio is routed to the PC exclusively, like a connected audio device.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Use system mode")
                            Text(if (privileged) "Exclusive routing, no consent dialog" else "Needs the system app install below",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = systemMode && privileged, onCheckedChange = { systemMode = it; settings.systemMode = it },
                            enabled = privileged && !StreamState.running)
                    }
                    if (privileged) {
                        var clockSteer by remember { mutableStateOf(settings.clockSteer) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Lossless rate matching (clock trim)")
                                Text("When the receiver asks for a rate correction, trim the phone's clock by that many ppm (adjtimex, root) so the whole audio pipeline follows the receiver's clock — no resampling anywhere. Off: resample on the phone instead. Takes effect at the next start.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(checked = clockSteer, onCheckedChange = { clockSteer = it; settings.clockSteer = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Quick Settings tile")
                                Text("Tap: stream to the last device; long-press: open the device list. The status-bar icon shows the link quality the receiver reports.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { requestQuickSettingsTile(ctx) }) { Text("Add tile") }
                        }
                    }
                    when (hasRoot) {
                        null -> Text("Checking root…", style = MaterialTheme.typography.bodySmall)
                        false -> Text("No root access (Magisk) on this phone — system mode unavailable.",
                            style = MaterialTheme.typography.bodySmall)
                        true -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (moduleInstalled != true) {
                                Button(enabled = !rootBusy, onClick = {
                                    rootBusy = true; rootMessage = null
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { RootInstaller.install(ctx) }
                                        rootBusy = false
                                        rootMessage = if (r.ok) "Module installed. Reboot to activate system mode." else "Install failed:\n${r.log}"
                                        if (r.ok) { needsReboot = true; moduleInstalled = true }
                                    }
                                }) { Text("Install as system app") }
                            } else {
                                OutlinedButton(enabled = !rootBusy, onClick = {
                                    rootBusy = true; rootMessage = null
                                    scope.launch {
                                        val r = withContext(Dispatchers.IO) { RootInstaller.uninstall() }
                                        rootBusy = false
                                        rootMessage = if (r.ok) "Module will be removed on the next reboot." else "Remove failed:\n${r.log}"
                                        if (r.ok) { needsReboot = true; moduleInstalled = false }
                                    }
                                }) { Text("Remove system app") }
                            }
                            if (needsReboot) Button(onClick = { scope.launch { withContext(Dispatchers.IO) { RootInstaller.reboot() } } }) { Text("Reboot now") }
                            if (rootBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (privileged && hasRoot == true && moduleInstalled == true) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Hi-res capture", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (halPatched == true) "Remote-submix HAL patched: the loop-back pipe runs in 32-bit float at the selected sample rate (44.1 / 48 / 96 kHz)."
                                else "Android's remote-submix HAL forces the loop-back pipe to 16-bit / 48 kHz. The patch changes two constants in a copy of that library (float instead of 16-bit, keep the requested rate); the copy lives in the Magisk module, the original is untouched.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (halPatched != true) {
                                    Button(enabled = !rootBusy && !StreamState.running, onClick = {
                                        rootBusy = true; rootMessage = null
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) { HalPatcher.install(ctx.cacheDir) }
                                            rootBusy = false
                                            rootMessage = if (r.ok) "HAL patched (${r.message}). Reboot to activate." else "Patch not applied: ${r.message}"
                                            if (r.ok) { needsReboot = true; halPatched = true }
                                        }
                                    }) { Text("Patch HAL for 24-bit / 96 kHz") }
                                } else {
                                    OutlinedButton(enabled = !rootBusy && !StreamState.running, onClick = {
                                        rootBusy = true; rootMessage = null
                                        scope.launch {
                                            val r = withContext(Dispatchers.IO) { HalPatcher.uninstall() }
                                            rootBusy = false
                                            rootMessage = r.message
                                            if (r.ok) { needsReboot = true; halPatched = false }
                                        }
                                    }) { Text("Restore stock HAL") }
                                }
                                if (needsReboot) Button(onClick = { scope.launch { withContext(Dispatchers.IO) { RootInstaller.reboot() } } }) { Text("Reboot now") }
                            }
                        }
                    }
                    rootMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Play test tone")
                            Text("1 kHz tone through the media path to check the link", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = testTone, onCheckedChange = {
                            testTone = it
                            if (it) TestTone.start(settings.rate) else TestTone.stop()
                        })
                    }
                }
            }
            Text(
                "Without system mode the app captures what other apps play using Android's playback capture — no root. " +
                "Apps that opt out of capture (some DRM video/music apps) stay silent. Phone calls and " +
                "notifications are never captured.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// ---- Status -------------------------------------------------------------------------

@Composable
private fun StatusCard(onStop: () -> Unit) {
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
                Text("→ ${StreamState.deviceName.ifBlank { StreamState.host }} (${StreamState.host})", style = MaterialTheme.typography.bodyMedium)
                val s = StreamState.seconds
                Text(
                    "${StreamState.packets} packets · ${"%.0f".format(StreamState.kbps)} kbit/s · " +
                        "%d:%02d".format(s / 60, s % 60) +
                        (if (StreamState.readErrors > 0) " · ${StreamState.readErrors} errors" else ""),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (StreamState.capturePeriodMs > 0) {
                    Text("Android delivers audio every %.1f ms (worst %.0f ms) · packets paced %.0f ms behind capture".format(
                        StreamState.capturePeriodMs, StreamState.captureLatencyMs, StreamState.paceMs) +
                        (if (StreamState.latePackets > 0) " · ${StreamState.latePackets} late" else "") +
                        (if (StreamState.resent > 0) " · ${StreamState.resent} resent" else ""),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (StreamState.linkSummary.isNotEmpty()) {
                    Text("Link ${"▮".repeat(StreamState.linkBars)}${"▯".repeat(4 - StreamState.linkBars)} · ${StreamState.linkSummary}",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (StreamState.systemMode) {
                    Text("System mode · exclusive routing" +
                        (if (StreamState.phoneVolume >= 0f) " · phone volume ${(StreamState.phoneVolume * 100).toInt()} %" else ""),
                        style = MaterialTheme.typography.bodySmall)
                }
                when (StreamState.sourceBits) {
                    16 -> Text("Captured audio is 16-bit (Android's playback-capture limit on this phone)",
                        style = MaterialTheme.typography.bodySmall)
                    24 -> Text("Captured audio carries more than 16 bits" +
                        (if (StreamState.rate != 48000) " (includes Android's resampling)" else ""),
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("Stop streaming") }
            } else {
                Text("Choose a device below to start streaming.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Asks the system to add our Quick Settings tile (Android 13+); earlier versions get a hint. */
private fun requestQuickSettingsTile(ctx: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        val sbm = ctx.getSystemService(android.app.StatusBarManager::class.java)
        sbm.requestAddTileService(
            android.content.ComponentName(ctx, StreamTileService::class.java),
            ctx.getString(R.string.app_name),
            android.graphics.drawable.Icon.createWithResource(ctx, R.drawable.ic_stat_stream),
            { it.run() }, { }
        )
    } else {
        android.widget.Toast.makeText(ctx,
            "Open Quick Settings, tap the edit (pencil) button and drag the HiFi Stream tile in",
            android.widget.Toast.LENGTH_LONG).show()
    }
}
