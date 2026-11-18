package com.ponds.tvremote.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Wake-on-LAN: turns the TV on from standby ("Turn on via Wi-Fi" must be enabled on the TV). */
object Waker {
    private val PORTS = listOf(9, 7)

    fun parseMac(mac: String): ByteArray {
        val parts = mac.trim().split(':', '-')
        require(parts.size == 6 && parts.all { it.length == 2 }) { "Bad MAC address: $mac" }
        return ByteArray(6) { i ->
            parts[i].toIntOrNull(16)?.toByte() ?: throw IllegalArgumentException("Bad MAC address: $mac")
        }
    }

    fun magicPacket(mac: String): ByteArray {
        val m = parseMac(mac)
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { m[it % 6] }
    }

    fun targets(tvIp: String): List<Pair<String, Int>> {
        val hosts = buildList {
            add("255.255.255.255")
            val octets = tvIp.split('.')
            if (octets.size == 4) add(octets.take(3).joinToString(".") + ".255")
            add(tvIp)
        }
        return hosts.flatMap { h -> PORTS.map { h to it } }
    }

    suspend fun wake(mac: String, tvIp: String) = withContext(Dispatchers.IO) {
        val packet = magicPacket(mac)
        DatagramSocket().use { s ->
            s.broadcast = true
            for ((host, port) in targets(tvIp)) {
                try {
                    s.send(DatagramPacket(packet, packet.size, InetAddress.getByName(host), port))
                } catch (_: IOException) {
                    // one target failing (e.g. no route) must not stop the others
                }
            }
        }
    }
}
