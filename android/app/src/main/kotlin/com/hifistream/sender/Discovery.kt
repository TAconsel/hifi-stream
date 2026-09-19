package com.hifistream.sender

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

data class Receiver(val address: String, val name: String)

/** Finds receivers on the local network with a UDP broadcast. Blocking; call off the main thread. */
object Discovery {
    fun find(port: Int, timeoutMs: Int = 1500): List<Receiver> {
        val found = LinkedHashMap<String, Receiver>()
        DatagramSocket().use { sock ->
            sock.broadcast = true
            sock.soTimeout = 200
            val msg = StreamProtocol.DISCOVER.toByteArray()
            val targets = mutableSetOf(InetAddress.getByName("255.255.255.255"))
            try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                    if (!nif.isUp || nif.isLoopback) return@forEach
                    nif.interfaceAddresses.forEach { ia -> ia.broadcast?.let { targets.add(it) } }
                }
            } catch (_: Exception) {
            }
            targets.forEach { addr ->
                try {
                    sock.send(DatagramPacket(msg, msg.size, addr, port))
                } catch (_: Exception) {
                }
            }
            val buf = ByteArray(256)
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val text = String(pkt.data, 0, pkt.length).trim()
                if (text.startsWith(StreamProtocol.HERE)) {
                    val name = text.removePrefix(StreamProtocol.HERE).trim().ifEmpty { "receiver" }
                    val ip = pkt.address.hostAddress ?: continue
                    found[ip] = Receiver(ip, name)
                }
            }
        }
        return found.values.toList()
    }
}
