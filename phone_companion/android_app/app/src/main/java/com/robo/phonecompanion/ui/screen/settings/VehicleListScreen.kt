package com.robo.phonecompanion.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.robo.phonecompanion.data.model.VehicleProfile
import com.robo.phonecompanion.vm.SettingsViewModel

@Composable
fun VehicleListScreen(
    vm: SettingsViewModel,
    onVehicleDetail: (String) -> Unit,
    onNewVehicle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vehicles by vm.vehicles.collectAsState()
    var pendingDelete by remember { mutableStateOf<VehicleProfile?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { onNewVehicle() }) {
                Icon(Icons.Default.Add, contentDescription = "Add vehicle")
            }
        },
        modifier = modifier,
    ) { inner ->
        if (vehicles.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp)) {
                Text("No vehicles yet. Tap + to add one.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(inner)) {
                items(vehicles, key = { it.id }) { v ->
                    ListItem(
                        headlineContent = { Text(v.nickname) },
                        supportingContent = {
                            Text("${v.year} ${v.make} ${v.model}" +
                                if (v.engine.isNotBlank()) " · ${v.engine}" else "")
                        },
                        trailingContent = {
                            IconButton(onClick = { pendingDelete = v }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        modifier = Modifier.clickable { onVehicleDetail(v.id) },
                    )
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }

    pendingDelete?.let { v ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${v.nickname}?") },
            text = { Text("This removes the vehicle profile from the app. The profile file will be staged for removal on the next sync.") },
            confirmButton = {
                Button(
                    onClick = { vm.deleteVehicle(v.id); pendingDelete = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}
