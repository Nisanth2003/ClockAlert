package com.example.alarmtracker.util

import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.location.LocationManagerCompat

/**
 * Whether the phone can produce a location at all, plus the last position we managed to get.
 *
 * The app-permission check and this are NOT the same thing, and conflating them was actively
 * misleading: with the system Location toggle off, the editor happily reported "Granted — arrival
 * will refine the alarm" and then failed to estimate anything, because no app on the device can get a
 * fix in that state. Distance estimates, the map's blue route, the my-location dot and the search
 * bias all depend on this, so it gets its own honest check and a way to fix it.
 */
object LocationState {

    private const val KEY_LAST_LAT = "loc_last_lat"
    private const val KEY_LAST_LNG = "loc_last_lng"
    private const val KEY_LAST_AT = "loc_last_at"

    /** How long a remembered position stays useful as a search bias. */
    private const val BIAS_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

    /** The system-wide Location toggle. False = nothing can get a fix, whatever we're granted. */
    fun servicesEnabled(context: Context): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    /** The Location settings page, so the app can offer to fix it rather than just complain. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)

    /**
     * True when this fix was invented by another app rather than measured.
     *
     * THIS is what actually makes "the app can't find me" happen — a fake-GPS / developer mock-location
     * app. It is worth calling out loudly, because an arrival alarm built on a mock fix will be wrong by
     * however far the mock is from reality, and nothing else in the app can detect that.
     *
     * A VPN, by contrast, does NOT move an Android location fix at all (see [NetworkState.vpnActive]) —
     * it changes which IP address servers see, and Android's location comes from satellites, Wi-Fi and
     * cell towers. Conflating the two would send users to turn off the wrong thing.
     */
    fun isMock(location: Location): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        location.isMock
    } else {
        @Suppress("DEPRECATION")
        location.isFromMockProvider
    }

    /**
     * Remembers roughly where the user was. Deliberately coarse and low-stakes: it exists so a place
     * search can still be biased to the right part of the world when no live fix is available (the
     * whole reason a local search fell back to worldwide results).
     */
    fun remember(context: Context, location: Location) {
        Prefs.get(context).edit()
            .putFloat(KEY_LAST_LAT, location.latitude.toFloat())
            .putFloat(KEY_LAST_LNG, location.longitude.toFloat())
            .putLong(KEY_LAST_AT, System.currentTimeMillis())
            .apply()
    }

    /** The last remembered position, or null if there is none or it is too stale to trust. */
    fun lastKnown(context: Context): Pair<Double, Double>? {
        val prefs = Prefs.get(context)
        val at = prefs.getLong(KEY_LAST_AT, 0L)
        if (at <= 0L || System.currentTimeMillis() - at > BIAS_MAX_AGE_MS) return null
        val lat = prefs.getFloat(KEY_LAST_LAT, Float.NaN)
        val lng = prefs.getFloat(KEY_LAST_LNG, Float.NaN)
        if (lat.isNaN() || lng.isNaN()) return null
        return lat.toDouble() to lng.toDouble()
    }
}
