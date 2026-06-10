package com.robo.phonecompanion.ui.screen.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robo.phonecompanion.ui.theme.ColorVerified
import com.robo.phonecompanion.vm.CanBusViewModel
import com.robo.phonecompanion.vm.SettingsViewModel
import kotlinx.coroutines.launch

@Composable
fun DbcListScreen(
    vm: SettingsViewModel,
    canBusVm: CanBusViewModel,
    modifier: Modifier = Modifier,
) {
    val dbcIds by vm.dbcIds.collectAsState()
    val activeDbc by canBusVm.activeDbc.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var showNewDialog by remember { mutableStateOf(false) }
    var newDbcName by remember { mutableStateOf("") }
    var showStarterDialog by remember { mutableStateOf(false) }

    if (showStarterDialog) {
        StarterDbcDialog(
            onDismiss = { showStarterDialog = false },
            onVisitOpendbc = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/commaai/opendbc"))
                )
            },
            onLoad = {
                showStarterDialog = false
                vm.importStarterDbcs { result ->
                    scope.launch {
                        val msg = when {
                            result.imported.isEmpty() && result.skipped.isNotEmpty() ->
                                "All starter DBCs already loaded."
                            result.imported.isNotEmpty() && result.skipped.isEmpty() ->
                                "Loaded ${result.imported.size} DBC${if (result.imported.size > 1) "s" else ""}."
                            else ->
                                "Loaded ${result.imported.size} new, skipped ${result.skipped.size} already present."
                        }
                        snackbar.showSnackbar(msg)
                    }
                }
            },
        )
    }

    if (showNewDialog) {
        AlertDialog(
            onDismissRequest = { showNewDialog = false; newDbcName = "" },
            title = { Text("New DBC file") },
            text = {
                Column {
                    Text(
                        "A blank DBC will be created locally. Use the signal editor to populate it, then push to Git to save.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    OutlinedTextField(
                        value = newDbcName,
                        onValueChange = { newDbcName = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' } },
                        label = { Text("DBC name (no spaces)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = newDbcName.isBlank() || dbcIds.contains(newDbcName.trim()),
                        supportingText = {
                            if (dbcIds.contains(newDbcName.trim()))
                                Text("Name already exists", color = MaterialTheme.colorScheme.error)
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.createDbc(newDbcName)
                        showNewDialog = false
                        newDbcName = ""
                    },
                    enabled = newDbcName.isNotBlank() && !dbcIds.contains(newDbcName.trim()),
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false; newDbcName = "" }) { Text("Cancel") }
            },
        )
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "New DBC")
            }
        },
    ) { inner ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(inner)) {
            // Starter DBC banner — always shown so users can re-import or discover it
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showStarterDialog = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Default.Download, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Load Starter DBCs",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary)
                        Text("8 real-world DBC files from comma.ai opendbc (unaffiliated)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(thickness = 0.5.dp)
            }

            if (dbcIds.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                        Text(
                            "No DBC files yet. Load the starter pack above, clone a git repository, or tap + to create a new file.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(dbcIds, key = { it }) { id ->
                    val isActive = activeDbc != null &&
                        vm.dbcRepository.load(id)?.messages?.size == activeDbc!!.messages.size

                    ListItem(
                        headlineContent = { Text(id) },
                        supportingContent = {
                            val count = vm.dbcRepository.load(id)?.messages?.size ?: 0
                            Text("$count message${if (count == 1) "" else "s"}")
                        },
                        trailingContent = {
                            if (isActive) {
                                Icon(Icons.Default.CheckCircle, contentDescription = "Active",
                                    tint = ColorVerified)
                            }
                        },
                        modifier = Modifier.clickable {
                            val loaded = vm.loadDbc(id)
                            if (loaded != null) {
                                val (dbc, sidecar) = loaded
                                canBusVm.setActiveDbc(dbc, sidecar, id)
                            }
                        },
                    )
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}

private val OPENDBC_VEHICLES = listOf(
    "Acura ILX (2016)",
    "BMW E9x / E8x",
    "Ford Fusion (2018)",
    "GM Global A — lowspeed bus",
    "Hyundai i30 (2014)",
    "Mazda (2017 platform)",
    "Tesla Model 3",
    "Toyota (2017 powertrain ref)",
)

@Composable
private fun StarterDbcDialog(
    onDismiss: () -> Unit,
    onVisitOpendbc: () -> Unit,
    onLoad: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Starter DBCs") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "These signal definitions come from the comma.ai opendbc project — " +
                        "an open-source community database of vehicle CAN bus definitions " +
                        "maintained by researchers and enthusiasts worldwide. " +
                        "This app is not affiliated with or endorsed by comma.ai.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Vehicles included:", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    OPENDBC_VEHICLES.forEach { vehicle ->
                        Text("• $vehicle",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Distributed under the MIT License. © 2020 Comma.ai, Inc.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                HorizontalDivider()
                Text(
                    "If you reverse-engineer signals on your vehicle, consider contributing " +
                        "them back to opendbc — every addition makes the database more useful " +
                        "for the whole community.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onLoad) { Text("Load DBCs") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onVisitOpendbc) { Text("Visit opendbc ↗") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
