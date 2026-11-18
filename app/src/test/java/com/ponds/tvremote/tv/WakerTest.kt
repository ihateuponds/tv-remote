package com.ponds.tvremote.tv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WakerTest {
    @Test fun packetIs102Bytes() = assertEquals(102, Waker.magicPacket("00:11:22:33:44:55").size)

    @Test fun packetStartsWithSixFF() =
        assertTrue(Waker.magicPacket("00:11:22:33:44:55").take(6).all { it == 0xFF.toByte() })

    @Test fun packetRepeatsMac16Times() {
        val p = Waker.magicPacket("00-11-22-33-44-55")
        val mac = byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55)
        for (i in 0 until 16) assertArrayEquals(mac, p.copyOfRange(6 + i * 6, 12 + i * 6))
    }

    @Test(expected = IllegalArgumentException::class)
    fun badMacThrows() { Waker.magicPacket("nope") }

    @Test fun targetsCoverBroadcastsAndIp() = assertEquals(
        setOf(
            "255.255.255.255" to 9, "255.255.255.255" to 7,
            "192.168.1.255" to 9, "192.168.1.255" to 7,
            "192.168.1.50" to 9, "192.168.1.50" to 7,
        ),
        Waker.targets("192.168.1.50").toSet(),
    )
}
