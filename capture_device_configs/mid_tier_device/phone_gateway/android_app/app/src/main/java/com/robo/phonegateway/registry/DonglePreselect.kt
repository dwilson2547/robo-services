package com.robo.phonegateway.registry

/**
 * When to preselect the car a dongle was last used in: once per dongle per app
 * process, the first time it is seen connected. Never again after that, so a car the
 * user picks by hand sticks. Re-offering whenever the Drive screen showed a connected
 * dongle switched a just-picked Blazer back to the dongle's Impala before recording
 * (docs/issues/2026_11_04_phone_gateway_signed_out_and_sign_in_crash.md). Brief BLE
 * reconnects do not re-offer either.
 */
class DonglePreselect {
    private val offered = mutableSetOf<String>()

    /** True the first time [address] is seen connected; false afterwards. */
    @Synchronized
    fun shouldOffer(address: String?): Boolean = address != null && offered.add(address)
}
