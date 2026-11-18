package com.ponds.tvremote.tv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.LinkedBlockingQueue

/** Scripted stand-in for the TV's SSAP socket. `onMessage` gets each parsed frame and the socket to reply on. */
class FakeTv(val onMessage: (JsonObject, WebSocket) -> Unit) : WebSocketListener() {
    val received = LinkedBlockingQueue<JsonObject>()

    override fun onMessage(webSocket: WebSocket, text: String) {
        val msg = Json.parseToJsonElement(text).jsonObject
        received.add(msg)
        onMessage(msg, webSocket)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
    }

    fun enqueueOn(server: MockWebServer) = server.enqueue(MockResponse().withWebSocketUpgrade(this))

    companion object {
        fun JsonObject.id() = this["id"]!!.jsonPrimitive.content
        fun JsonObject.type() = this["type"]!!.jsonPrimitive.content
        fun JsonObject.uri() = this["uri"]?.jsonPrimitive?.content

        /** Answers register with PROMPT then registered(client-key = key). */
        fun pairing(key: String = "abc", then: (JsonObject, WebSocket) -> Unit = { _, _ -> }) =
            FakeTv { m, ws ->
                if (m.type() == "register") {
                    ws.send("""{"type":"response","id":"${m.id()}","payload":{"pairingType":"PROMPT"}}""")
                    ws.send("""{"type":"registered","id":"${m.id()}","payload":{"client-key":"$key"}}""")
                } else then(m, ws)
            }
    }
}
