package com.ponds.tvremote.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Settings(
    val ip: String = "192.168.1.50",
    val mac: String = "00:11:22:33:44:55",
    val clientKey: String? = null,
    val certPin: String? = null,
)

interface SettingsStore {
    suspend fun get(): Settings
    suspend fun update(f: (Settings) -> Settings)
}

/** App-private settings store (DataStore "tv"). */
class Prefs(private val store: DataStore<Preferences>) : SettingsStore {
    private object K {
        val ip = stringPreferencesKey("ip")
        val mac = stringPreferencesKey("mac")
        val clientKey = stringPreferencesKey("clientKey")
        val certPin = stringPreferencesKey("certPin")
    }

    val settings: Flow<Settings> = store.data.map { p ->
        val d = Settings()
        Settings(p[K.ip] ?: d.ip, p[K.mac] ?: d.mac, p[K.clientKey], p[K.certPin])
    }

    override suspend fun get(): Settings = settings.first()

    override suspend fun update(f: (Settings) -> Settings) {
        store.edit { p ->
            val d = Settings()
            val new = f(Settings(p[K.ip] ?: d.ip, p[K.mac] ?: d.mac, p[K.clientKey], p[K.certPin]))
            p[K.ip] = new.ip
            p[K.mac] = new.mac
            if (new.clientKey != null) p[K.clientKey] = new.clientKey else p.remove(K.clientKey)
            if (new.certPin != null) p[K.certPin] = new.certPin else p.remove(K.certPin)
        }
    }

    suspend fun forgetPairing() = update { it.copy(clientKey = null, certPin = null) }
}
