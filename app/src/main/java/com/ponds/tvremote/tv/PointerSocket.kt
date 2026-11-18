package com.ponds.tvremote.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Remote-control buttons sent over the TV's pointer input socket. */
enum class Button(val wire: String) {
    UP("UP"), DOWN("DOWN"), LEFT("LEFT"), RIGHT("RIGHT"), ENTER("ENTER"), BACK("BACK"), HOME("HOME"),
    MUTE("MUTE"), VOLUMEUP("VOLUMEUP"), VOLUMEDOWN("VOLUMEDOWN"),
    NUM_0("0"), NUM_1("1"), NUM_2("2"), NUM_3("3"), NUM_4("4"),
    NUM_5("5"), NUM_6("6"), NUM_7("7"), NUM_8("8"), NUM_9("9"),
}

/** The TV's second socket, used for D-pad style button presses. */
class PointerSocket(private val http: OkHttpClient) {
    @Volatile private var socket: WebSocket? = null

    val isOpen: Boolean get() = socket != null

    suspend fun openFrom(ssap: SsapApi) {
        val path = ssap.request("ssap://com.webos.service.networkinput/getPointerInputSocket")["socketPath"]
            ?.jsonPrimitive?.contentOrNull ?: throw SsapError("TV gave no pointer socket")
        open(path)
    }

    suspend fun open(socketPath: String) {
        close()
        val opened = CompletableDeferred<WebSocket>()
        val ws = http.newWebSocket(Request.Builder().url(socketPath).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(webSocket) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(t)
                if (socket === webSocket) socket = null
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                if (socket === webSocket) socket = null
            }
        })
        try {
            socket = withTimeout(5_000) { opened.await() }
        } catch (e: CancellationException) {
            ws.cancel()
            if (e is TimeoutCancellationException) throw SsapError("button socket timed out")
            throw e
        } catch (t: Throwable) {
            ws.cancel()
            throw SsapError("couldn't open button socket: ${t.message}")
        }
    }

    /** Returns false (never throws) when the socket isn't open. */
    fun press(b: Button): Boolean = socket?.send("type:button\nname:${b.wire}\n\n") ?: false

    fun close() {
        socket?.close(1000, null)
        socket = null
    }
}
