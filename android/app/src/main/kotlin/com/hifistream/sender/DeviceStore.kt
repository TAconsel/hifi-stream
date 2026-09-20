package com.hifistream.sender

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A saved receiver with its own streaming settings, like a paired Bluetooth device. */
data class Device(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = StreamProtocol.DEFAULT_PORT,
    val rate: Int = 48000,
    val format: StreamProtocol.Format = StreamProtocol.Format.S24,
    val mute: Boolean = true,
    val forwardVolume: Boolean = true,
) {
    val address get() = "$host:$port"
}

/** The saved device list, persisted as JSON in shared preferences. */
class DeviceStore(context: Context) {
    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)
    private val settings = Settings(context)

    fun list(): List<Device> {
        val raw = prefs.getString("list", null)
        if (raw == null) return migrate()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(id: String): Device? = list().firstOrNull { it.id == id }

    fun save(device: Device) {
        val l = list().toMutableList()
        val i = l.indexOfFirst { it.id == device.id }
        if (i >= 0) l[i] = device else l += device
        write(l)
        if (settings.lastDeviceId == device.id) select(device)
    }

    fun remove(id: String) {
        write(list().filter { it.id != id })
        if (settings.lastDeviceId == id) settings.lastDeviceId = ""
    }

    /**
     * Makes this device the one the Quick Settings tile starts and what the service reads:
     * the legacy single-receiver settings mirror the selected device.
     */
    fun select(device: Device) {
        settings.lastDeviceId = device.id
        settings.host = device.host
        settings.port = device.port
        settings.rate = device.rate
        settings.format = device.format
        settings.mutePhone = device.mute
        settings.forwardVolume = device.forwardVolume
    }

    fun selected(): Device? = settings.lastDeviceId.takeIf { it.isNotEmpty() }?.let { get(it) }

    /** First run after the update: the single receiver that was configured becomes the first device. */
    private fun migrate(): List<Device> {
        val l = if (settings.host.isNotBlank()) listOf(
            Device(name = "PC", host = settings.host, port = settings.port, rate = settings.rate,
                format = settings.format, mute = settings.mutePhone, forwardVolume = settings.forwardVolume)
        ) else emptyList()
        write(l)
        l.firstOrNull()?.let { settings.lastDeviceId = it.id }
        return l
    }

    private fun write(l: List<Device>) {
        val arr = JSONArray()
        l.forEach { arr.put(toJson(it)) }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    private fun toJson(d: Device) = JSONObject()
        .put("id", d.id).put("name", d.name).put("host", d.host).put("port", d.port)
        .put("rate", d.rate).put("format", d.format.id).put("mute", d.mute).put("forwardVolume", d.forwardVolume)

    private fun fromJson(o: JSONObject) = Device(
        id = o.getString("id"), name = o.getString("name"), host = o.getString("host"),
        port = o.optInt("port", StreamProtocol.DEFAULT_PORT), rate = o.optInt("rate", 48000),
        format = StreamProtocol.Format.fromId(o.optInt("format", StreamProtocol.Format.S24.id)),
        mute = o.optBoolean("mute", true), forwardVolume = o.optBoolean("forwardVolume", true),
    )
}
