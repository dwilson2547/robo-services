package com.robo.phonecompanion.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robo.phonecompanion.data.model.DbcMessage
import com.robo.phonecompanion.data.model.SignalSidecar
import com.robo.phonecompanion.data.model.VerificationStatus
import com.robo.phonecompanion.ui.theme.ColorSuspect
import com.robo.phonecompanion.ui.theme.ColorUnknown
import com.robo.phonecompanion.ui.theme.ColorVerified
import com.robo.phonecompanion.vm.CanBusViewModel
import com.robo.phonecompanion.vm.MessageState
import com.robo.phonecompanion.vm.SettingsViewModel

@Composable
fun SignalsScreen(
    vm: CanBusViewModel,
    settingsVm: SettingsViewModel,
    onEditSignal: (rawId: Int, signalName: String) -> Unit = { _, _ -> },
    onNewSignal: (rawId: Int) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val known by vm.knownMessages.collectAsState()
    val sidecar by vm.activeSidecar.collectAsState()
    val dbc by vm.activeDbc.collectAsState()
    val activeDbcId by vm.activeDbcId.collectAsState()

    if (dbc == null) {
        Box(modifier = modifier.fillMaxSize().padding(24.dp)) {
            Text("No DBC loaded. Load one from Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val messages = dbc!!.messages.values.sortedBy { it.name }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(messages, key = { it.rawId }) { msg ->
            val liveState = known[msg.canId]
            MessageRow(
                msg = msg,
                live = liveState,
                sidecarSignals = sidecar.signals,
                onEditSignal = { sigName -> onEditSignal(msg.rawId, sigName) },
                onNewSignal = { onNewSignal(msg.rawId) },
                onVerifySignal = { sigName, status, notes ->
                    activeDbcId?.let { dbcId ->
                        val sidecarRepo = settingsVm.dbcRepository.sidecarFor(dbcId)
                        vm.markSignalVerification(sigName, status, notes, sidecarRepo)
                    }
                },
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    msg: DbcMessage,
    live: MessageState?,
    sidecarSignals: Map<String, SignalSidecar>,
    onEditSignal: (String) -> Unit = {},
    onNewSignal: () -> Unit = {},
    onVerifySignal: (signalName: String, status: VerificationStatus, notes: String) -> Unit = { _, _, _ -> },
) {
    var expanded by remember { mutableStateOf(false) }
    val idStr = if (msg.isExtended) "0x%08X".format(msg.canId)
                else "0x%03X".format(msg.canId)
    val hz = live?.let { "${"%.0f".format(it.updateRateHz)} Hz" } ?: "—"

    Column(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(msg.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(idStr, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Text(hz, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }

        if (expanded) {
            msg.signals.forEach { sig ->
                val verification = sidecarSignals[sig.name]?.verifications?.lastOrNull()
                val liveValue = live?.decodedSignals?.get(sig.name)
                SignalRow(
                    name = sig.name,
                    value = liveValue?.let { "${"%.2f".format(it)} ${sig.unit}".trim() } ?: "—",
                    status = verification?.status,
                    vehicleId = verification?.vehicleId,
                    onClick = { onEditSignal(sig.name) },
                    onVerify = { status, notes -> onVerifySignal(sig.name, status, notes) },
                )
            }
            androidx.compose.material3.TextButton(
                onClick = onNewSignal,
                modifier = Modifier.padding(start = 16.dp),
            ) { Text("+ Add signal") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SignalRow(
    name: String,
    value: String,
    status: VerificationStatus?,
    vehicleId: String?,
    onClick: () -> Unit = {},
    onVerify: (VerificationStatus, String) -> Unit = { _, _ -> },
) {
    var showVerifyDialog by remember { mutableStateOf(false) }

    val (icon, tint) = when (status) {
        VerificationStatus.VERIFIED -> Icons.Default.CheckCircle to ColorVerified
        VerificationStatus.SUSPECT -> Icons.Default.Warning to ColorSuspect
        else -> Icons.AutoMirrored.Filled.Help to ColorUnknown
    }

    if (showVerifyDialog) {
        VerificationDialog(
            signalName = name,
            currentStatus = status,
            onConfirm = { newStatus, notes ->
                onVerify(newStatus, notes)
                showVerifyDialog = false
            },
            onDismiss = { showVerifyDialog = false },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showVerifyDialog = true },
            )
            .padding(start = 24.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = status?.name, tint = tint,
                modifier = Modifier.padding(end = 2.dp))
            Column {
                Text(name, fontSize = 13.sp)
                vehicleId?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun VerificationDialog(
    signalName: String,
    currentStatus: VerificationStatus?,
    onConfirm: (VerificationStatus, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedStatus by remember { mutableStateOf(currentStatus ?: VerificationStatus.VERIFIED) }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark: $signalName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VerificationStatus.entries.forEach { s ->
                        val (label, color) = when (s) {
                            VerificationStatus.VERIFIED -> "Verified" to ColorVerified
                            VerificationStatus.SUSPECT -> "Suspect" to ColorSuspect
                            VerificationStatus.UNVERIFIED -> "Unverified" to ColorUnknown
                        }
                        TextButton(
                            onClick = { selectedStatus = s },
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = if (selectedStatus == s) color
                                               else MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        ) { Text(label, fontWeight = if (selectedStatus == s) FontWeight.Bold else FontWeight.Normal) }
                    }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedStatus, notes) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
