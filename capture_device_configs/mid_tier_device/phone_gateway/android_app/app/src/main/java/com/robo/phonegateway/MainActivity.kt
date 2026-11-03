package com.robo.phonegateway

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.robo.phonegateway.auth.Auth
import com.robo.phonegateway.ble.CanFilter
import com.robo.phonegateway.ble.DongleClient
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var auth: Auth
    private var message by mutableStateOf<String?>(null)

    private val signIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: run {
            message = "sign-in cancelled"
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            message = runCatching { auth.completeSignIn(data) }
                .fold({ null }, { "sign-in failed: ${it.message}" })
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) GatewayService.start(this)
        else message = "capture needs Bluetooth, location and notifications"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = Auth.get(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Screen()
                }
            }
        }
    }

    @Composable
    private fun Screen() {
        val signedIn by auth.signedIn.collectAsStateWithLifecycle()
        val status by GatewayStatus.snapshot.collectAsStateWithLifecycle()
        var confirmForget by remember { mutableStateOf(false) }
        val dongleSettings = remember { DongleClient(this@MainActivity, { _, _ -> }, {}) }
        var filterText by remember { mutableStateOf(CanFilter.format(dongleSettings.filterIds)) }
        val filterIds = CanFilter.parse(filterText)

        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Phone Gateway", style = MaterialTheme.typography.headlineSmall)
            Text("Device ${GatewayService.deviceId(this@MainActivity)}", style = MaterialTheme.typography.bodySmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (signedIn) {
                    OutlinedButton(onClick = { lifecycleScope.launch { auth.signOut() } }) { Text("Sign out") }
                } else {
                    Button(onClick = { startSignIn() }) { Text("Sign in") }
                }
                if (status.running) {
                    Button(onClick = { GatewayService.stop(this@MainActivity) }) { Text("Stop capture") }
                } else {
                    Button(onClick = { startCapture() }, enabled = signedIn) { Text("Start capture") }
                }
            }

            StatusLine("Dongle", status.dongle)
            StatusLine("Broker", status.broker)
            StatusLine("Frames logged", status.framesLogged.toString())
            StatusLine("Frames acked", status.framesAcked.toString())
            StatusLine("Frames not sent", status.framesNotSent.toString())
            StatusLine("IMU seconds incomplete", status.imuIncomplete.toString())
            StatusLine("IMU last second", "${status.lastImuSamples} samples, max gap ${status.lastImuMaxGapMs} ms")
            StatusLine("Late samples dropped", status.lateSamples.toString())
            StatusLine("Unparsed dongle lines", status.badLines.toString())
            StatusLine("Cut-off notifications", status.truncatedNotifications.toString())
            StatusLine("BLE MTU", if (status.bleMtu == 0) "-" else status.bleMtu.toString())
            status.dongleStats?.let { StatusLine("Dongle 5 s", it) }
            (message ?: status.lastError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (!status.running) {
                OutlinedTextField(
                    value = filterText,
                    onValueChange = { text ->
                        filterText = text
                        CanFilter.parse(text)?.let { dongleSettings.filterIds = it }
                    },
                    label = { Text("CAN ids to forward (hex; empty = all)") },
                    isError = filterIds == null,
                    supportingText = {
                        Text(
                            when {
                                filterIds == null -> "hex CAN ids, at most ${CanFilter.MAX_IDS}"
                                filterIds.isEmpty() -> "dongle forwards every id"
                                else -> "dongle forwards ${filterIds.size} ids (firmware 1.6.0+)"
                            },
                        )
                    },
                )
                OutlinedButton(onClick = {
                    if (confirmForget) {
                        dongleSettings.forget()
                        message = "dongle forgotten; the next one seen is used"
                        confirmForget = false
                    } else {
                        confirmForget = true
                    }
                }) { Text(if (confirmForget) "Tap again to forget the dongle" else "Forget dongle") }
            }
        }
    }

    @Composable
    private fun StatusLine(label: String, value: String) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$label:", style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
    }

    private fun startSignIn() {
        message = null
        lifecycleScope.launch {
            runCatching { auth.signInIntent() }
                .onSuccess { signIn.launch(it) }
                .onFailure { message = "can't reach authentik: ${it.message}" }
        }
    }

    private fun startCapture() {
        message = null
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) GatewayService.start(this) else permissions.launch(needed.toTypedArray())
    }
}
