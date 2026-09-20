package com.hifistream.sender

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tile: one tap starts or stops streaming. Starting straight from the
 * tile only works in system mode (no MediaProjection consent dialog is needed there);
 * otherwise the tile opens the app.
 */
class StreamTileService : TileService() {
    companion object {
        fun requestUpdate(context: Context) =
            requestListeningState(context, ComponentName(context, StreamTileService::class.java))
    }

    override fun onStartListening() = refresh()

    override fun onClick() {
        val settings = Settings(this)
        when {
            StreamState.running -> startForegroundService(CaptureService.stopIntent(this))
            settings.systemMode && SystemCapture.isPrivileged(this) && settings.host.isNotBlank() -> {
                StreamState.deviceName = DeviceStore(this).selected()?.name ?: ""
                startForegroundService(CaptureService.systemStartIntent(this, settings))
            }
            else -> {
                val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >= 34) {
                    startActivityAndCollapse(android.app.PendingIntent.getActivity(
                        this, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE))
                } else {
                    startActivityAndCollapse(open)
                }
            }
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val running = StreamState.running
        val settings = Settings(this)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, if (running) R.drawable.ic_signal_4 else R.drawable.ic_stat_stream)
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= 29) {
            val last = DeviceStore(this).selected()
            tile.subtitle = when {
                running -> StreamState.deviceName.ifBlank { StreamState.host.ifBlank { "Streaming" } }
                settings.systemMode && SystemCapture.isPrivileged(this) && last != null -> last.name
                else -> "Open to start"
            }
        }
        tile.updateTile()
    }
}
