package com.robo.racelogger

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import com.robo.racelogger.ui.navigation.AppNavigation
import com.robo.racelogger.ui.theme.RaceLoggerTheme
import com.robo.racelogger.vm.RaceLoggerViewModel

class MainActivity : ComponentActivity() {

    private val vm: RaceLoggerViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (hasBlePermissions()) vm.startScan()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RaceLoggerTheme {
                AppNavigation(vm = vm)
            }
        }
        if (hasBlePermissions()) {
            vm.startScan()
        } else {
            requestAllPermissions()
        }
    }

    private fun requestAllPermissions() {
        val needed = buildList {
            // Location — WiFi scanning on all API levels; also covers BLE scanning on API ≤30
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION))
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            // BLE — fine-grained permissions on API 31+
            if (Build.VERSION.SDK_INT >= 31) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN))
                    add(Manifest.permission.BLUETOOTH_SCAN)
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT))
                    add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun hasBlePermissions(): Boolean {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return false
        return if (Build.VERSION.SDK_INT >= 31) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN) &&
                hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else true
    }

    private fun hasPermission(p: String) =
        ActivityCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
}
