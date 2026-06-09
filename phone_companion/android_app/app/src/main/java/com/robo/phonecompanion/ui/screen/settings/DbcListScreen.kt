package com.robo.phonecompanion.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.robo.phonecompanion.ui.theme.ColorVerified
import com.robo.phonecompanion.vm.CanBusViewModel
import com.robo.phonecompanion.vm.SettingsViewModel

@Composable
fun DbcListScreen(
    vm: SettingsViewModel,
    canBusVm: CanBusViewModel,
    modifier: Modifier = Modifier,
) {
    val dbcIds by vm.dbcIds.collectAsState()
    val activeDbc by canBusVm.activeDbc.collectAsState()

    if (dbcIds.isEmpty()) {
        Box(modifier = modifier.fillMaxSize().padding(24.dp)) {
            Text(
                "No DBC files found. Clone a git repository containing a dbcs/ directory.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize()) {
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
