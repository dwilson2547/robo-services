package com.robo.phonecompanion

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.activity.ComponentActivity
import com.robo.phonecompanion.ui.navigation.AppNavigation
import com.robo.phonecompanion.ui.theme.PhoneCompanionTheme
import com.robo.phonecompanion.vm.CanBusViewModel
import com.robo.phonecompanion.vm.ConnectionState
import com.robo.phonecompanion.vm.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val vm: CanBusViewModel by viewModels()
    private val settingsVm: SettingsViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions granted or denied — UI reflects state via ViewModel */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBluetoothPermissions()

        setContent {
            PhoneCompanionTheme {
                AppNavigation(canBusVm = vm, settingsVm = settingsVm)

                // Device picker dialog — shown while scanning or after scan completes
                val connectionState by vm.connectionState.collectAsState()
                val devices by vm.scannedDevices.collectAsState()

                val showPicker = devices.isNotEmpty() &&
                    connectionState is ConnectionState.Scanning ||
                    (connectionState is ConnectionState.Disconnected && devices.isNotEmpty())

                if (showPicker) {
                    AlertDialog(
                        onDismissRequest = { vm.dismissScanDialog() },
                        title = { Text("Select Device") },
                        text = {
                            LazyColumn {
                                items(devices) { scanned ->
                                    TextButton(
                                        onClick = { vm.connectDevice(scanned.device) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            Text(scanned.name, fontSize = 14.sp)
                                            Text(
                                                "${scanned.device.address}  ${scanned.rssi} dBm",
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OutlinedButton(onClick = { vm.startScan() }) { Text("Rescan") }
                                Button(onClick = { vm.dismissScanDialog() }) { Text("Skip") }
                            }
                        },
                    )
                }
            }
        }

        // Start scan automatically on first launch if we have permissions
        if (hasBluetoothPermissions()) vm.startScan()
    }

    private fun requestBluetoothPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) add(Manifest.permission.BLUETOOTH_SCAN)
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun hasBluetoothPermissions(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasPermission(p: String) =
        ActivityCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}
