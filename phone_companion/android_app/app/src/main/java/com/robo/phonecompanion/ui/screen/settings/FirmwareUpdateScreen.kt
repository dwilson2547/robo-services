package com.robo.phonecompanion.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.robo.phonecompanion.vm.CanBusViewModel
import com.robo.phonecompanion.vm.ConnectionState
import com.robo.phonecompanion.vm.OtaState

@Composable
fun FirmwareUpdateScreen(
    vm: CanBusViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val bundledVersion = remember {
        runCatching {
            context.assets.open("firmware/version.txt").bufferedReader().readText().trim()
        }.getOrElse { "not bundled" }
    }
    val firmwareAvailable = remember {
        runCatching { context.assets.open("firmware/firmware.bin").close(); true }.getOrElse { false }
    }

    val connectionState by vm.connectionState.collectAsState()
    val deviceVersion by vm.deviceFirmwareVersion.collectAsState()
    val otaState by vm.otaState.collectAsState()

    val isConnected = connectionState is ConnectionState.Connected

    LaunchedEffect(isConnected) {
        if (isConnected) vm.readDeviceFirmwareVersion()
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Firmware Update", style = MaterialTheme.typography.titleMedium)
        Text(
            "Updates are transferred over BLE. Keep the phone near the dongle and do not " +
                "disconnect during the update. The dongle will restart automatically when done.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VersionRow("Device version", deviceVersion ?: if (isConnected) "reading…" else "not connected")
                VersionRow("Bundled version", bundledVersion)
                if (isConnected && !vm.isOtaServicePresent && deviceVersion == null) {
                    HorizontalDivider()
                    Text(
                        "OTA service not detected. This firmware was built before OTA support was " +
                            "added — flash via USB once to enable wireless updates.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        when (val state = otaState) {
            is OtaState.Idle -> {
                val updateNeeded = deviceVersion != null && deviceVersion != bundledVersion
                val canUpdate = isConnected && vm.isOtaServicePresent && firmwareAvailable

                if (updateNeeded) {
                    Text(
                        "Update available: $deviceVersion → $bundledVersion",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (deviceVersion != null && deviceVersion == bundledVersion) {
                    Text("Device is up to date.", color = MaterialTheme.colorScheme.tertiary)
                }

                Button(
                    onClick = {
                        val firmware = context.assets.open("firmware/firmware.bin").readBytes()
                        vm.startOta(firmware)
                    },
                    enabled = canUpdate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (updateNeeded) "Update Dongle  ($bundledVersion)" else "Re-flash Dongle")
                }

                if (!firmwareAvailable) {
                    Text(
                        "No firmware bundled. Run scripts/build_and_stage_firmware.sh and rebuild the app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is OtaState.Uploading -> {
                val progress = state.sent.toFloat() / state.total
                val sentKb = state.sent / 1024
                val totalKb = state.total / 1024
                Text("Uploading… $sentKb / $totalKb KB (${(progress * 100).toInt()}%)")
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is OtaState.Verifying -> {
                Text("Verifying image…")
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            is OtaState.Complete -> {
                Text(
                    "Update complete. Dongle is restarting — it will reconnect shortly.",
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            is OtaState.Error -> {
                Text(
                    "Error: ${state.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { vm.resetOtaState() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Dismiss") }
            }
        }
    }
}

@Composable
private fun VersionRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
