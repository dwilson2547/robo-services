package com.robo.phonegateway.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robo.phonegateway.BuildConfig
import com.robo.phonegateway.Config
import com.robo.phonegateway.GatewayStatus
import com.robo.phonegateway.ble.CanFilter
import com.robo.phonegateway.ble.DongleOta
import com.robo.phonegateway.log.FrameLogCleanup
import com.robo.phonegateway.registry.DbcChoice
import com.robo.phonegateway.registry.RegistryException
import com.robo.phonegateway.registry.Units
import com.robo.phonegateway.registry.Vehicle

@Composable
fun SettingsScreen(app: App, onDiagnostics: () -> Unit) {
    val signedIn by app.auth.signedIn.collectAsStateWithLifecycle()
    val status by GatewayStatus.snapshot.collectAsStateWithLifecycle()
    val vehicles by app.vehicles.vehicles.collectAsStateWithLifecycle()
    val selectedId by app.vehicles.selectedId.collectAsStateWithLifecycle()
    var me by remember { mutableStateOf<Pair<String, String>?>(null) }
    var editing by remember { mutableStateOf<Vehicle?>(null) }
    var adding by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    LaunchedEffect(signedIn) { me = if (signedIn) runCatching { app.registry.me() }.getOrNull() else null }

    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 8.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader("Account")
        if (signedIn) {
            Text(me?.let { "${it.first} · ${it.second}" } ?: "signed in", style = MaterialTheme.typography.bodyLarge)
            OutlinedButton(onClick = app::signOut, enabled = !status.running) { Text("Sign out") }
        } else {
            Button(onClick = app::startSignIn) { Text("Sign in") }
        }

        SectionHeader("Vehicles")
        if (vehicles.isEmpty()) Text("No vehicles yet.", style = MaterialTheme.typography.bodyMedium)
        vehicles.sortedBy { it.name }.forEach { v ->
            Row(
                Modifier.fillMaxWidth().clickable(enabled = signedIn) { editing = v }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(v.label() + if (v.id == selectedId) "  (selected)" else "", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (v.canFilter.isEmpty()) "CAN filter: all ids" else "CAN filter: ${v.canFilter.size} ids",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("Edit ›", color = MaterialTheme.colorScheme.primary)
            }
            HorizontalDivider()
        }
        OutlinedButton(onClick = { adding = true }, enabled = signedIn) { Text("Add vehicle") }

        SectionHeader("Units")
        val context = androidx.compose.ui.platform.LocalContext.current
        val units by Units.state(context).collectAsStateWithLifecycle()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Units.IMPERIAL to "Imperial (mi, mph, °F)", Units.METRIC to "Metric (km, km/h, °C)").forEach { (u, label) ->
                androidx.compose.material3.FilterChip(selected = units == u, onClick = { Units.set(context, u) }, label = { Text(label) })
            }
        }

        SectionHeader("Dongle")
        StatusLine("State", status.dongle)
        StatusLine("Remembered", app.dongleSettings.rememberedAddress ?: "none (uses the first one seen)")
        val filter = app.dongleSettings.filterIds
        StatusLine("Filter", if (filter.isEmpty()) "all ids" else "${filter.size} ids, from the selected vehicle")
        OutlinedButton(
            onClick = {
                if (confirmForget) { app.forgetDongle(); confirmForget = false } else confirmForget = true
            },
            enabled = !status.running,
        ) { Text(if (confirmForget) "Tap again to forget" else "Forget dongle") }
        DongleFirmware(app, status)

        SectionHeader("Phone storage")
        PhoneStorage(app, status.running)

        SectionHeader("Diagnostics")
        Text(
            "Frame, IMU and BLE counters ›",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onDiagnostics() }.padding(vertical = 6.dp),
        )

        SectionHeader("About")
        StatusLine("App", BuildConfig.VERSION_NAME)
        StatusLine("Device", app.deviceId)
        StatusLine("Registry", Config.API_BASE)
        StatusLine("Broker", Config.BROKER_URIS.joinToString(", "))
    }
    editing?.let { v -> VehicleDialog(app, v) { editing = null } }
    if (adding) VehicleDialog(app, null) { adding = false }
}

