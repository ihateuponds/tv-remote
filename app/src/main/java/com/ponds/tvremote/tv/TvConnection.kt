package com.ponds.tvremote.tv

import com.ponds.tvremote.data.Settings
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient

data class ConnectResult(val result: PairResult, val seenPin: String?)

interface Connection {
    suspend fun connect(settings: Settings, onPrompt: () -> Unit): ConnectResult
    fun disconnect()
    /** Re-open the button socket if it's missing (e.g. the TV was in standby when we connected). */
    suspend fun ensureButtons()
    /** Called when an established connection drops on its own. */
    var onDropped: (() -> Unit)?
}

/**
 * The live link to the TV: SSAP over wss:3001 (pinned cert), falling back to ws:3000 only when this TV has
 * never been pinned, plus the pointer socket for buttons.
 */
class TvConnection(
    private val base: OkHttpClient,
    private val wssPort: Int = 3001,
    private val wsPort: Int = 3000,
) : Connection, SsapApi {
    @Volatile private var ssap: SsapClient? = null
    @Volatile private var pointer: PointerSocket? = null
    @Volatile private var http: OkHttpClient = base
    @Volatile var latestSettings: Settings = Settings()
        private set
    override var onDropped: (() -> Unit)? = null

    override suspend fun connect(settings: Settings, onPrompt: () -> Unit): ConnectResult {
        disconnect()
        latestSettings = settings
        val pin = TlsPin(settings.certPin)
        val tlsHttp = pin.okHttp(base)
        var client = SsapClient(tlsHttp)
        var result = client.connect("wss://${settings.ip}:$wssPort", settings.clientKey, onPrompt)
        var seen = pin.seenPin
        var http = tlsHttp

        if (result is PairResult.Unreachable && settings.certPin == null) {
            val plain = SsapClient(base)
            val r2 = plain.connect("ws://${settings.ip}:$wsPort", settings.clientKey, onPrompt)
            if (r2 !is PairResult.Unreachable) { client = plain; result = r2; seen = null; http = base }
        }

        if (result is PairResult.Paired) {
            client.onClosed = { onDropped?.invoke() }
            ssap = client
            this.http = http
            ensureButtons()
        }
        return ConnectResult(result, seen)
    }

    override suspend fun request(uri: String, payload: JsonObject): JsonObject =
        (ssap ?: throw SsapError("not connected")).request(uri, payload)

    override suspend fun ensureButtons() {
        val client = ssap ?: return
        if (pointer?.isOpen == true) return
        pointer = PointerSocket(http).also { p ->
            try { p.openFrom(client) } catch (_: SsapError) { /* retried on the next press or wake */ }
        }
    }

    fun press(b: Button): Boolean = pointer?.press(b) ?: false

    override fun disconnect() {
        ssap?.let { it.onClosed = null; it.close() }
        ssap = null
        pointer?.close()
        pointer = null
    }
}
