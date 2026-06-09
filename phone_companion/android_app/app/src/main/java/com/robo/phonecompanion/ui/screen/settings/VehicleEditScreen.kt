package com.robo.phonecompanion.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.robo.phonecompanion.data.model.VehicleProfile
import com.robo.phonecompanion.vm.SettingsViewModel
import java.util.UUID

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VehicleEditScreen(
    vm: SettingsViewModel,
    vehicleId: String?,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val existing = remember(vehicleId) {
        vehicleId?.let { vm.vehicleRepository.load(it) }
    }

    var nickname by rememberSaveable { mutableStateOf(existing?.nickname ?: "") }
    var year by rememberSaveable { mutableStateOf(existing?.year?.toString() ?: "") }
    var make by rememberSaveable { mutableStateOf(existing?.make ?: "") }
    var model by rememberSaveable { mutableStateOf(existing?.model ?: "") }
    var engine by rememberSaveable { mutableStateOf(existing?.engine ?: "") }
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }

    val dbcIds by vm.dbcIds.collectAsState()
    val selectedDbcs = remember {
        mutableStateListOf<String>().apply { existing?.dbcIds?.let { addAll(it) } }
    }

    val isValid = nickname.isNotBlank() && make.isNotBlank() && model.isNotBlank() &&
        year.toIntOrNull() != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it },
            label = { Text("Nickname *") },
            placeholder = { Text("e.g. 02 Silverado") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = year,
            onValueChange = { year = it },
            label = { Text("Year *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            value = make,
            onValueChange = { make = it },
            label = { Text("Make *") },
            placeholder = { Text("Chevrolet") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model *") },
            placeholder = { Text("Silverado 1500") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = engine,
            onValueChange = { engine = it },
            label = { Text("Engine") },
            placeholder = { Text("5.3L LM7") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
        )

        if (dbcIds.isNotEmpty()) {
            Text("Applicable DBCs", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dbcIds.forEach { id ->
                    FilterChip(
                        selected = id in selectedDbcs,
                        onClick = {
                            if (id in selectedDbcs) selectedDbcs.remove(id)
                            else selectedDbcs.add(id)
                        },
                        label = { Text(id) },
                    )
                }
            }
        }

        Button(
            onClick = {
                val profile = VehicleProfile(
                    id = existing?.id ?: UUID.randomUUID().toString().take(8),
                    nickname = nickname.trim(),
                    year = year.toInt(),
                    make = make.trim(),
                    model = model.trim(),
                    engine = engine.trim(),
                    notes = notes.trim(),
                    dbcIds = selectedDbcs.toList(),
                )
                vm.saveVehicle(profile)
                onSaved()
            },
            enabled = isValid,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (existing != null) "Save changes" else "Add vehicle")
        }
    }
}
