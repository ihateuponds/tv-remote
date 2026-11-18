package com.ponds.tvremote.ui

import com.ponds.tvremote.data.Settings
import com.ponds.tvremote.data.SettingsStore
import com.ponds.tvremote.tv.Button
import com.ponds.tvremote.tv.ConnectResult
import com.ponds.tvremote.tv.Connection
import com.ponds.tvremote.tv.Input
import com.ponds.tvremote.tv.PairResult
import com.ponds.tvremote.tv.SsapError
import com.ponds.tvremote.tv.Tv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteViewModelTest {
    private val sched = TestCoroutineScheduler()

    class FakeStore : SettingsStore {
        var s = Settings()
        override suspend fun get() = s
        override suspend fun update(f: (Settings) -> Settings) { s = f(s) }
    }

    inner class FakeConnection(vararg results: ConnectResult) : Connection {
        val queue = ArrayDeque(results.toList())
        val connectTimes = mutableListOf<Long>()
        var disconnects = 0
        var promptOnConnect = false
        var ensureButtonsCalls = 0
        var throwOnConnect: Exception? = null
        override suspend fun ensureButtons() { ensureButtonsCalls++ }
        override var onDropped: (() -> Unit)? = null
        override suspend fun connect(settings: Settings, onPrompt: () -> Unit): ConnectResult {
            connectTimes += sched.currentTime
            throwOnConnect?.let { throwOnConnect = null; throw it }
            if (promptOnConnect) onPrompt()
            return queue.removeFirstOrNull() ?: ConnectResult(PairResult.Unreachable(Exception("down")), null)
        }
        override fun disconnect() { disconnects++ }
    }

    class FakeTv(var on: Boolean = true) : Tv {
        var isOnCalls = 0; var wakes = 0; var offs = 0
        val pressed = mutableListOf<Button>(); val media = mutableListOf<String>()
        var turnsOnAfterWake = false
        var pressWorks = true
        override suspend fun isOn(): Boolean { isOnCalls++; return on }
        override suspend fun powerOff() { offs++; on = false }
        override suspend fun wake() { wakes++; if (turnsOnAfterWake) on = true }
        override fun press(b: Button): Boolean { pressed += b; return pressWorks }
        override suspend fun play() { media += "play" }
        override suspend fun pause() { media += "pause" }
        override suspend fun inputs() = listOf(Input("HDMI_1", "PC"))
        override suspend fun setInput(id: String) { media += "input:$id" }
        override suspend fun typeText(text: String) { media += "type:$text" }
        override suspend fun sendEnter() { media += "enter" }
        override suspend fun deleteChars(count: Int) { media += "del:$count" }
        var launchFails = false
        override suspend fun launch(appId: String) { if (launchFails) throw SsapError("no such app"); media += "launch:$appId" }
    }

    private val paired = ConnectResult(PairResult.Paired("abc"), "pin1")

    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher(sched))
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(conn: Connection, tv: Tv, store: SettingsStore = FakeStore()) = RemoteViewModel(conn, tv, store)
    private fun run(ms: Long = 0) { sched.advanceTimeBy(ms); sched.runCurrent() }

    @Test fun connectsAndShowsOn() {
        val v = vm(FakeConnection(paired), FakeTv(on = true)); v.onForeground(); run()
        assertEquals(Status.On, v.status.value)
    }

    @Test fun connectedButStandbyShowsOff() {
        val v = vm(FakeConnection(paired), FakeTv(on = false)); v.onForeground(); run()
        assertEquals(Status.Off, v.status.value)
    }

    @Test fun pairedSavesKeyAndPin() {
        val store = FakeStore()
        val v = vm(FakeConnection(paired), FakeTv(), store); v.onForeground(); run()
        assertEquals("abc", store.s.clientKey); assertEquals("pin1", store.s.certPin)
    }

    @Test fun showsPairingWhilePrompted() {
        val c = object : Connection {
            override var onDropped: (() -> Unit)? = null
            override suspend fun connect(settings: Settings, onPrompt: () -> Unit): ConnectResult {
                onPrompt(); kotlinx.coroutines.delay(30_000); return paired
            }
            override fun disconnect() {}
            override suspend fun ensureButtons() {}
        }
        val v = vm(c, FakeTv()); v.onForeground(); run(1_000)
        assertEquals(Status.Pairing, v.status.value)
    }

    @Test fun powerWhenStandbyWakes() {
        val tv = FakeTv(on = false)
        val v = vm(FakeConnection(paired), tv); v.onForeground(); run()
        v.power(); run()
        assertEquals(1, tv.wakes); assertEquals(0, tv.offs)
    }

    @Test fun powerWhenOnTurnsOff() {
        val tv = FakeTv(on = true)
        val v = vm(FakeConnection(paired), tv); v.onForeground(); run()
        v.power(); run()
        assertEquals(1, tv.offs); assertEquals(0, tv.wakes); assertEquals(Status.Off, v.status.value)
    }

    @Test fun wakeTurnsOnWithin15s() {
        val tv = FakeTv(on = false).apply { turnsOnAfterWake = true }
        val v = vm(FakeConnection(paired, paired), tv); v.onForeground(); run()
        v.power(); run(2_000)
        assertEquals(Status.On, v.status.value)
    }

    @Test fun wakeGivesUpAfter15s() {
        val tv = FakeTv(on = false)
        val v = vm(FakeConnection(paired, paired), tv); v.onForeground(); run()
        v.power(); run(16_000)
        assertEquals(Status.Off, v.status.value); assertEquals(1, tv.wakes)
    }

    @Test fun powerWorksWhenUnreachable() {
        val tv = FakeTv(on = false)
        val v = vm(FakeConnection(), tv); v.onForeground(); run()
        assertEquals(Status.Unreachable, v.status.value)
        v.power(); run()
        assertEquals(1, tv.wakes)
    }

    @Test fun declinedShowsDeclinedAndStops() {
        val c = FakeConnection(ConnectResult(PairResult.Declined, null))
        val v = vm(c, FakeTv()); v.onForeground(); run(60_000)
        assertEquals(Status.Declined, v.status.value); assertEquals(1, c.connectTimes.size)
    }

    @Test fun certChangedShowsMessageAndStops() {
        val c = FakeConnection(ConnectResult(PairResult.CertChanged("pin2"), "pin2"))
        val v = vm(c, FakeTv()); v.onForeground(); run(60_000)
        assertEquals(Status.CertChanged, v.status.value); assertEquals(1, c.connectTimes.size)
    }

    @Test fun backoffDelays() {
        val c = FakeConnection()
        val v = vm(c, FakeTv()); v.onForeground(); run(7_500)
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L), c.connectTimes)
    }

    @Test fun backoffCapsAt10s() {
        val c = FakeConnection()
        val v = vm(c, FakeTv()); v.onForeground(); run(45_500)
        // 0,1,3,7,15,25,35,45
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 25_000L, 35_000L, 45_000L), c.connectTimes)
    }

    @Test fun foregroundTwiceOneLoop() {
        val c = FakeConnection(paired)
        val v = vm(c, FakeTv()); v.onForeground(); v.onForeground(); run()
        assertEquals(1, c.connectTimes.size)
    }

    @Test fun backgroundStopsLoopAndDisconnects() {
        val c = FakeConnection()
        val v = vm(c, FakeTv()); v.onForeground(); run(); v.onBackground(); run(60_000)
        assertEquals(1, c.connectTimes.size); assertTrue(c.disconnects >= 1)
    }

    @Test fun backgroundThenForegroundReconnectsOnce() {
        val c = FakeConnection(paired, paired)
        val v = vm(c, FakeTv()); v.onForeground(); run(); v.onBackground(); v.onForeground(); run()
        assertEquals(2, c.connectTimes.size); assertEquals(Status.On, v.status.value)
    }

    @Test fun droppedConnectionReconnects() {
        val c = FakeConnection(paired, paired)
        val v = vm(c, FakeTv()); v.onForeground(); run()
        c.onDropped!!.invoke(); run(1_000)
        assertEquals(2, c.connectTimes.size)
    }

    @Test fun standbyNoticedWhileConnected() {
        val tv = FakeTv(on = true)
        val v = vm(FakeConnection(paired), tv); v.onForeground(); run()
        tv.on = false; run(5_000)
        assertEquals(Status.Off, v.status.value)
    }

    @Test fun pressIgnoredWhenNotOn() {
        val tv = FakeTv(on = false)
        val v = vm(FakeConnection(paired), tv); v.onForeground(); run()
        repeat(50) { v.press(Button.VOLUMEUP) }
        assertTrue(tv.pressed.isEmpty())
    }

    @Test fun pressSentWhenOn() {
        val tv = FakeTv(on = true)
        val v = vm(FakeConnection(paired), tv); v.onForeground(); run()
        v.press(Button.UP)
        assertEquals(listOf(Button.UP), tv.pressed)
    }

    @Test fun playPauseAlternatesStartingWithPause() {
        val tv = FakeTv(on = true)
        val v = vm(FakeConnection(paired), tv); v.onForeground(); run()
        v.playPause(); v.playPause(); v.playPause(); run()
        assertEquals(listOf("pause", "play", "pause"), tv.media)
    }

    @Test fun tvErrorsDontCrash() {
        val tv = object : Tv by FakeTv(on = true) {
            override suspend fun powerOff() = throw SsapError("boom")
            override suspend fun inputs(): List<Input> = throw SsapError("boom")
        }
        val v = vm(FakeConnection(paired), tv); v.onForeground(); run()
        v.power(); v.loadInputs(); run()
        assertEquals(emptyList<Input>(), v.inputs.value)
    }

    @Test fun loadAndSetInput() {
        val tv = FakeTv(on = true)
        val v = vm(FakeConnection(paired), tv); v.onForeground(); run()
        v.loadInputs(); v.setInput("HDMI_1"); run()
        assertEquals(listOf(Input("HDMI_1", "PC")), v.inputs.value); assertEquals(listOf("input:HDMI_1"), tv.media)
    }

    @Test fun retryAfterDeclinedTriesAgain() {
        val c = FakeConnection(ConnectResult(PairResult.Declined, null), paired)
        val v = vm(c, FakeTv()); v.onForeground(); run(); v.retry(); run()
        assertEquals(Status.On, v.status.value)
    }

    @Test fun wakeFailureDoesntCrash() {
        val tv = object : Tv by FakeTv(on = false) {
            override suspend fun wake() = throw IllegalArgumentException("Bad MAC address: x")
        }
        val crashes = mutableListOf<Throwable>()
        val old = Thread.currentThread().uncaughtExceptionHandler
        Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> crashes += e }
        try {
            val v = vm(FakeConnection(paired), tv); v.onForeground(); run()
            v.power(); run(1_000)
            assertEquals(emptyList<Throwable>(), crashes)
            assertEquals(Status.Off, v.status.value)
        } finally { Thread.currentThread().uncaughtExceptionHandler = old }
    }

    private fun noCrashes(block: () -> Unit) {
        val crashes = mutableListOf<Throwable>()
        val old = Thread.currentThread().uncaughtExceptionHandler
        Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> crashes += e }
        try { block(); assertEquals(emptyList<Throwable>(), crashes) }
        finally { Thread.currentThread().uncaughtExceptionHandler = old }
    }

    @Test fun powerAfterDeclinedDoesntLoopPrompts() {
        val c = FakeConnection(ConnectResult(PairResult.Declined, null))
        val v = vm(c, FakeTv(on = false)); v.onForeground(); run()
        v.power(); run(16_000)
        assertEquals(1, c.connectTimes.size); assertEquals(Status.Declined, v.status.value)
    }

    @Test fun powerAfterCertChangedKeepsMessage() {
        val c = FakeConnection(ConnectResult(PairResult.CertChanged("p2"), "p2"))
        val v = vm(c, FakeTv(on = false)); v.onForeground(); run()
        v.power(); run(16_000)
        assertEquals(1, c.connectTimes.size); assertEquals(Status.CertChanged, v.status.value)
    }

    @Test fun wakeFromStandbyReopensButtons() {
        val tv = FakeTv(on = false).apply { turnsOnAfterWake = true }
        val c = FakeConnection(paired)
        val v = vm(c, tv); v.onForeground(); run()
        v.power(); run(2_000)
        assertEquals(Status.On, v.status.value); assertTrue(c.ensureButtonsCalls >= 1)
    }

    @Test fun deadButtonSocketGetsReopened() {
        val tv = FakeTv(on = true).apply { pressWorks = false }
        val c = FakeConnection(paired)
        val v = vm(c, tv); v.onForeground(); run()
        v.press(Button.UP); run()
        assertTrue(c.ensureButtonsCalls >= 1)
    }

    @Test fun powerWhenOnTurnsOffEvenIfStateCheckFails() {
        val tv = FakeTv(on = true)
        val c = FakeConnection(paired)
        val v = vm(c, tv); v.onForeground(); run()
        tv.on = false // e.g. getPowerState timed out; the TV is really still on
        v.power(); run()
        assertEquals(1, tv.offs); assertEquals(0, tv.wakes); assertEquals(1, c.connectTimes.size)
    }

    @Test fun unexpectedConnectErrorDoesntCrash() = noCrashes {
        val c = FakeConnection(paired).apply { throwOnConnect = IllegalArgumentException("weird json") }
        val v = vm(c, FakeTv()); v.onForeground(); run(1_500)
        assertEquals(2, c.connectTimes.size); assertEquals(Status.On, v.status.value)
    }

    @Test fun homeOpensFreeMyTv() {
        val tv = FakeTv(on = true)
        val v = vm(FakeConnection(paired), tv); v.onForeground(); run()
        v.home(); run()
        assertEquals(listOf("launch:io.freemytv"), tv.media); assertTrue(tv.pressed.isEmpty())
    }

    @Test fun homeFallsBackToLgHomeIfLaunchFails() {
        val tv = FakeTv(on = true).apply { launchFails = true }
        val v = vm(FakeConnection(paired), tv); v.onForeground(); run()
        v.home(); run()
        assertEquals(listOf(Button.HOME), tv.pressed)
    }

    @Test fun homeIgnoredWhenOff() {
        val tv = FakeTv(on = false)
        val v = vm(FakeConnection(paired), tv); v.onForeground(); run()
        v.home(); run()
        assertTrue(tv.media.isEmpty() && tv.pressed.isEmpty())
    }

    @Test fun sendTextTypesIt() {
        val tv = FakeTv(on = true)
        val v = vm(FakeConnection(paired), tv); v.onForeground(); run()
        v.sendText("hello"); v.enter(); v.backspace(); run()
        assertEquals(listOf("type:hello", "enter", "del:1"), tv.media)
    }

    @Test fun emptyTextNotSent() {
        val tv = FakeTv(on = true)
        val v = vm(FakeConnection(paired), tv); v.onForeground(); run()
        v.sendText(""); run()
        assertTrue(tv.media.isEmpty())
    }

    @Test fun textIgnoredWhenOff() {
        val tv = FakeTv(on = false)
        val v = vm(FakeConnection(paired), tv); v.onForeground(); run()
        v.sendText("x"); v.enter(); run()
        assertTrue(tv.media.isEmpty())
    }

    @Test fun numberKeysGoToPointer() {
        val tv = FakeTv(on = true)
        val v = vm(FakeConnection(paired), tv); v.onForeground(); run()
        v.press(Button.NUM_0); v.press(Button.NUM_9)
        assertEquals(listOf(Button.NUM_0, Button.NUM_9), tv.pressed)
    }
}
