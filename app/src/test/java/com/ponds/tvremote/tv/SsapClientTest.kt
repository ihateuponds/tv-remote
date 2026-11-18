package com.ponds.tvremote.tv

import com.ponds.tvremote.tv.FakeTv.Companion.id
import com.ponds.tvremote.tv.FakeTv.Companion.type
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class SsapClientTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private fun url() = "ws://${server.hostName}:${server.port}"

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun pairsAndReturnsClientKey() = runBlocking {
        FakeTv.pairing("abc").enqueueOn(server)
        var prompted = false
        val r = SsapClient(http).connect(url(), null, onPrompt = { prompted = true })
        assertEquals(PairResult.Paired("abc"), r)
        assertTrue(prompted)
    }

    @Test fun sendsSavedClientKey() = runBlocking {
        val tv = FakeTv.pairing("abc").also { it.enqueueOn(server) }
        SsapClient(http).connect(url(), "abc")
        val first = tv.received.poll(2, TimeUnit.SECONDS)!!
        assertEquals("register", first.type())
        assertEquals("abc", first["payload"]!!.jsonObject["client-key"]!!.jsonPrimitive.content)
    }

    @Test fun firstPairingSendsNoClientKey() = runBlocking {
        val tv = FakeTv.pairing().also { it.enqueueOn(server) }
        SsapClient(http).connect(url(), null)
        assertEquals(null, tv.received.poll(2, TimeUnit.SECONDS)!!["payload"]!!.jsonObject["client-key"])
    }

    @Test fun declinedPairing() = runBlocking {
        FakeTv { m, ws ->
            ws.send("""{"type":"error","id":"${m.id()}","error":"403 User denied access"}""")
        }.enqueueOn(server)
        assertEquals(PairResult.Declined, SsapClient(http).connect(url(), null))
    }

    @Test fun pairingTimeoutCountsAsDeclined() = runBlocking {
        FakeTv { m, ws ->
            ws.send("""{"type":"response","id":"${m.id()}","payload":{"pairingType":"PROMPT"}}""")
        }.enqueueOn(server)
        assertEquals(PairResult.Declined, SsapClient(http, pairTimeoutMs = 300).connect(url(), null))
    }

    @Test fun requestMatchesById() = runBlocking {
        val held = mutableListOf<Pair<String, okhttp3.WebSocket>>()
        FakeTv.pairing { m, ws ->
            held += m.id() to ws
            if (held.size == 2) {
                // answer in reverse order
                for ((id, s) in held.reversed()) s.send("""{"type":"response","id":"$id","payload":{"echo":"$id"}}""")
            }
        }.enqueueOn(server)
        val c = SsapClient(http)
        c.connect(url(), null)
        val a = async { c.request("ssap://a") }
        val b = async { c.request("ssap://b") }
        val ra = a.await(); val rb = b.await()
        assertEquals(held[0].first, ra["echo"]!!.jsonPrimitive.content)
        assertEquals(held[1].first, rb["echo"]!!.jsonPrimitive.content)
    }

    @Test fun requestSendsUriAndPayload() = runBlocking {
        val tv = FakeTv.pairing { m, ws -> ws.send("""{"type":"response","id":"${m.id()}","payload":{}}""") }
            .also { it.enqueueOn(server) }
        val c = SsapClient(http)
        c.connect(url(), null)
        c.request("ssap://tv/switchInput", kotlinx.serialization.json.JsonObject(mapOf("inputId" to JsonPrimitive("HDMI_2"))))
        tv.received.poll(2, TimeUnit.SECONDS) // register
        val req = tv.received.poll(2, TimeUnit.SECONDS)!!
        assertEquals("request", req.type())
        assertEquals("ssap://tv/switchInput", req["uri"]!!.jsonPrimitive.content)
        assertEquals("HDMI_2", req["payload"]!!.jsonObject["inputId"]!!.jsonPrimitive.content)
    }

    @Test fun requestErrorThrows() = runBlocking {
        FakeTv.pairing { m, ws -> ws.send("""{"type":"error","id":"${m.id()}","error":"404 no such service"}""") }
            .enqueueOn(server)
        val c = SsapClient(http)
        c.connect(url(), null)
        try { c.request("ssap://nope"); fail("expected SsapError") }
        catch (e: SsapError) { assertTrue(e.message!!.contains("404")) }
    }

    @Test fun requestTimesOut() = runBlocking {
        FakeTv.pairing { _, _ -> }.enqueueOn(server)
        val c = SsapClient(http, timeoutMs = 300)
        c.connect(url(), null)
        val start = System.nanoTime()
        try { c.request("ssap://slow"); fail("expected SsapError") } catch (_: SsapError) {}
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000)
    }

    @Test fun requestWhenClosedThrows() = runBlocking {
        try { SsapClient(http).request("ssap://x"); fail("expected SsapError") } catch (_: SsapError) {}
    }

    @Test fun unreachable() = runBlocking {
        val port = server.port
        server.shutdown()
        val r = SsapClient(http).connect("ws://127.0.0.1:$port", null)
        assertTrue(r is PairResult.Unreachable)
    }

    @Test fun closeReportsClosed() = runBlocking {
        FakeTv.pairing().enqueueOn(server)
        val c = SsapClient(http)
        c.connect(url(), null)
        assertTrue(c.isOpen)
        c.close()
        assertTrue(!c.isOpen)
    }

    @Test fun cancelDuringPairingClosesSocket() = runBlocking {
        val closed = java.util.concurrent.CountDownLatch(1)
        server.enqueue(okhttp3.mockwebserver.MockResponse().withWebSocketUpgrade(object : okhttp3.WebSocketListener() {
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) { closed.countDown(); webSocket.close(1000, null) }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) { closed.countDown() }
        }))
        val job = launch(kotlinx.coroutines.Dispatchers.IO) { SsapClient(http).connect(url(), null) }
        kotlinx.coroutines.delay(500)
        job.cancel(); job.join()
        assertTrue("socket left open", closed.await(3, TimeUnit.SECONDS))
    }

    @Test fun cancelDuringOpenPropagates() = runBlocking {
        server.enqueue(okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        var result: PairResult? = null
        val job = launch(kotlinx.coroutines.Dispatchers.IO) { result = SsapClient(http).connect(url(), null) }
        kotlinx.coroutines.delay(300)
        job.cancel(); job.join()
        assertEquals(null, result)
    }
}
