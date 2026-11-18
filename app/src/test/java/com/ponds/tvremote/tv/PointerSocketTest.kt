package com.ponds.tvremote.tv

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class PointerSocketTest {
    private lateinit var server: MockWebServer
    private val frames = LinkedBlockingQueue<String>()

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { frames.add(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
    }
    @After fun tearDown() { server.shutdown() }

    @Test fun pressSendsButtonFrame() = runBlocking {
        val p = PointerSocket(OkHttpClient())
        p.open("ws://${server.hostName}:${server.port}/resources/abc/netinput.pointer.sock")
        assertTrue(p.press(Button.UP))
        assertEquals("type:button\nname:UP\n\n", frames.poll(2, TimeUnit.SECONDS))
        p.close()
    }

    @Test fun pressWhenClosedReturnsFalse() {
        assertFalse(PointerSocket(OkHttpClient()).press(Button.ENTER))
    }

    @Test fun openFromAsksTvForSocketPath() = runBlocking {
        val url = "ws://${server.hostName}:${server.port}/sock"
        var askedUri: String? = null
        val api = SsapApi { uri, _ ->
            askedUri = uri
            kotlinx.serialization.json.JsonObject(mapOf("socketPath" to kotlinx.serialization.json.JsonPrimitive(url)))
        }
        val p = PointerSocket(OkHttpClient())
        p.openFrom(api)
        assertEquals("ssap://com.webos.service.networkinput/getPointerInputSocket", askedUri)
        assertTrue(p.press(Button.HOME))
        assertEquals("type:button\nname:HOME\n\n", frames.poll(2, TimeUnit.SECONDS))
        p.close()
    }

    @Test fun cancelDuringOpenThrowsCancellation() = runBlocking {
        val slow = MockWebServer().apply { start() }
        slow.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        var thrown: Throwable? = null
        val job = this.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { PointerSocket(OkHttpClient()).open("ws://${slow.hostName}:${slow.port}/") } catch (t: Throwable) { thrown = t; throw t }
        }
        kotlinx.coroutines.delay(300); job.cancel(); job.join()
        assertTrue("got $thrown", thrown is kotlinx.coroutines.CancellationException)
        slow.shutdown()
    }

    @Test fun numberButtonSendsDigitName() = runBlocking {
        val p = PointerSocket(OkHttpClient())
        p.open("ws://${server.hostName}:${server.port}/sock")
        p.press(Button.NUM_7)
        assertEquals("type:button\nname:7\n\n", frames.poll(2, TimeUnit.SECONDS))
        p.close()
    }
}
