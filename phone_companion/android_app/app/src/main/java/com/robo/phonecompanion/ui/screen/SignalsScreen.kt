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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
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
import com.robo.phonecompanion.vm.OdbCrossRef
import com.robo.phonecompanion.vm.SettingsViewModel
import com.robo.phonecompanion.vm.SignalHealth

private val ColorHealthWarning = Color(0xFFFFB300)

@Composable
fun SignalsScreen(
    vm: CanBusViewModel,
    settingsVm: SettingsViewModel,
    onEditSignal: (rawId: Int, signalName: String) -> Unit = { _, _ -> },
    onNewSignal: (rawId: Int) -> Unit = {},
    onInspect: (canId: Int) -> Unit = {},
    onHealthPanel: () -> Unit = {},
    onGraphScreen: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val known by vm.knownMessages.collectAsState()
    val sidecar by vm.activeSidecar.collectAsState()
    val dbc by vm.activeDbc.collectAsState()
    val activeDbcId by vm.activeDbcId.collectAsState()
    val health by vm.signalHealth.collectAsState()
    val obdCrossRefs by vm.obdCrossRefs.collectAsState()
    val pinnedSignalKeys by vm.pinnedSignalKeys.collectAsState()

    if (dbc == null) {
        Box(modifier = modifier.fillMaxSize().padding(24.dp)) {
            Text("No DBC loaded. Load one from Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val allMessages = dbc!!.messages.values.sortedBy { it.name }

    var query by remember { mutableStateOf("") }

    val messages = if (query.isEmpty()) allMessages
    else allMessages.filter {
        it.name.contains(query, ignoreCase = true) ||
            "0x%03X".format(it.canId).contains(query.uppercase())
    }

    val seenCount = allMessages.count { known.containsKey(it.canId) }
    val flaggedCount = health.count { (_, h) -> h.isStuck || h.isPegged }

    LazyColumn(modifier = modifier.fillMaxSize()) {
        // Coverage summary row
        item {
            Text(
                "$seenCount / ${allMessages.size} messages seen this session",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (seenCount == allMessages.size) ColorVerified
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Health warning banner — only when there are flagged signals
        if (flaggedCount > 0) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onHealthPanel() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.Warning, null,
                        tint = ColorHealthWarning, modifier = Modifier.size(16.dp))
                    Text(
                        "$flaggedCount signal${if (flaggedCount > 1) "s" else ""} with health warnings",
                        style = MaterialTheme.typography.labelSmall,
                        color = ColorHealthWarning,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null,
                        tint = ColorHealthWarning, modifier = Modifier.size(14.dp))
                }
            }
        }

        // Graph banner — shown when at least one signal is pinned
        if (pinnedSignalKeys.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onGraphScreen() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ShowChart, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text(
                        "Graph (${pinnedSignalKeys.size} signal${if (pinnedSignalKeys.size > 1) "s" else ""} pinned)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                }
            }
        }

        // Search bar
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Filter by name or ID") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Clear, null)
                        }
                    }
                },
            )
        }

        items(messages, key = { it.rawId }) { msg ->
            val liveState = known[msg.canId]
            MessageRow(
                msg = msg,
                live = liveState,
                sidecarSignals = sidecar.signals,
                health = health,
                obdCrossRefs = obdCrossRefs,
                pinnedSignalKeys = pinnedSignalKeys,
                onEditSignal = { sigName -> onEditSignal(msg.rawId, sigName) },
                onNewSignal = { onNewSignal(msg.rawId) },
                onInspect = { onInspect(msg.canId) },
                onDeleteSignal = { sigName ->
                    vm.deleteSignal(msg.rawId, sigName, settingsVm)
                },
                onDeleteMessage = {
                    vm.deleteMessage(msg.rawId, settingsVm)
                },
                onVerifySignal = { sigName, status, notes ->
                    activeDbcId?.let { dbcId ->
                        val sidecarRepo = settingsVm.dbcRepository.sidecarFor(dbcId)
                        vm.markSignalVerification(sigName, status, notes, sidecarRepo)
                    }
                },
                onTogglePin = { sigName ->
                    val key = "${msg.name}/$sigName"
                    if (key in pinnedSignalKeys) vm.unpinSignal(key) else vm.pinSignal(key)
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
    health: Map<String, SignalHealth> = emptyMap(),
    obdCrossRefs: Map<String, OdbCrossRef> = emptyMap(),
    pinnedSignalKeys: List<String> = emptyList(),
    onEditSignal: (String) -> Unit = {},
    onNewSignal: () -> Unit = {},
    onInspect: () -> Unit = {},
    onDeleteSignal: (String) -> Unit = {},
    onDeleteMessage: () -> Unit = {},
    onVerifySignal: (signalName: String, status: VerificationStatus, notes: String) -> Unit = { _, _, _ -> },
    onTogglePin: (signalName: String) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showDeleteMsgDialog by remember { mutableStateOf(false) }

    val idStr = if (msg.isExtended) "0x%08X".format(msg.canId)
                else "0x%03X".format(msg.canId)
    val hz = live?.let { "${"%.0f".format(it.updateRateHz)} Hz" } ?: "—"
    val seenColor = if (live != null) ColorVerified.copy(alpha = 0.7f)
                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)

    if (showDeleteMsgDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteMsgDialog = false },
            title = { Text("Delete message?") },
            text = { Text("Remove \"${msg.name}\" and all its signals from the DBC?") },
            confirmButton = {
                TextButton(onClick = { onDeleteMessage(); showDeleteMsgDialog = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteMsgDialog = false }) { Text("Cancel") }
            },
        )
    }

    Column(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Seen indicator dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .padding(end = 0.dp),
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
                    drawCircle(color = seenColor)
                }
            }
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(msg.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(idStr, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            Text(hz, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)

            Box {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Message options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Inspect frames") },
                        onClick = { showOverflowMenu = false; onInspect() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete message", color = MaterialTheme.colorScheme.error) },
                        onClick = { showOverflowMenu = false; showDeleteMsgDialog = true },
                    )
                }
            }
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
                    signalHealth = health[sig.name],
                    crossRef = obdCrossRefs["${msg.name}/${sig.name}"],
                    isPinned = "${msg.name}/${sig.name}" in pinnedSignalKeys,
                    onClick = { onEditSignal(sig.name) },
                    onDelete = { onDeleteSignal(sig.name) },
                    onVerify = { status, notes -> onVerifySignal(sig.name, status, notes) },
                    onTogglePin = { onTogglePin(sig.name) },
                )
            }
            androidx.compose.material3.TextButton(
                onClick = onNewSignal,
                modifier = Modifier.padding(start = 16.dp),
            ) { Text("+ Add signal") }
        }
    }
}

