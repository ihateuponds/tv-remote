package com.ponds.tvremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ponds.tvremote.tv.Button

fun Status.label(): String = when (this) {
    Status.Connecting -> "Connecting…"
    Status.On -> "Connected"
    Status.Off -> "TV off"
    Status.Pairing -> "Press OK on the TV to allow this phone"
    Status.Declined -> "Pairing was declined. Try again."
    Status.CertChanged -> "The TV's certificate changed. Forget pairing in Settings."
    Status.Unreachable -> "Can't reach the TV. Are you on home Wi-Fi?"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(vm: RemoteViewModel, onSettings: () -> Unit) {
    val status by vm.status.collectAsState()
    val inputs by vm.inputs.collectAsState()
    val on = status == Status.On
    val haptic = LocalHapticFeedback.current
    var showInputs by remember { mutableStateOf(false) }
    var showNumpad by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }

    fun tap(action: () -> Unit): () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        action()
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = tap { if (status == Status.Declined || status == Status.Unreachable) vm.retry() },
                label = { Text(status.label(), maxLines = 2) },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = tap(onSettings)) { Icon(Icons.Default.Settings, "Settings") }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            FilledIconButton(
                onClick = tap { vm.power() },
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Icon(Icons.Default.PowerSettingsNew, "Power", Modifier.size(36.dp)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundButton(Icons.Default.Dialpad, "Numbers", on, 72.dp, tap { showNumpad = true })
                RoundButton(Icons.Default.Keyboard, "Keyboard", on, 72.dp, tap { showKeyboard = true })
                RoundButton(Icons.AutoMirrored.Filled.Input, "Input", on, 72.dp, tap { vm.loadInputs(); showInputs = true })
            }
        }

        DPad(on, press = { b -> tap { vm.press(b) }() })

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RoundButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", on, 64.dp, tap { vm.press(Button.BACK) })
            RoundButton(Icons.Default.Home, "Home", on, 64.dp, tap { vm.home() })
            RoundButton(Icons.Default.PlayArrow, "Play / pause", on, 64.dp, tap { vm.playPause() })
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(Icons.Default.Remove, "Volume down", on, 64.dp, tap { vm.press(Button.VOLUMEDOWN) })
            RoundButton(Icons.AutoMirrored.Filled.VolumeOff, "Mute", on, 56.dp, tap { vm.press(Button.MUTE) })
            RoundButton(Icons.Default.Add, "Volume up", on, 64.dp, tap { vm.press(Button.VOLUMEUP) })
        }
        Spacer(Modifier.height(4.dp))
    }

    if (showNumpad) {
        ModalBottomSheet(onDismissRequest = { showNumpad = false }) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val rows = listOf(
                    listOf(Button.NUM_1, Button.NUM_2, Button.NUM_3),
                    listOf(Button.NUM_4, Button.NUM_5, Button.NUM_6),
                    listOf(Button.NUM_7, Button.NUM_8, Button.NUM_9),
                    listOf(null, Button.NUM_0, null),
                )
                rows.forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        row.forEach { b ->
                            if (b == null) Spacer(Modifier.size(72.dp))
                            else FilledTonalButton(
                                onClick = tap { vm.press(b) },
                                enabled = on,
                                modifier = Modifier.size(72.dp),
                                shape = CircleShape,
                            ) { Text(b.wire, style = MaterialTheme.typography.headlineSmall) }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showKeyboard) {
        ModalBottomSheet(onDismissRequest = { showKeyboard = false }) {
            var text by remember { mutableStateOf("") }
            Column(Modifier.fillMaxWidth().padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Type or paste, then Send", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Select a text box on the TV first") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.sendText(text); text = "" }),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = tap { vm.sendText(text); text = "" },
                        enabled = on && text.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Send") }
                    FilledTonalButton(onClick = tap { vm.enter() }, enabled = on, modifier = Modifier.weight(1f)) { Text("Enter") }
                    FilledTonalButton(onClick = tap { vm.backspace() }, enabled = on, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Filled.Backspace, "Delete one letter on the TV")
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showInputs) {
        ModalBottomSheet(onDismissRequest = { showInputs = false }) {
            Column(Modifier.padding(16.dp)) {
                Text("Inputs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (inputs.isEmpty()) Text("Loading…", Modifier.padding(vertical = 12.dp))
                inputs.forEach { input ->
                    TextButton(
                        onClick = tap { vm.setInput(input.id); showInputs = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(input.label, Modifier.fillMaxWidth()) }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DPad(enabled: Boolean, press: (Button) -> Unit) {
    Box(Modifier.size(260.dp)) {
        RoundButton(Icons.Default.KeyboardArrowUp, "Up", enabled, 76.dp, { press(Button.UP) }, Modifier.align(Alignment.TopCenter))
        RoundButton(Icons.Default.KeyboardArrowDown, "Down", enabled, 76.dp, { press(Button.DOWN) }, Modifier.align(Alignment.BottomCenter))
        RoundButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left", enabled, 76.dp, { press(Button.LEFT) }, Modifier.align(Alignment.CenterStart))
        RoundButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right", enabled, 76.dp, { press(Button.RIGHT) }, Modifier.align(Alignment.CenterEnd))
        FilledIconButton(
            onClick = { press(Button.ENTER) },
            enabled = enabled,
            modifier = Modifier.size(96.dp).align(Alignment.Center),
            shape = CircleShape,
        ) { Text("OK", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(size),
        shape = RoundedCornerShape(percent = 50),
    ) { Icon(icon, label, Modifier.size(size * 0.5f)) }
}
