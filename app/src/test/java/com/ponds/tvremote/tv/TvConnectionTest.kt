package com.ponds.tvremote.tv

import com.ponds.tvremote.data.Settings
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

class TvConnectionTest {
    private lateinit var server: MockWebServer
    private val closedPort = ServerSocket(0).use { it.localPort }

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun conn() = TvConnection(OkHttpClient(), wssPort = closedPort, wsPort = server.port)

    @Test fun fallsBackToWsWhenNeverPinned() = runBlocking {
        FakeTv.pairing("k").enqueueOn(server)
        val c = conn()
        val r = c.connect(Settings(ip = "127.0.0.1", certPin = null), {})
        assertEquals(PairResult.Paired("k"), r.result)
        c.disconnect()
    }

    @Test fun noPlaintextFallbackOncePinned() = runBlocking {
        FakeTv.pairing("k").enqueueOn(server)
        val r = conn().connect(Settings(ip = "127.0.0.1", certPin = "aa"), {})
        assertTrue("got ${r.result}", r.result is PairResult.Unreachable)
        assertEquals(0, server.requestCount)
    }

    @Test fun requestWhenNotConnectedThrows() = runBlocking {
        try { conn().request("ssap://x"); throw AssertionError("expected SsapError") } catch (_: SsapError) {}
    }
}