/**
 * The dongle's firmware against the one bundled in this app, and the update button:
 * shown while a dongle with the OTA service is connected and nothing is recording.
 */
@Composable
private fun DongleFirmware(app: App, status: GatewayStatus.Snapshot) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bundled = remember { DongleOta.bundledVersion(context) }
    var confirm by remember { mutableStateOf(false) }
    StatusLine(
        "Firmware",
        (status.dongleFirmware ?: if (status.dongleConnected) "unknown" else "-") +
            (bundled?.let { " (app has $it)" } ?: ""),
    )
    status.dongleUpdate?.let { StatusLine("Update", it) }
    if (bundled == null || !status.dongleConnected || status.running) return
    val newer = DongleOta.isNewer(bundled, status.dongleFirmware)
    // the same version again recovers a dongle; an older one is never offered
    val same = status.dongleFirmware == bundled
    if (!status.dongleUpdating && !newer && !same) return
    val verb = if (newer) "update" else "reinstall"
    OutlinedButton(
        onClick = { if (confirm) { app.updateDongle(); confirm = false } else confirm = true },
        enabled = !status.dongleUpdating,
    ) {
        Text(
            when {
                status.dongleUpdating -> "Updating…"
                confirm -> "Tap again to $verb $bundled"
                newer -> "Update dongle to $bundled"
                else -> "Reinstall $bundled"
            },
        )
    }
}

