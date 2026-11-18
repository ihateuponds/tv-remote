package com.ponds.tvremote.tv

import com.ponds.tvremote.data.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Input(val id: String, val label: String)

interface Tv {
    suspend fun isOn(): Boolean
    suspend fun powerOff()
    suspend fun wake()
    fun press(b: Button): Boolean
    suspend fun play()
    suspend fun pause()
    suspend fun inputs(): List<Input>
    suspend fun setInput(id: String)
    suspend fun launch(appId: String)
    /** Types into whatever text box is focused on the TV. */
    suspend fun typeText(text: String)
    suspend fun sendEnter()
    suspend fun deleteChars(count: Int)
}

class TvController(
    private val ssap: SsapApi,
    private val pressButton: (Button) -> Boolean,
    private val settings: () -> Settings,
    private val waker: suspend (mac: String, ip: String) -> Unit = Waker::wake,
) : Tv {
    /** The TV keeps its network up in standby, so ask for the real power state. */
    override suspend fun isOn(): Boolean = try {
        val state = ssap.request("ssap://com.webos.service.tvpower/power/getPowerState")["state"]
            ?.jsonPrimitive?.contentOrNull
        state == "Active" || state == "Screen Off" || state == "Screen On" ||
            state?.startsWith("Screen Saver") == true // the screensaver runs while the TV is on
    } catch (_: SsapError) {
        false
    }

    override suspend fun powerOff() { ssap.request("ssap://system/turnOff") }

    override suspend fun wake() { settings().let { waker(it.mac, it.ip) } }

    override fun press(b: Button): Boolean = pressButton(b)

    override suspend fun play() { ssap.request("ssap://media.controls/play") }

    override suspend fun pause() { ssap.request("ssap://media.controls/pause") }

    override suspend fun inputs(): List<Input> =
        ssap.request("ssap://tv/getExternalInputList")["devices"]?.jsonArray.orEmpty().mapNotNull { d ->
            val o = d.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Input(id, o["label"]?.jsonPrimitive?.contentOrNull ?: id)
        }

    override suspend fun typeText(text: String) {
        ssap.request("ssap://com.webos.service.ime/insertText",
            JsonObject(mapOf("text" to JsonPrimitive(text), "replace" to JsonPrimitive(false))))
    }

    override suspend fun sendEnter() { ssap.request("ssap://com.webos.service.ime/sendEnterKey") }

    override suspend fun deleteChars(count: Int) {
        ssap.request("ssap://com.webos.service.ime/deleteCharacters", JsonObject(mapOf("count" to JsonPrimitive(count))))
    }

    override suspend fun launch(appId: String) {
        ssap.request("ssap://system.launcher/launch", JsonObject(mapOf("id" to JsonPrimitive(appId))))
    }

    override suspend fun setInput(id: String) {
        ssap.request("ssap://tv/switchInput", JsonObject(mapOf("inputId" to JsonPrimitive(id))))
    }
}
