package com.ponds.tvremote.tv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed interface PairResult {
    data class Paired(val clientKey: String) : PairResult
    data object Declined : PairResult
    data class CertChanged(val newPin: String) : PairResult
    data class Unreachable(val cause: Throwable) : PairResult
}

class SsapError(message: String) : Exception(message)

/** Anything that can send an SSAP request. */
fun interface SsapApi {
    suspend fun request(uri: String, payload: JsonObject): JsonObject
}

suspend fun SsapApi.request(uri: String): JsonObject = request(uri, JsonObject(emptyMap()))

/** One SSAP (LG webOS remote protocol) WebSocket connection. */
class SsapClient(
    private val http: OkHttpClient,
    private val timeoutMs: Long = 5_000,
    private val pairTimeoutMs: Long = 60_000,
) : SsapApi {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val nextId = AtomicInteger(1)
    private var registration: Channel<JsonObject>? = null
    @Volatile private var socket: WebSocket? = null

    /** Called once when an open connection drops (not when [close] is called). */
    @Volatile var onClosed: (() -> Unit)? = null

    val isOpen: Boolean get() = socket != null

    suspend fun connect(url: String, clientKey: String?, onPrompt: () -> Unit = {}): PairResult {
        close()
        val opened = CompletableDeferred<WebSocket>()
        val reg = Channel<JsonObject>(Channel.UNLIMITED).also { registration = it }

        val ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.complete(webSocket) }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val id = msg["id"]?.jsonPrimitive?.contentOrNull
                if (id == REG_ID) { reg.trySend(msg); return }
                val waiter = id?.let { pending.remove(it) } ?: return
                if (msg.type() == "error") waiter.completeExceptionally(SsapError(msg.errorText()))
                else waiter.complete(msg["payload"]?.jsonObject ?: JsonObject(emptyMap()))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                opened.completeExceptionally(t)
                dropped(webSocket, t)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                dropped(webSocket, SsapError("closed by TV: $code $reason"))
            }
        })

        try {
            withTimeout(timeoutMs) { opened.await() }
        } catch (e: TimeoutCancellationException) {
            ws.cancel()
            return PairResult.Unreachable(e)
        } catch (e: CancellationException) {
            ws.cancel()
            throw e
        } catch (t: Throwable) {
            ws.cancel()
            val mismatch = t.causes().filterIsInstance<PinMismatch>().firstOrNull()
            return if (mismatch != null) PairResult.CertChanged(mismatch.seenPin) else PairResult.Unreachable(t)
        }

        ws.send(Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
            "type" to JsonPrimitive("register"),
            "id" to JsonPrimitive(REG_ID),
            "payload" to registerPayload(clientKey),
        ))))

        return try {
            withTimeout(pairTimeoutMs) {
                while (true) {
                    val m = reg.receive()
                    when (m.type()) {
                        "registered" -> {
                            val key = m["payload"]?.jsonObject?.get("client-key")?.jsonPrimitive?.contentOrNull
                                ?: return@withTimeout fail(ws, PairResult.Declined)
                            socket = ws
                            return@withTimeout PairResult.Paired(key)
                        }
                        "error" -> return@withTimeout fail(ws, PairResult.Declined)
                        else -> if (m["payload"]?.jsonObject?.get("pairingType")?.jsonPrimitive?.contentOrNull == "PROMPT") onPrompt()
                    }
                }
                @Suppress("UNREACHABLE_CODE") PairResult.Declined
            }
        } catch (_: TimeoutCancellationException) {
            fail(ws, PairResult.Declined)
        } catch (e: CancellationException) {
            ws.cancel()
            throw e
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            fail(ws, PairResult.Unreachable(e))
        }
    }

    override suspend fun request(uri: String, payload: JsonObject): JsonObject {
        val ws = socket ?: throw SsapError("not connected")
        val id = nextId.getAndIncrement().toString()
        val waiter = CompletableDeferred<JsonObject>().also { pending[id] = it }
        val sent = ws.send(Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf(
            "type" to JsonPrimitive("request"),
            "id" to JsonPrimitive(id),
            "uri" to JsonPrimitive(uri),
            "payload" to payload,
        ))))
        if (!sent) { pending.remove(id); throw SsapError("not connected") }
        return try {
            withTimeout(timeoutMs) { waiter.await() }
        } catch (_: TimeoutCancellationException) {
            pending.remove(id)
            throw SsapError("TV didn't answer $uri")
        }
    }

    fun close() {
        val ws = socket
        socket = null
        registration?.close()
        ws?.close(1000, null)
        failPending(SsapError("closed"))
    }

    private fun dropped(ws: WebSocket, t: Throwable) {
        registration?.close(t)
        if (socket === ws) {
            socket = null
            failPending(SsapError("connection lost"))
            onClosed?.invoke()
        }
    }

    private fun failPending(e: Throwable) {
        val all = pending.values.toList()
        pending.clear()
        all.forEach { it.completeExceptionally(e) }
    }

    private fun fail(ws: WebSocket, r: PairResult): PairResult { ws.close(1000, null); return r }

    private fun JsonObject.type() = this["type"]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.errorText() = this["error"]?.jsonPrimitive?.contentOrNull ?: "TV error"
    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private companion object { const val REG_ID = "reg0" }
}
