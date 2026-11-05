package com.robo.phonegateway

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.robo.phonegateway.auth.Auth
import com.robo.phonegateway.ble.DongleClient
import com.robo.phonegateway.upload.CatchUpWorker
import com.robo.phonegateway.auth.UploadCredentials
import com.robo.phonegateway.registry.RegistryClient
import com.robo.phonegateway.registry.RegistryException
import com.robo.phonegateway.registry.Vehicle
import com.robo.phonegateway.registry.VehicleStore
import com.robo.phonegateway.ui.App
import com.robo.phonegateway.ui.GatewayTheme
import com.robo.phonegateway.ui.Shell
import kotlinx.coroutines.launch

/** Hosts the Compose shell (ui/Shell.kt) and implements its actions against the service. */
class MainActivity : ComponentActivity(), App {

    override lateinit var auth: Auth
    override lateinit var vehicles: VehicleStore
    override lateinit var registry: RegistryClient
    /** Reads and writes the dongle filter (shared prefs with the service's client). */
    override val dongleSettings by lazy { DongleClient(this, { _, _ -> }, {}) }
    override val deviceId: String get() = GatewayService.deviceId(this)
    override var message by mutableStateOf<String?>(null)

    private val signIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data ?: run {
            lifecycleScope.launch { auth.signOut() } // a clean state for the next attempt
            message = "sign-in cancelled"
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            message = runCatching { auth.completeSignIn(data) }
                .fold(
                    {
                        UploadCredentials.get(this@MainActivity).ensureRegistered(registry)
                        "signed in"
                    },
                    {
                        // start the next attempt from a clean sign-in state, so a half-finished or
                        // stale one can't keep failing; the upload key stays, so uploads go on
                        auth.signOut()
                        "sign-in failed: ${it.message}"
                    },
                )
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) GatewayService.link(this)
        else message = "the gateway needs Bluetooth, location and notifications"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = Auth.get(this)
        vehicles = VehicleStore.get(this)
        registry = RegistryClient(auth)
        // anything recorded but not yet uploaded (also scheduled by Stop; survives reboots)
        CatchUpWorker.schedule(this)
        // a phone signed in before upload keys existed registers one now
        lifecycleScope.launch { UploadCredentials.get(this@MainActivity).ensureRegistered(registry) }
        setContent {
            GatewayTheme {
                Surface(Modifier.fillMaxSize()) { Shell(this) }
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

    override fun startSignIn() {
        lifecycleScope.launch {
            runCatching { auth.signInIntent() }
                .onSuccess { signIn.launch(it) }
                .onFailure { message = "can't reach authentik: ${it.message}" }
        }
    }

    override fun signOut() {
        lifecycleScope.launch {
            UploadCredentials.get(this@MainActivity).revoke(registry) // while the token still works
            auth.signOut()
        }
    }

    override fun startCapture() {
        if (neededPermissions().isEmpty()) GatewayService.startCapture(this)
        else permissions.launch(neededPermissions().toTypedArray())
    }

    override fun stopCapture() = GatewayService.stopCapture(this)

    override fun markStart() = GatewayService.markStart(this)

    override fun selectVehicle(v: Vehicle) {
        vehicles.select(v.id)
        dongleSettings.filterIds = v.canFilter
        GatewayService.applyFilter(this)
    }

    override fun refreshVehicles() {
        lifecycleScope.launch {
            try {
                vehicles.replaceAll(registry.vehicles())
            } catch (e: RegistryException) {
                // the cached list still works offline; say so only if there is none
                if (vehicles.vehicles.value.isEmpty()) message = "vehicles: ${e.message}"
            }
        }
    }

    override fun forgetDongle() {
        GatewayService.forgetDongle(this)
        message = "dongle forgotten; the next one seen is used"
    }

    override fun updateDongle() = GatewayService.updateDongle(this)

    override fun launch(block: suspend () -> Unit) {
        lifecycleScope.launch { block() }
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
