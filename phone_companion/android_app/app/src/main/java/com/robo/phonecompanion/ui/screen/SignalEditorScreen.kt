package com.robo.phonecompanion.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.robo.phonecompanion.data.model.CanonicalSignal
import com.robo.phonecompanion.data.model.CanonicalSignals
import com.robo.phonecompanion.data.model.Dbc
import com.robo.phonecompanion.data.model.DbcMessage
import com.robo.phonecompanion.data.model.DbcSignal
import com.robo.phonecompanion.ui.component.BitGrid
import com.robo.phonecompanion.vm.CanBusViewModel
import com.robo.phonecompanion.vm.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    var comment by rememberSaveable { mutableStateOf(existingSignal?.comment ?: "") }

    var byteOrderExpanded by remember { mutableStateOf(false) }

    // Live frame data for preview
    val canId = rawId?.and(0x1FFFFFFF)
    val liveData = canId?.let { canBusVm.lastFrameData(it) }

    // DLC: for existing messages use the stored value; for new messages allow user input
    val isNewMessage = rawId == null || existingMessage == null
    var dlcInput by rememberSaveable {
        mutableStateOf(
            when {
                existingMessage != null -> existingMessage.dlc.toString()
                canId != null -> (canBusVm.lastFrameData(canId)?.size ?: 8).toString()
                else -> "8"
            }
        )
    }
    val dlc = when {
        existingMessage != null -> existingMessage.dlc
        else -> dlcInput.toIntOrNull()?.coerceIn(1, 8) ?: 8
    }
    val maxStartBit = dlc * 8 - 1

    val canSave = name.isNotBlank() && (existingMessage != null || msgName.isNotBlank()) &&
        factor.toDoubleOrNull() != null && offset.toDoubleOrNull() != null &&
        startBit in 0..maxStartBit && length in 1..(dlc * 8)

    // Live preview value — recompute whenever signal definition or live data changes
    val previewValue = remember(startBit, length, byteOrder, signed, factor, offset, liveData) {
        liveData?.let {
            runCatching {
                val sig = DbcSignal("_", startBit, length, byteOrder, signed,
                    factor.toDouble(), offset.toDouble(), 0.0, 0.0, unit)
                SignalDecoder.decodeOrNull(sig, it)
            }.getOrNull()?.let { it }
        }
    }

    // VAL_ descriptions state
    var valueDescriptions by remember {
        mutableStateOf(existingSignal?.valueDescriptions ?: emptyMap<Long, String>())
    }
    var showValDesc by remember { mutableStateOf(valueDescriptions.isNotEmpty()) }
    var showAddValDialog by remember { mutableStateOf(false) }

    // ── Canonical autocomplete state ──────────────────────────────────────────
    var showSuggestions by remember { mutableStateOf(false) }
    val suggestions: List<CanonicalSignal> = remember(name) {
        if (name.length >= 2)
            CanonicalSignals.ALL.filter { it.name.contains(name, ignoreCase = true) }.take(6)
        else emptyList()
    }

    // ── Copy-from-DBC dialog state ────────────────────────────────────────────
    var showCopyDialog by remember { mutableStateOf(false) }

    // ── Dialogs ────────────────────────────────────────────────────────────────
    if (showAddValDialog) {
        ValueDescriptionDialog(
            onConfirm = { raw, label ->
                valueDescriptions = valueDescriptions + (raw to label)
                showAddValDialog = false
            },
            onDismiss = { showAddValDialog = false },
        )
    }

    if (showCopyDialog) {
        CopySignalDialog(
            settingsVm = settingsVm,
            onCopy = { sig ->
                name = sig.name
                unit = sig.unit
                min = sig.min.toString()
                max = sig.max.toString()
                factor = sig.factor.toString()
                offset = sig.offset.toString()
                signed = sig.signed
                startBit = sig.startBit
                length = sig.length
                byteOrder = sig.byteOrder
                sig.comment?.let { comment = it }
                valueDescriptions = sig.valueDescriptions
                showCopyDialog = false
            },
            onDismiss = { showCopyDialog = false },
        )
    }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = dlcInput,
                    onValueChange = { dlcInput = it.filter { c -> c.isDigit() }.take(1) },
                    label = { Text("DLC (bytes)") },
                    modifier = Modifier.fillMaxWidth(0.4f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = dlcInput.toIntOrNull()?.let { it in 1..8 } == false,
                )
            }
        } else {
            val idStr = rawId?.let { "0x%03X".format(it and 0x1FFFFFFF) } ?: ""
            Text("${existingMessage!!.name}  $idStr",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ── Signal name + autocomplete + copy-from ─────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Signal definition", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { showCopyDialog = true }) {
                Text("Copy from…", style = MaterialTheme.typography.labelSmall)
            }
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    showSuggestions = it.length >= 2
                },
                label = { Text("Signal name *") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            DropdownMenu(
                expanded = showSuggestions && suggestions.isNotEmpty(),
                onDismissRequest = { showSuggestions = false },
                modifier = Modifier.fillMaxWidth(),
            ) {
                suggestions.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(s.name, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                                Text(
                                    buildString {
                                        if (s.unit.isNotEmpty()) { append(s.unit); append("  ") }
                                        append(s.description)
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            name = s.name
                            unit = s.unit
                            min = s.min.toString()
                            max = s.max.toString()
                            factor = s.factor.toString()
                            offset = s.offset.toString()
                            showSuggestions = false
                        },
                    )
                }
            }
        }

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

        HorizontalDivider()

        // ── Comment ────────────────────────────────────────────────────────
        OutlinedTextField(
            value = comment,
            onValueChange = { comment = it },
            label = { Text("Notes / comment") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
        )

        HorizontalDivider()

        // ── Value descriptions (VAL_) ──────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().clickable { showValDesc = !showValDesc },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Value descriptions (${valueDescriptions.size})",
                style = MaterialTheme.typography.labelMedium)
            Icon(
                if (showValDesc) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
            )
        }

        if (showValDesc) {
            valueDescriptions.entries.sortedBy { it.key }.forEach { (raw, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$raw → $label", modifier = Modifier.weight(1f), fontSize = 12.sp)
                    IconButton(onClick = { valueDescriptions = valueDescriptions - raw }) {
                        Icon(Icons.Default.Delete, contentDescription = "Remove",
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            TextButton(onClick = { showAddValDialog = true }) { Text("+ Add entry") }
        }

        HorizontalDivider()

        // ── Live preview ───────────────────────────────────────────────────
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

        if (liveData != null && (startBit + length) > liveData.size * 8) {
            Text(
                "⚠ Signal extends past frame DLC (${liveData.size} bytes)",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── Save ───────────────────────────────────────────────────────────
        Button(
            onClick = {
                saveSignal(
                    canBusVm, settingsVm,
                    rawId, isNewMessage, msgName, dlc,
                    name, startBit, length, byteOrder, signed,
                    factor.toDouble(), offset.toDouble(),
                    min.toDoubleOrNull() ?: 0.0, max.toDoubleOrNull() ?: 0.0, unit,
                    comment.trim().ifEmpty { null },
                    valueDescriptions,
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

// ── Copy-from-DBC dialog ──────────────────────────────────────────────────────

@Composable
private fun CopySignalDialog(
    settingsVm: SettingsViewModel,
    onCopy: (DbcSignal) -> Unit,
    onDismiss: () -> Unit,
) {
    var dbcIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var allDbcs by remember { mutableStateOf<Map<String, Dbc>>(emptyMap()) }
    var selectedId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val ids = settingsVm.dbcRepository.listIds()
            val dbcs = ids.mapNotNull { id ->
                settingsVm.dbcRepository.load(id)?.let { id to it }
            }.toMap()
            dbcIds = ids
            allDbcs = dbcs
        }
    }

    val selectedDbc = selectedId?.let { allDbcs[it] }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            if (selectedId != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedId = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                    Text(selectedId!!, style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Text("Copy signal from")
            }
        },
        text = {
            if (dbcIds.isEmpty() && allDbcs.isEmpty()) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (dbcIds.isEmpty()) {
                Text("No other DBC files available.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (selectedDbc == null) {
                // Level 1: pick DBC
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(dbcIds) { id ->
                        ListItem(
                            headlineContent = { Text(id) },
                            supportingContent = {
                                val count = allDbcs[id]?.messages?.size ?: 0
                                Text("$count message${if (count == 1) "" else "s"}")
                            },
                            modifier = Modifier.clickable { selectedId = id },
                        )
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            } else {
                // Level 2: pick signal
                val signalPairs = selectedDbc.messages.values
                    .sortedBy { it.name }
                    .flatMap { msg -> msg.signals.map { sig -> msg.name to sig } }

                if (signalPairs.isEmpty()) {
                    Text("No signals in this DBC.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(signalPairs, key = { (msg, sig) -> "$msg/${sig.name}" }) { (msgName, sig) ->
                            ListItem(
                                headlineContent = {
                                    Text(sig.name, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                                },
                                supportingContent = {
                                    Text(
                                        buildString {
                                            append(msgName)
                                            if (sig.unit.isNotEmpty()) append("  ${sig.unit}")
                                            if (sig.min != 0.0 || sig.max != 0.0)
                                                append("  [${sig.min}…${sig.max}]")
                                        },
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                modifier = Modifier.clickable { onCopy(sig) },
                            )
                            HorizontalDivider(thickness = 0.5.dp)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── VAL_ description dialog ───────────────────────────────────────────────────

@Composable
private fun ValueDescriptionDialog(
    onConfirm: (Long, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var rawInput by remember { mutableStateOf("") }
    var labelInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add value description") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = rawInput,
                    onValueChange = { rawInput = it },
                    label = { Text("Raw value") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = rawInput.toLongOrNull() == null && rawInput.isNotEmpty(),
                )
                OutlinedTextField(
                    value = labelInput,
                    onValueChange = { labelInput = it },
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { rawInput.toLongOrNull()?.let { onConfirm(it, labelInput.trim()) } },
                enabled = rawInput.toLongOrNull() != null && labelInput.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ── Save helper ───────────────────────────────────────────────────────────────

private fun saveSignal(
    canBusVm: CanBusViewModel,
    settingsVm: SettingsViewModel,
    rawId: Int?,
    isNewMessage: Boolean,
    msgName: String,
    dlc: Int,
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
    comment: String?,
    valueDescriptions: Map<Long, String>,
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
        comment = comment,
        valueDescriptions = valueDescriptions,
    )

    val messages = dbc.messages.toMutableMap()

    if (isNewMessage) {
        val newRawId = rawId ?: return
        val msg = DbcMessage(rawId = newRawId, name = msgName.trim(),
            dlc = dlc, signals = listOf(newSignal))
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
