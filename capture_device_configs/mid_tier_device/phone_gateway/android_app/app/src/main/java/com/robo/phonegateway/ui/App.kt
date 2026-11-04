package com.robo.phonegateway.ui

import com.robo.phonegateway.auth.Auth
import com.robo.phonegateway.ble.DongleClient
import com.robo.phonegateway.registry.RegistryClient
import com.robo.phonegateway.registry.Vehicle
import com.robo.phonegateway.registry.VehicleStore

/** What the screens need from the activity: its stores, and the actions that touch the service. */
interface App {
    val auth: Auth
    val vehicles: VehicleStore
    val registry: RegistryClient
    val dongleSettings: DongleClient
    val deviceId: String
    /** A one-line message for the snackbar; the shell shows and clears it. */
    var message: String?

    fun startSignIn()
    fun signOut()
    fun startCapture()
    fun stopCapture()
    fun markStart()
    /** Pick a vehicle: its CAN filter becomes the dongle's, at once if connected. */
    fun selectVehicle(v: Vehicle)
    fun refreshVehicles()
    fun forgetDongle()
    fun launch(block: suspend () -> Unit)
}
