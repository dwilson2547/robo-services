package com.robo.phonegateway

import android.annotation.SuppressLint
import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Where the phone is, for "tracks near me" (the app holds location permission to capture). */
object Where {
    @SuppressLint("MissingPermission")
    suspend fun now(context: Context, timeoutMs: Long = 15_000): Pair<Double, Double>? = withTimeoutOrNull(timeoutMs) {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val cancel = CancellationTokenSource()
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel.cancel() }
            runCatching {
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancel.token)
                    .addOnSuccessListener { loc -> cont.resume(loc?.let { it.latitude to it.longitude }) }
                    .addOnFailureListener { cont.resume(null) }
            }.onFailure { cont.resume(null) }
        }
    }
}
