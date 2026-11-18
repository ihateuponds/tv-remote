package com.ponds.tvremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.ponds.tvremote.data.Prefs
import com.ponds.tvremote.tv.Waker
import kotlinx.coroutines.launch

private val IP = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""")

fun validIp(s: String) = IP.matchEntire(s.trim())?.groupValues?.drop(1)?.all { it.toInt() in 0..255 } == true
fun validMac(s: String) = runCatching { Waker.parseMac(s) }.isSuccess

@Composable
fun SettingsScreen(prefs: Prefs, onSaved: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var ip by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    var paired by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val s = prefs.get()
        ip = s.ip; mac = s.mac; paired = s.clientKey != null
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        OutlinedTextField(
            value = ip, onValueChange = { ip = it }, label = { Text("TV IP address") },
            isError = !validIp(ip), singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = mac, onValueChange = { mac = it }, label = { Text("TV MAC address (for turning it on)") },
            isError = !validMac(mac), singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = validIp(ip) && validMac(mac),
            onClick = {
                scope.launch {
                    prefs.update { it.copy(ip = ip.trim(), mac = mac.trim()) }
                    onSaved()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save") }
        OutlinedButton(
            onClick = {
                scope.launch {
                    prefs.forgetPairing()
                    paired = false
                    note = "Forgotten. The TV will ask to allow this phone again."
                    onSaved()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Forget pairing") }
        Text(if (paired) "Paired with the TV." else "Not paired yet.", style = MaterialTheme.typography.bodyMedium)
        note?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(
            "Turning the TV on needs \"Turn on via Wi-Fi\" in the TV's settings " +
                "(General → Devices → External Devices → TV On With Mobile).",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
