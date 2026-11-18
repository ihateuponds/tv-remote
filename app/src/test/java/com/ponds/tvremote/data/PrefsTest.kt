package com.ponds.tvremote.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrefsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val prefs by lazy {
        Prefs(PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("tv.preferences_pb").also { it.delete() } })
    }
    @After fun tearDown() = scope.cancel()

    @Test fun defaults() = runBlocking {
        assertEquals(Settings("192.168.1.50", "00:11:22:33:44:55", null, null), prefs.settings.first())
    }

    @Test fun updateAndForget() = runBlocking {
        prefs.update { it.copy(ip = "10.0.0.5", clientKey = "k", certPin = "p") }
        assertEquals(Settings("10.0.0.5", "00:11:22:33:44:55", "k", "p"), prefs.settings.first())
        prefs.forgetPairing()
        assertEquals(Settings("10.0.0.5", "00:11:22:33:44:55", null, null), prefs.settings.first())
    }
}
