package com.ponds.tvremote.tv

import com.ponds.tvremote.data.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvControllerTest {
    private val sent = mutableListOf<Pair<String, JsonObject>>()
    private fun api(reply: (String) -> String) = SsapApi { uri, payload ->
        sent += uri to payload
        Json.parseToJsonElement(reply(uri)).jsonObject
    }
    private fun tv(api: SsapApi, waker: suspend (String, String) -> Unit = { _, _ -> }, pressed: MutableList<Button> = mutableListOf()) =
        TvController(api, { pressed += it; true }, { Settings() }, waker)

    private fun powerState(state: String) = tv(api { """{"state":"$state","returnValue":true}""" })

    @Test fun activeMeansOn() = runBlocking { assertTrue(powerState("Active").isOn()) }
    @Test fun standbyMeansOff() = runBlocking { assertFalse(powerState("Active Standby").isOn()) }
    @Test fun suspendMeansOff() = runBlocking { assertFalse(powerState("Suspend").isOn()) }
    @Test fun screenOffCountsAsOn() = runBlocking { assertTrue(powerState("Screen Off").isOn()) }
    @Test fun screenSaverCountsAsOn() = runBlocking {
        for (s in listOf("Screen Saver", "Screen Saver Ready", "Screen On")) assertTrue(s, powerState(s).isOn())
    }
    @Test fun powerOffMeansOff() = runBlocking {
        for (s in listOf("Power Off", "Power Off Ready", "Suspend Ready", "Always Ready", "Screen Down")) assertFalse(s, powerState(s).isOn())
    }

    @Test fun requestErrorMeansOff() = runBlocking {
        assertFalse(tv(SsapApi { _, _ -> throw SsapError("not connected") }).isOn())
    }

    @Test fun isOnAsksPowerState() = runBlocking {
        powerState("Active").isOn()
        assertEquals("ssap://com.webos.service.tvpower/power/getPowerState", sent.single().first)
    }

    @Test fun wakeUsesSettingsMacAndIp() = runBlocking {
        var args: Pair<String, String>? = null
        tv(api { "{}" }, waker = { mac, ip -> args = mac to ip }).wake()
        assertEquals("00:11:22:33:44:55" to "192.168.1.50", args)
    }

    @Test fun powerOffUri() = runBlocking {
        tv(api { "{}" }).powerOff()
        assertEquals("ssap://system/turnOff", sent.single().first)
    }

    @Test fun playPauseUris() = runBlocking {
        val t = tv(api { "{}" }); t.play(); t.pause()
        assertEquals(listOf("ssap://media.controls/play", "ssap://media.controls/pause"), sent.map { it.first })
    }

    @Test fun inputsParsed() = runBlocking {
        val t = tv(api { """{"devices":[{"id":"HDMI_1","label":"PC","icon":"x"},{"id":"HDMI_2","label":"HDMI 2"}]}""" })
        assertEquals(listOf(Input("HDMI_1", "PC"), Input("HDMI_2", "HDMI 2")), t.inputs())
        assertEquals("ssap://tv/getExternalInputList", sent.single().first)
    }

    @Test fun setInputSendsId() = runBlocking {
        tv(api { "{}" }).setInput("HDMI_2")
        assertEquals("ssap://tv/switchInput", sent.single().first)
        assertEquals("HDMI_2", sent.single().second["inputId"]!!.jsonPrimitive.content)
    }

    @Test fun pressGoesToPointer() {
        val pressed = mutableListOf<Button>()
        assertTrue(tv(api { "{}" }, pressed = pressed).press(Button.MUTE))
        assertEquals(listOf(Button.MUTE), pressed)
    }

    @Test fun launchSendsAppId() = runBlocking {
        tv(api { "{}" }).launch("io.freemytv")
        assertEquals("ssap://system.launcher/launch", sent.single().first)
        assertEquals("io.freemytv", sent.single().second["id"]!!.jsonPrimitive.content)
    }

    @Test fun typeTextInsertsWithoutReplacing() = runBlocking {
        tv(api { "{}" }).typeText("hunter2 é")
        assertEquals("ssap://com.webos.service.ime/insertText", sent.single().first)
        assertEquals("hunter2 é", sent.single().second["text"]!!.jsonPrimitive.content)
        assertEquals("false", sent.single().second["replace"]!!.jsonPrimitive.content)
    }

    @Test fun enterAndBackspaceUris() = runBlocking {
        val t = tv(api { "{}" }); t.sendEnter(); t.deleteChars(1)
        assertEquals(listOf("ssap://com.webos.service.ime/sendEnterKey", "ssap://com.webos.service.ime/deleteCharacters"), sent.map { it.first })
        assertEquals("1", sent[1].second["count"]!!.jsonPrimitive.content)
    }
}
