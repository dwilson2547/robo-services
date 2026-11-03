package com.robo.phonegateway

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.robo.phonegateway.auth.Auth
import com.robo.phonegateway.ble.CanFilter
import com.robo.phonegateway.ble.DongleClient
import com.robo.phonegateway.telemetry.LapAnchor
import kotlinx.coroutines.delay
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
        if (granted.values.all { it }) GatewayService.link(this)
        else message = "the gateway needs Bluetooth, location and notifications"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = Auth.get(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // connect to the dongle while the screen is up, so its state shows before capture
        val missing = neededPermissions()
        if (missing.isEmpty()) GatewayService.link(this) else permissions.launch(missing.toTypedArray())
    }

    override fun onStop() {
        // stops the service too unless capturing, which frees the dongle for other apps
        if (!isChangingConfigurations) GatewayService.unlink(this)
        super.onStop()
    }

    @Composable
    private fun Screen() {
        val signedIn by auth.signedIn.collectAsStateWithLifecycle()
        val status by GatewayStatus.snapshot.collectAsStateWithLifecycle()
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(500)
            }
        }
        val fixFresh = status.lastFixMs > 0 && now - status.lastFixMs <= LapAnchor.MAX_FIX_AGE_MS

        Column(
            Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Phone Gateway", style = MaterialTheme.typography.headlineSmall)
            Text("Device ${GatewayService.deviceId(this@MainActivity)}", style = MaterialTheme.typography.bodySmall)

            DongleIndicator(status, now)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (signedIn) {
                    OutlinedButton(onClick = { lifecycleScope.launch { auth.signOut() } }) { Text("Sign out") }
                } else {
                    Button(onClick = { startSignIn() }) { Text("Sign in") }
                }
                if (status.running) {
                    Button(onClick = { GatewayService.stopCapture(this@MainActivity) }) { Text("Stop capture") }
                } else {
                    Button(onClick = { startCapture() }, enabled = signedIn) { Text("Start capture") }
                }
            }

            if (status.running) {
                Button(
                    onClick = { GatewayService.markStart(this@MainActivity) },
                    enabled = fixFresh,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Mark start/finish here") }
                Text(
                    when {
                        !fixFresh -> "waiting for a GPS fix"
                        status.lastAnchor != null -> "Last anchor: ${status.lastAnchor}"
                        else -> "Lap timing starts once a start/finish is marked"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()
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
            StatusLine("BLE MTU", if (status.bleMtu == 0) "-" else status.bleMtu.toString())
            status.dongleStats?.let { StatusLine("Dongle 5 s", it) }
            (message ?: status.lastError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            HorizontalDivider()
            DongleSettings(status)
        }
    }

    @Composable
    private fun DongleIndicator(status: GatewayStatus.Snapshot, now: Long) {
        val dataAgeMs = if (status.lastDongleDataMs == 0L) Long.MAX_VALUE else now - status.lastDongleDataMs
        val (color, label) = when {
            status.dongleConnected && dataAgeMs <= 3_000 -> Color(0xFF2E7D32) to "Dongle connected"
            status.dongleConnected -> Color(0xFFF9A825) to "Dongle connected, no data for ${dataAgeMs / 1000} s"
            else -> Color(0xFFC62828) to "Dongle not connected (${status.dongle})"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(14.dp).background(color, CircleShape))
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun DongleSettings(status: GatewayStatus.Snapshot) {
        val settings = remember { DongleClient(this@MainActivity, { _, _ -> }, {}) }
        var filterText by remember { mutableStateOf(CanFilter.format(settings.filterIds)) }
        var saved by remember { mutableStateOf(settings.filterIds) }
        var confirmForget by remember { mutableStateOf(false) }
        val parsed = CanFilter.parse(filterText)

        OutlinedTextField(
            value = filterText,
            onValueChange = { filterText = it },
            label = { Text("CAN ids to forward (hex; empty = all)") },
            isError = parsed == null,
            supportingText = {
                Text(
                    when {
                        parsed == null -> "hex CAN ids, at most ${CanFilter.MAX_IDS}"
                        parsed.isEmpty() -> "dongle forwards every id"
                        else -> "dongle forwards ${parsed.size} ids (firmware 1.6.0+)"
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val ids = parsed ?: return@OutlinedButton
                    settings.filterIds = ids
                    saved = ids
                    GatewayService.applyFilter(this@MainActivity) // takes effect now if connected
                    message = if (ids.isEmpty()) "filter cleared" else "filter applied: ${ids.size} ids"
                },
                enabled = parsed != null && parsed != saved,
            ) { Text("Apply filter") }
            OutlinedButton(
                onClick = {
                    if (confirmForget) {
                        GatewayService.forgetDongle(this@MainActivity)
                        message = "dongle forgotten; the next one seen is used"
                        confirmForget = false
                    } else {
                        confirmForget = true
                    }
                },
                enabled = !status.running,
            ) { Text(if (confirmForget) "Tap again to forget" else "Forget dongle") }
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
        if (neededPermissions().isEmpty()) GatewayService.startCapture(this)
        else permissions.launch(neededPermissions().toTypedArray())
    }

    private fun neededPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
}
