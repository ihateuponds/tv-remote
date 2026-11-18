package com.ponds.tvremote.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ponds.tvremote.data.SettingsStore
import com.ponds.tvremote.tv.Button
import com.ponds.tvremote.tv.ConnectResult
import com.ponds.tvremote.tv.Connection
import com.ponds.tvremote.tv.Input
import com.ponds.tvremote.tv.PairResult
import com.ponds.tvremote.tv.Tv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface Status {
    data object Connecting : Status
    data object On : Status
    data object Off : Status
    data object Pairing : Status
    data object Declined : Status
    data object CertChanged : Status
    data object Unreachable : Status
}

class RemoteViewModel(
    private val conn: Connection,
    private val tv: Tv,
    private val store: SettingsStore,
) : ViewModel() {
    private val _status = MutableStateFlow<Status>(Status.Connecting)
    val status: StateFlow<Status> = _status
    private val _inputs = MutableStateFlow<List<Input>>(emptyList())
    val inputs: StateFlow<List<Input>> = _inputs

    private var loop: Job? = null
    private var powerJob: Job? = null
    @Volatile private var dropped = CompletableDeferred<Unit>()
    private var playing = true

    init { conn.onDropped = { dropped.complete(Unit) } }

    fun onForeground() {
        if (loop?.isActive == true) return
        loop = viewModelScope.launch { connectLoop() }
    }

    fun onBackground() {
        loop?.cancel(); loop = null
        powerJob?.cancel()
        conn.disconnect()
    }

    fun retry() {
        onBackground()
        _status.value = Status.Connecting
        onForeground()
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (true) {
            _status.value = Status.Connecting
            dropped = CompletableDeferred()
            val r = try {
                conn.connect(store.get()) { _status.value = Status.Pairing }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ConnectResult(PairResult.Unreachable(e), null)
            }
            when (val res = r.result) {
                is PairResult.Paired -> {
                    attempt = 0
                    try {
                        store.update { it.copy(clientKey = res.clientKey, certPin = r.seenPin ?: it.certPin) }
                    } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                    refreshPower()
                    // Stay here while connected; re-check power state so standby by the TV's own button shows up.
                    while (!dropped.isCompleted) {
                        withTimeoutOrNull(POLL_MS) { dropped.await() } ?: refreshPower()
                    }
                }
                PairResult.Declined -> { _status.value = Status.Declined; return }
                is PairResult.CertChanged -> { _status.value = Status.CertChanged; return }
                is PairResult.Unreachable -> {
                    _status.value = Status.Unreachable
                    delay(BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)])
                    attempt++
                }
            }
        }
    }

    private suspend fun refreshPower() {
        if (powerJob?.isActive == true) return // a power press is in charge of the status right now
        val wasOn = _status.value == Status.On
        val on = tv.isOn()
        _status.value = if (on) Status.On else Status.Off
        if (on && !wasOn) safely { conn.ensureButtons() }
    }

    fun power() {
        if (powerJob?.isActive == true) return
        powerJob = viewModelScope.launch {
            try {
                // Status On was confirmed by getPowerState (re-checked every 5 s), so trust it here:
                // a slow answer right now must not turn a power-off press into a wake + reconnect.
                val st = _status.value
                if (st == Status.On) {
                    tv.powerOff()
                    _status.value = Status.Off
                    return@launch
                }
                tv.wake()
                // These need the user's attention; waking is fine but don't reconnect behind their back.
                if (st == Status.Declined || st == Status.CertChanged || st == Status.Pairing) return@launch
                // If we weren't connected, start over now instead of waiting out the backoff.
                if (st != Status.Off) { loop?.cancel(); loop = null; conn.disconnect() }
                for (i in 1..WAKE_POLLS) {
                    delay(1_000)
                    if (loop?.isActive != true) loop = viewModelScope.launch { connectLoop() }
                    if (tv.isOn()) {
                        _status.value = Status.On
                        conn.ensureButtons()
                        return@launch
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // TV didn't answer or the wake packet couldn't be sent; the status chip shows what we know
            }
        }
    }

    private var buttonsJob: Job? = null

    fun press(b: Button) {
        if (_status.value != Status.On) return
        if (!tv.press(b) && buttonsJob?.isActive != true) {
            buttonsJob = viewModelScope.launch { safely { conn.ensureButtons() } }
        }
    }

    /** Home opens Free My TV; if it isn't installed, fall back to LG's own home screen. */
    fun home() {
        if (_status.value != Status.On) return
        viewModelScope.launch {
            try {
                tv.launch(HOME_APP)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                press(Button.HOME)
            }
        }
    }

    fun sendText(text: String) {
        if (_status.value != Status.On || text.isEmpty()) return
        viewModelScope.launch { safely { tv.typeText(text) } }
    }

    fun enter() {
        if (_status.value != Status.On) return
        viewModelScope.launch { safely { tv.sendEnter() } }
    }

    fun backspace() {
        if (_status.value != Status.On) return
        viewModelScope.launch { safely { tv.deleteChars(1) } }
    }

    fun playPause() {
        if (_status.value != Status.On) return
        val toPause = playing
        playing = !playing
        viewModelScope.launch { safely { if (toPause) tv.pause() else tv.play() } }
    }

    fun loadInputs() { viewModelScope.launch { safely { _inputs.value = tv.inputs() } } }

    fun setInput(id: String) { viewModelScope.launch { safely { tv.setInput(id) } } }

    override fun onCleared() { conn.disconnect() }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) {}
    }

    private companion object {
        val BACKOFF_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 10_000)
        const val POLL_MS = 5_000L
        const val WAKE_POLLS = 15
        const val HOME_APP = "io.freemytv"
    }
}
