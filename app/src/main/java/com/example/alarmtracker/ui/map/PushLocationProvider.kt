package com.example.alarmtracker.ui.map

import android.location.Location
import org.osmdroid.views.overlay.mylocation.IMyLocationConsumer
import org.osmdroid.views.overlay.mylocation.IMyLocationProvider

/**
 * Lets osmdroid's `MyLocationNewOverlay` draw the fixes WE already have.
 *
 * The overlay ships with a provider that opens its own `LocationManager` subscription. Using it
 * would mean two independent location streams on one screen — a second battery cost, and a dot that
 * disagrees with the distance/route readouts. This shim owns nothing: the activity pushes each fused
 * fix in, so there is exactly one source of truth for "where the user is".
 */
class PushLocationProvider : IMyLocationProvider {

    private var consumer: IMyLocationConsumer? = null
    private var last: Location? = null

    override fun startLocationProvider(myLocationConsumer: IMyLocationConsumer?): Boolean {
        consumer = myLocationConsumer
        // Hand over whatever we already know so the dot appears immediately on re-attach.
        last?.let { myLocationConsumer?.onLocationChanged(it, this) }
        return true
    }

    /** No-op by design: this provider never subscribed to anything, so there is nothing to stop. */
    override fun stopLocationProvider() = Unit

    override fun getLastKnownLocation(): Location? = last

    override fun destroy() {
        consumer = null
        last = null
    }

    fun push(location: Location) {
        last = location
        consumer?.onLocationChanged(location, this)
    }
}