private val ColorOdbConfirmed = Color(0xFF66BB6A)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SignalRow(
    name: String,
    value: String,
    status: VerificationStatus?,
    vehicleId: String?,
    signalHealth: SignalHealth? = null,
    crossRef: OdbCrossRef? = null,
    isPinned: Boolean = false,
    onClick: () -> Unit = {},
    onDelete: () -> Unit = {},
    onVerify: (VerificationStatus, String) -> Unit = { _, _ -> },
    onTogglePin: () -> Unit = {},
) {
    var showVerifyDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val (icon, tint) = when (status) {
        VerificationStatus.VERIFIED -> Icons.Default.CheckCircle to ColorVerified
        VerificationStatus.SUSPECT -> Icons.Default.Warning to ColorSuspect
        else -> Icons.AutoMirrored.Filled.Help to ColorUnknown
    }

    val healthFlag = signalHealth?.isStuck == true || signalHealth?.isPegged == true
    val healthLabel = when {
        signalHealth?.isStuck == true && signalHealth.isPegged -> "Stuck+Pegged"
        signalHealth?.isStuck == true -> "Stuck"
        signalHealth?.isPegged == true -> "Pegged"
        else -> null
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

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete signal?") },
            text = { Text("Remove \"$name\" from this message?") },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteDialog = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showVerifyDialog = true },
            )
            .padding(start = 24.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f)) {
            Icon(icon, contentDescription = status?.name, tint = tint,
                modifier = Modifier.padding(end = 2.dp))
            Column {
                Text(name, fontSize = 13.sp)
                vehicleId?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (healthLabel != null) {
                    Text(
                        healthLabel,
                        fontSize = 10.sp,
                        color = ColorHealthWarning,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (crossRef != null) {
                    Text(
                        "OBD ✓  ${crossRef.pidName}  r=${"%.2f".format(crossRef.correlation)}",
                        fontSize = 10.sp,
                        color = ColorOdbConfirmed,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(end = 4.dp))
        if (healthFlag) {
            Icon(Icons.Default.Warning, contentDescription = healthLabel,
                tint = ColorHealthWarning, modifier = Modifier.size(16.dp))
        }
        IconButton(
            onClick = onTogglePin,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ShowChart, contentDescription = if (isPinned) "Unpin" else "Pin to graph",
                tint = if (isPinned) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp))
        }
        IconButton(
            onClick = { showDeleteDialog = true },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(Icons.Default.Delete, contentDescription = "Delete signal",
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp))
        }
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
