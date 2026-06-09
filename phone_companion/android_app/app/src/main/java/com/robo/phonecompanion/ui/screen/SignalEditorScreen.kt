package com.robo.phonecompanion.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robo.phonecompanion.data.decoder.SignalDecoder
import com.robo.phonecompanion.data.model.ByteOrder
import com.robo.phonecompanion.data.model.DbcMessage
import com.robo.phonecompanion.data.model.DbcSignal
import com.robo.phonecompanion.ui.component.BitGrid
import com.robo.phonecompanion.vm.CanBusViewModel
import com.robo.phonecompanion.vm.SettingsViewModel

/**
 * Full-screen signal editor. Handles two cases:
 *  - Editing an existing signal: [rawId] and [signalName] are provided, non-null
 *  - Creating a new signal: [signalName] is null. If [rawId] is also null, a
 *    message name field is shown so the user can define a new message at the same time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalEditorScreen(
    canBusVm: CanBusViewModel,
    settingsVm: SettingsViewModel,
    rawId: Int?,
    signalName: String?,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeDbc by canBusVm.activeDbc.collectAsState()
    val activeDbcId by canBusVm.activeDbcId.collectAsState()

    val existingMessage = rawId?.let { activeDbc?.messages?.get(it) }
    val existingSignal = existingMessage?.signals?.find { it.name == signalName }

    // ── Form state ────────────────────────────────────────────────────────────

    var msgName by rememberSaveable { mutableStateOf(existingMessage?.name ?: "") }
    var name by rememberSaveable { mutableStateOf(existingSignal?.name ?: "") }
    var startBit by rememberSaveable { mutableIntStateOf(existingSignal?.startBit ?: 0) }
    var length by rememberSaveable { mutableIntStateOf(existingSignal?.length ?: 8) }
    var byteOrder by remember { mutableStateOf(existingSignal?.byteOrder ?: ByteOrder.INTEL) }
    var signed by rememberSaveable { mutableStateOf(existingSignal?.signed ?: false) }
    var factor by rememberSaveable { mutableStateOf(existingSignal?.factor?.toString() ?: "1.0") }
    var offset by rememberSaveable { mutableStateOf(existingSignal?.offset?.toString() ?: "0.0") }
    var unit by rememberSaveable { mutableStateOf(existingSignal?.unit ?: "") }
    var min by rememberSaveable { mutableStateOf(existingSignal?.min?.toString() ?: "0.0") }
    var max by rememberSaveable { mutableStateOf(existingSignal?.max?.toString() ?: "0.0") }

    var byteOrderExpanded by remember { mutableStateOf(false) }

    // Live frame data for preview
    val canId = rawId?.and(0x1FFFFFFF)
    val liveData = canId?.let { canBusVm.lastFrameData(it) }

    // Live preview value — recompute whenever signal definition or live data changes
    val previewValue = remember(startBit, length, byteOrder, signed, factor, offset, liveData) {
        liveData?.let {
            runCatching {
                val sig = DbcSignal("_", startBit, length, byteOrder, signed,
                    factor.toDouble(), offset.toDouble(), 0.0, 0.0, unit)
                SignalDecoder.decode(sig, it)
            }.getOrNull()
        }
    }

    val dlc = existingMessage?.dlc ?: 8
    val maxStartBit = dlc * 8 - 1
    val isNewMessage = rawId == null || existingMessage == null
    val canSave = name.isNotBlank() && (existingMessage != null || msgName.isNotBlank()) &&
        factor.toDoubleOrNull() != null && offset.toDoubleOrNull() != null &&
        startBit in 0..maxStartBit && length in 1..(dlc * 8)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── Message context ────────────────────────────────────────────────
        if (isNewMessage) {
            OutlinedTextField(
                value = msgName,
                onValueChange = { msgName = it },
                label = { Text("Message name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        } else {
            val idStr = rawId?.let { "0x%03X".format(it and 0x1FFFFFFF) } ?: ""
            Text("${existingMessage!!.name}  $idStr",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Signal name *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        HorizontalDivider()

        // ── Bit grid ───────────────────────────────────────────────────────
        Text("Bit layout", style = MaterialTheme.typography.labelMedium)
        Text(
            "Tap a cell to set the start bit (MSB for Motorola, LSB for Intel). " +
                "Adjust length with the +/− buttons.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        BitGrid(
            dlc = dlc,
            startBit = startBit,
            length = length,
            byteOrder = byteOrder,
            onBitTap = { startBit = it },
            liveFrameData = liveData,
        )

        // ── Controls row ───────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Byte order dropdown
            ExposedDropdownMenuBox(
                expanded = byteOrderExpanded,
                onExpandedChange = { byteOrderExpanded = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = byteOrder.name.lowercase().replaceFirstChar { it.uppercase() },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Byte order") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(byteOrderExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = byteOrderExpanded,
                    onDismissRequest = { byteOrderExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Intel") },
                        onClick = { byteOrder = ByteOrder.INTEL; byteOrderExpanded = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Motorola") },
                        onClick = { byteOrder = ByteOrder.MOTOROLA; byteOrderExpanded = false },
                    )
                }
            }

            // Signed toggle
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(0.8f)) {
                Text("Signed", style = MaterialTheme.typography.labelSmall)
                Switch(checked = signed, onCheckedChange = { signed = it })
            }
        }

        // Start bit + length row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = "Bit $startBit",
                onValueChange = {},
                readOnly = true,
                label = { Text("Start bit") },
                modifier = Modifier.weight(1f),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1.2f),
            ) {
                IconButton(onClick = { if (length > 1) length-- }) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease length")
                }
                Text(
                    "$length bit${if (length != 1) "s" else ""}",
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                IconButton(onClick = { if (startBit + length <= maxStartBit) length++ }) {
                    Icon(Icons.Default.Add, contentDescription = "Increase length")
                }
            }
        }

        HorizontalDivider()

        // ── Scaling ────────────────────────────────────────────────────────
        Text("Scaling", style = MaterialTheme.typography.labelMedium)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = factor,
                onValueChange = { factor = it },
                label = { Text("Factor") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = factor.toDoubleOrNull() == null,
            )
            OutlinedTextField(
                value = offset,
                onValueChange = { offset = it },
                label = { Text("Offset") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = offset.toDoubleOrNull() == null,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = unit,
                onValueChange = { unit = it },
                label = { Text("Unit") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = min,
                onValueChange = { min = it },
                label = { Text("Min") },
                modifier = Modifier.weight(0.7f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                value = max,
                onValueChange = { max = it },
                label = { Text("Max") },
                modifier = Modifier.weight(0.7f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }

        // ── Live preview ───────────────────────────────────────────────────
        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Live preview", style = MaterialTheme.typography.labelMedium)
            val preview = previewValue
            Text(
                text = if (preview != null) "${"%.4g".format(preview)} $unit".trim()
                       else if (liveData == null) "No live data" else "—",
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
                color = if (previewValue != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── Save ───────────────────────────────────────────────────────────
        Button(
            onClick = {
                saveSignal(
                    canBusVm, settingsVm,
                    rawId, isNewMessage, msgName,
                    name, startBit, length, byteOrder, signed,
                    factor.toDouble(), offset.toDouble(),
                    min.toDoubleOrNull() ?: 0.0, max.toDoubleOrNull() ?: 0.0, unit,
                    existingSignal,
                )
                onSaved()
            },
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (existingSignal != null) "Save changes" else "Add to DBC")
        }
    }
}

private fun saveSignal(
    canBusVm: CanBusViewModel,
    settingsVm: SettingsViewModel,
    rawId: Int?,
    isNewMessage: Boolean,
    msgName: String,
    name: String,
    startBit: Int,
    length: Int,
    byteOrder: ByteOrder,
    signed: Boolean,
    factor: Double,
    offset: Double,
    min: Double,
    max: Double,
    unit: String,
    existingSignal: DbcSignal?,
) {
    val dbc = canBusVm.activeDbc.value ?: return
    val dbcId = canBusVm.activeDbcId.value ?: return

    val newSignal = DbcSignal(
        name = name.trim(),
        startBit = startBit,
        length = length,
        byteOrder = byteOrder,
        signed = signed,
        factor = factor,
        offset = offset,
        min = min,
        max = max,
        unit = unit.trim(),
        comment = existingSignal?.comment,
        valueDescriptions = existingSignal?.valueDescriptions ?: emptyMap(),
    )

    val messages = dbc.messages.toMutableMap()

    if (isNewMessage) {
        // Allocate a raw DBC ID — use canId directly (standard frame)
        val newRawId = rawId ?: return
        val msg = DbcMessage(rawId = newRawId, name = msgName.trim(),
            dlc = 8, signals = listOf(newSignal))
        messages[newRawId] = msg
    } else {
        val msg = messages[rawId!!] ?: return
        val signals = msg.signals.filterNot { it.name == existingSignal?.name } + newSignal
        messages[rawId] = msg.copy(signals = signals)
    }

    val updatedDbc = dbc.copy(messages = messages)
    settingsVm.dbcRepository.save(dbcId, updatedDbc)
    val sidecar = settingsVm.dbcRepository.sidecarFor(dbcId).load()
    canBusVm.setActiveDbc(updatedDbc, sidecar, dbcId)
}