/** The capture's counters (frames, IMU, BLE), live. */
@Composable
fun DiagnosticsScreen() {
    val status by GatewayStatus.snapshot.collectAsStateWithLifecycle()
    Column(
        Modifier.padding(horizontal = 20.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (status.sessionId != 0L) StatusLine("Session", "${status.sessionId}" + if (status.running) "" else " (ended)")
        StatusLine("Broker", status.broker)
        StatusLine("Frames logged", status.framesLogged.toString())
        StatusLine("Frames acked", status.framesAcked.toString())
        StatusLine("Frames not sent", status.framesNotSent.toString())
        StatusLine("IMU seconds dropped", status.imuIncomplete.toString())
        StatusLine("IMU points filled", status.imuMissingPoints.toString())
        StatusLine("IMU gaps", "dongle ${status.imuDongleGaps}, clock ${status.imuClockJumps}")
        StatusLine("IMU last second", "${status.lastImuSamples} samples, max gap ${status.lastImuMaxGapMs} ms")
        StatusLine("Late samples dropped", status.lateSamples.toString())
        StatusLine("Unparsed dongle lines", status.badLines.toString())
        StatusLine("Cut-off notifications", status.truncatedNotifications.toString())
        StatusLine(
            "BLE link",
            (if (status.bleMtu == 0) "MTU -" else "MTU ${status.bleMtu}") +
                when (status.blePhy) { 1 -> ", 1M PHY"; 2 -> ", 2M PHY"; 3 -> ", coded PHY"; else -> "" },
        )
        status.dongleStats?.let { StatusLine("Dongle 5 s", it) }
        status.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/**
 * Add (vehicle = null) or edit a vehicle: name, year, make, model, DBC and CAN filter.
 * Saving the selected vehicle applies its filter to the dongle at once.
 */
@Composable
fun VehicleDialog(app: App, vehicle: Vehicle?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(vehicle?.name ?: "") }
    var year by remember { mutableStateOf(vehicle?.year?.toString() ?: "") }
    var make by remember { mutableStateOf(vehicle?.make ?: "") }
    var model by remember { mutableStateOf(vehicle?.model ?: "") }
    var filterText by remember { mutableStateOf(CanFilter.format(vehicle?.canFilter ?: app.dongleSettings.filterIds)) }
    var dbcs by remember { mutableStateOf<List<DbcChoice>>(emptyList()) }
    var dbcId by remember { mutableStateOf(vehicle?.dbcId) }
    var dbcMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { dbcs = runCatching { app.registry.dbcs() }.getOrDefault(emptyList()) }
    val yearOk = year.isBlank() || year.toIntOrNull()?.let { it in 1900..2100 } == true
    val filter = CanFilter.parse(filterText)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (vehicle == null) "Add vehicle" else "Edit ${vehicle.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(year, { year = it }, label = { Text("Year") }, singleLine = true, isError = !yearOk)
                OutlinedTextField(make, { make = it }, label = { Text("Make") }, singleLine = true)
                OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true)
                Box {
                    OutlinedButton(onClick = { dbcMenu = true }, enabled = dbcs.isNotEmpty()) {
                        Text("DBC: " + (dbcs.firstOrNull { it.id == dbcId }?.name ?: if (dbcId != null) "#$dbcId" else "default"))
                    }
                    DropdownMenu(expanded = dbcMenu, onDismissRequest = { dbcMenu = false }) {
                        DropdownMenuItem(text = { Text("default") }, onClick = { dbcId = null; dbcMenu = false })
                        dbcs.forEach { d -> DropdownMenuItem(text = { Text(d.name) }, onClick = { dbcId = d.id; dbcMenu = false }) }
                    }
                }
                OutlinedTextField(
                    filterText, { filterText = it },
                    label = { Text("CAN ids to forward (hex; empty = all)") },
                    isError = filter == null,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    supportingText = {
                        Text(when {
                            filter == null -> "hex CAN ids, at most ${CanFilter.MAX_IDS}"
                            filter.isEmpty() -> "dongle forwards every id"
                            else -> "dongle forwards ${filter.size} ids"
                        })
                    },
                )
                if (vehicle != null) {
                    TextButton(onClick = {
                        if (!confirmDelete) { confirmDelete = true; return@TextButton }
                        onDismiss()
                        app.launch {
                            try {
                                app.registry.delete(vehicle.id)
                                app.message = "deleted ${vehicle.name}"
                            } catch (e: RegistryException) {
                                app.message = "could not delete: ${e.message}"
                            }
                            app.refreshVehicles()
                        }
                    }) { Text(if (confirmDelete) "Tap again to delete" else "Delete vehicle", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && yearOk && filter != null, onClick = {
                val draft = Vehicle(
                    id = vehicle?.id ?: 0, name = name.trim(), year = year.trim().toIntOrNull(),
                    make = make.trim().ifEmpty { null }, model = model.trim().ifEmpty { null },
                    canFilter = filter!!, dbcId = dbcId,
                )
                onDismiss()
                app.launch {
                    try {
                        val saved = if (vehicle == null) app.registry.create(draft) else app.registry.update(draft)
                        app.vehicles.upsert(saved)
                        if (vehicle == null || app.vehicles.selectedId.value == saved.id) app.selectVehicle(saved)
                        app.message = "saved ${saved.name}"
                    } catch (e: RegistryException) {
                        app.message = "could not save: ${e.message}"
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Frame logs on the phone, and the cleanup rule (also run once each time the app opens). */
@Composable
private fun PhoneStorage(app: App, running: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var logs by remember { mutableStateOf(FrameLogCleanup.list(FrameLogCleanup.dir(context))) }
    var busy by remember { mutableStateOf(false) }
    val mb = { bytes: Long -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0) }
    StatusLine("Frame logs", "${logs.size} sessions, ${mb(logs.sumOf { it.bytes })}")
    Text(
        "A session's log stays until the registry has it complete, then ${FrameLogCleanup.KEEP_DAYS} more days. " +
            "Others stay until the 5 GB cap.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(enabled = !busy && logs.isNotEmpty(), onClick = {
        busy = true
        app.launch {
            try {
                val r = FrameLogCleanup.run(context, app.registry)
                app.message = if (r.deleted == 0) "nothing to clean up yet" else "deleted ${r.deleted} logs (${mb(r.deletedBytes)})"
            } catch (e: RegistryException) {
                app.message = "cleanup needs the registry: ${e.message}"
            }
            logs = FrameLogCleanup.list(FrameLogCleanup.dir(context))
            busy = false
        }
    }) { Text(if (busy) "Cleaning up…" else "Clean up now") }
}
