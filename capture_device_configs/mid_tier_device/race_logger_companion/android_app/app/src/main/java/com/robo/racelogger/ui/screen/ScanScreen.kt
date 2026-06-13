package com.robo.racelogger.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robo.racelogger.vm.BleState
import com.robo.racelogger.vm.RaceLoggerViewModel

@Composable
fun ScanScreen(vm: RaceLoggerViewModel) {
    val bleState by vm.bleState.collectAsState()
    val devices by vm.scannedDevices.collectAsState()
    val scanning = bleState == BleState.SCANNING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Race Logger", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))

        if (scanning) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            Text("Scanning for race-logger…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Button(onClick = { vm.startScan() }, modifier = Modifier.fillMaxWidth()) {
                Text("Scan for Device")
            }
        }

        if (devices.isNotEmpty()) {
            Text(
                "Found devices",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Start),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices) { d ->
                    Card(
                        onClick = { vm.connect(d.device, d.name) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Box(modifier = Modifier.padding(16.dp)) {
                            Column {
                                Text(d.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${d.device.address}   ${d.rssi} dBm",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
