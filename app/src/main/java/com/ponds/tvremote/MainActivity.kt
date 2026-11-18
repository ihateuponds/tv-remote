package com.ponds.tvremote

import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ponds.tvremote.data.Prefs
import com.ponds.tvremote.tv.Button
import com.ponds.tvremote.tv.TvConnection
import com.ponds.tvremote.tv.TvController
import com.ponds.tvremote.ui.RemoteScreen
import com.ponds.tvremote.ui.RemoteViewModel
import com.ponds.tvremote.ui.SettingsScreen
import com.ponds.tvremote.ui.Status
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore("tv")

/** Shared pieces that must outlive rotation and be the same for both screens. */
class Graph(app: Application) : AndroidViewModel(app) {
    val http: OkHttpClient = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build()
    val conn = TvConnection(http)
}

class MainActivity : ComponentActivity() {
    private val prefs by lazy { Prefs(applicationContext.dataStore) }
    private val graph: Graph by viewModels()

    private val vm: RemoteViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val conn = graph.conn
                val tv = TvController(conn, conn::press, { conn.latestSettings })
                return RemoteViewModel(conn, tv, prefs) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycle.addObserver(LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> vm.onForeground()
                Lifecycle.Event.ON_STOP -> vm.onBackground()
                else -> Unit
            }
        })
        setContent {
            val ctx = LocalContext.current
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = colors) {
                Surface {
                    var settings by remember { mutableStateOf(false) }
                    when {
                        settings -> {
                            BackHandler { settings = false }
                            SettingsScreen(prefs, onSaved = { vm.retry() }, onBack = { settings = false })
                        }
                        else -> RemoteScreen(vm, onSettings = { settings = true })
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val b = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> Button.VOLUMEUP
            KeyEvent.KEYCODE_VOLUME_DOWN -> Button.VOLUMEDOWN
            else -> return super.onKeyDown(keyCode, event)
        }
        if (vm.status.value != Status.On) return super.onKeyDown(keyCode, event)
        vm.press(b)
        return true
    }
}
