package com.robo.phonecompanion.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robo.phonecompanion.data.model.VehicleProfile
import com.robo.phonecompanion.ui.theme.ColorActive
import com.robo.phonecompanion.ui.theme.ColorUnknown
import com.robo.phonecompanion.vm.CanBusViewModel
import com.robo.phonecompanion.vm.DisplayFrame
import com.robo.phonecompanion.vm.SettingsViewModel

@Composable
fun LiveScreen(
    vm: CanBusViewModel,
    settingsVm: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val frames by vm.liveFrames.collectAsState()
    val showKnown by vm.showKnownInLive.collectAsState()
    val showUnknown by vm.showUnknownInLive.collectAsState()
    val isRecording by vm.isRecording.collectAsState()
    val vehicles by settingsVm.vehicles.collectAsState()
    val listState = rememberLazyListState()

    var showVehiclePicker by remember { mutableStateOf(false) }

    val visible = frames.filter { f ->
        if (f.message != null) showKnown else showUnknown
    }

    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty()) listState.animateScrollToItem(visible.lastIndex)
    }

    if (showVehiclePicker) {
        VehiclePickerDialog(
            vehicles = vehicles,
            onPick = { vehicleId, notes ->
                vm.startRecording(vehicleId, notes)
                showVehiclePicker = false
            },
            onDismiss = { showVehiclePicker = false },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = showKnown,
                onClick = { vm.setShowKnown(!showKnown) },
                label = { Text("Known") },
            )
            FilterChip(
                selected = showUnknown,
                onClick = { vm.setShowUnknown(!showUnknown) },
                label = { Text("Unknown") },
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (isRecording) {
                    OutlinedButton(
                        onClick = { vm.stopRecording() },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("⏹ Stop", fontSize = 12.sp)
                    }
                } else {
                    OutlinedButton(onClick = {
                        if (vehicles.isEmpty()) vm.startRecording("")
                        else showVehiclePicker = true
                    }) {
                        Text("⏺ Record", fontSize = 12.sp)
                    }
                }
            }
        }

        HorizontalDivider()

        if (visible.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                Text("No frames yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.seq }) { df ->
                    FrameRow(df)
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun VehiclePickerDialog(
    vehicles: List<VehicleProfile>,
    onPick: (vehicleId: String, notes: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var sessionNotes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select vehicle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                vehicles.forEach { v ->
                    TextButton(
                        onClick = { onPick(v.id, sessionNotes) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${v.make} ${v.model} ${v.year}".trim())
                    }
                }
                HorizontalDivider()
                TextButton(
                    onClick = { onPick("", sessionNotes) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("No vehicle", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                OutlinedTextField(
                    value = sessionNotes,
                    onValueChange = { sessionNotes = it },
                    label = { Text("Session notes (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FrameRow(df: DisplayFrame) {
    val isKnown = df.message != null
    val color = if (isKnown) ColorActive else ColorUnknown

    val idStr = if (df.frame.isExtended) "0x%08X".format(df.frame.id)
                else "0x%03X".format(df.frame.id)

    val content = when {
        df.message != null && df.decodedSignals.isNotEmpty() ->
            df.decodedSignals.entries.joinToString("  ") { (k, v) ->
                val sig = df.message.signals.find { it.name == k }
                val unit = sig?.unit ?: ""
                "$k=${"%.2f".format(v)}$unit"
            }
        else ->
            df.frame.data.joinToString(" ") { "%02X".format(it) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(idStr, color = color, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(0.25f))
        Text(content, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(0.75f))
    }
}
