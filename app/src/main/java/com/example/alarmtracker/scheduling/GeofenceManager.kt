package com.example.alarmtracker.scheduling

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.alarmtracker.data.EventTrigger
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * Registers/removes the outer + arrival geofences for an event trigger. The OS wakes the app
 * on a transition (battery-native) — there is NO polling and NO always-on location here. Any
 * location sampling happens once, only after the outer ring is crossed (see EventAlarmCoordinator).
 *
 * Geofences are lost on reboot, so RescheduleReceiver re-registers them via
 * [EventAlarmCoordinator.reregisterAll] on BOOT_COMPLETED.
 */
object GeofenceManager {

    private const val TAG = "GeofenceManager"
    private const val RC_GEOFENCE = 700

    /** Geofence id = "evt_<alarmId>_<ring>". Encodes both so the receiver can route the signal. */
    const val RING_OUTER = "outer"
    const val RING_ARRIVAL = "arrival"

    fun geofenceId(alarmId: Long, ring: String): String = "evt_${alarmId}_$ring"

    fun parseAlarmId(geofenceId: String): Long? =
        geofenceId.removePrefix("evt_").substringBeforeLast('_').toLongOrNull()

    fun parseRing(geofenceId: String): String = geofenceId.substringAfterLast('_')

    /** True when we hold the location grants needed to register background-triggering geofences. */
    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine) return false
        // Background location is required from API 29+ for geofences to fire while the app is
        // in the background. Below 29 fine location covers background use.
        return if (Build.VERSION.SDK_INT >= 29) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun client(context: Context): GeofencingClient =
        LocationServices.getGeofencingClient(context.applicationContext)

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, GeofenceBroadcastReceiver::class.java)
            .setAction(GeofenceBroadcastReceiver.ACTION_TRANSITION)
        // Geofencing requires a MUTABLE PendingIntent so the OS can attach the transition result.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context.applicationContext, RC_GEOFENCE, intent, flags)
    }

    /**
     * Registers the outer + arrival rings for [trigger]. No-op (returns false) if we lack the
     * destination or location permission — the caller then relies purely on the ETA fallback.
     */
    @SuppressLint("MissingPermission") // guarded by hasLocationPermission()
    fun register(context: Context, trigger: EventTrigger): Boolean {
        val lat = trigger.destLat ?: return false
        val lng = trigger.destLng ?: return false
        if (!hasLocationPermission(context)) return false

        val outer = Geofence.Builder()
            .setRequestId(geofenceId(trigger.alarmId, RING_OUTER))
            .setCircularRegion(lat, lng, trigger.outerRadiusM.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()
        val arrival = Geofence.Builder()
            .setRequestId(geofenceId(trigger.alarmId, RING_ARRIVAL))
            .setCircularRegion(lat, lng, trigger.arrivalRadiusM.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val request = GeofencingRequest.Builder()
            // INITIAL_TRIGGER_ENTER: if we're already inside a ring at registration time, fire
            // immediately (e.g. re-registering after boot while already near the destination).
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(listOf(outer, arrival))
            .build()

        return try {
            client(context).addGeofences(request, pendingIntent(context))
                .addOnFailureListener { e -> Log.w(TAG, "addGeofences failed for ${trigger.alarmId}", e) }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "addGeofences SecurityException", e)
            false
        }
    }

    /** Removes both rings for [alarmId] (on disable/delete/arrival-fired). */
    fun remove(context: Context, alarmId: Long) {
        client(context).removeGeofences(
            listOf(geofenceId(alarmId, RING_OUTER), geofenceId(alarmId, RING_ARRIVAL))
        )
    }
}
