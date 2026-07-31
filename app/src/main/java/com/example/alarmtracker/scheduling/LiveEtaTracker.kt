package com.example.alarmtracker.scheduling

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.data.EventTrigger
import com.example.alarmtracker.util.GeoResolver
import com.example.alarmtracker.util.Prefs
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Keeps an "arrive at a place" alarm's time honest while it is armed.
 *
 * Geofences alone only speak twice — when you cross the outer ring and when you arrive — so an
 * alarm whose fallback time was a guess stayed wrong for the whole journey and the user saw
 * nothing happening. This adds a sparse re-check in between: while a destination alarm is armed
 * and due within [TRACK_WINDOW_MS], a single cached-or-fresh location fix is taken every so often,
 * turned into a remaining-distance ETA, and pushed through [EventAlarmCoordinator.applyEtaSignal].
 *
 * BATTERY CONTRACT: this is still not polling in the "always-on GPS" sense. There is no foreground
 * location service and no repeating location request — each check is one [CurrentLocationRequest]
 * at balanced power that happily accepts a fix another app already paid for, scheduled by a single
 * inexact, Doze-friendly AlarmManager wake-up that re-arms itself. The cadence widens the further
 * away the alarm is (30 min when hours out, 5 min when close) and stops entirely the moment no
 * destination alarm is armed. Turn it off with [Prefs.liveEtaEnabled].
 *
 * SAFETY: a refined ETA may only pull the alarm EARLIER than the guaranteed fallback, never later.
 * Otherwise a user sitting still far from the destination would see the alarm pushed back forever
 * and it would never ring — the fallback time the user set is the promise, and it is kept.
 */
object LiveEtaTracker {

    private const val TAG = "LiveEtaTracker"
    private const val RC_LIVE_ETA = 800

    /** Only track alarms due within this window; anything further out doesn't need refining yet. */
    private const val TRACK_WINDOW_MS = 6 * 60 * 60_000L

    /** First check after arming, so a guessed fallback time is corrected almost immediately. */
    private const val FIRST_CHECK_MS = 2 * 60_000L

    private const val CHECK_FAR_MS = 30 * 60_000L
    private const val CHECK_MID_MS = 15 * 60_000L
    private const val CHECK_NEAR_MS = 5 * 60_000L
    private const val FAR_THRESHOLD_MS = 2 * 60 * 60_000L
    private const val MID_THRESHOLD_MS = 45 * 60_000L

    /** Ignore refinements that move the alarm by less than this — pointless churn otherwise. */
    private const val MIN_SHIFT_MS = 60_000L

    /**
     * Arms (or cancels) the next re-check based on what is currently tracked. Cheap and idempotent
     * — call it whenever event triggers change, on boot, and after every check.
     */
    suspend fun sync(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val delay = nextDelayMs(context)
        if (delay == null) {
            am.cancel(pendingIntent(context))
            return
        }
        val at = System.currentTimeMillis() + delay
        try {
            // Inexact on purpose: this refines an estimate, it does not ring anything, so it has no
            // business burning an exact-alarm slot. allowWhileIdle keeps it alive through Doze.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(context))
        } catch (e: SecurityException) {
            Log.w(TAG, "could not schedule live-ETA check", e)
        }
    }

    /**
     * Takes one location fix and refines every tracked destination alarm from it, then re-arms.
     * Safe to call when nothing qualifies — it just cancels the next wake-up.
     */
    suspend fun runCheck(context: Context) {
        val tracked = trackedTriggers(context)
        if (tracked.isEmpty()) {
            sync(context)
            return
        }
        val location = oneShotLocation(context)
        if (location != null) {
            for (trigger in tracked) refine(context, trigger, location)
        }
        sync(context)
    }

    /** Enabled destination triggers whose alarm is close enough in time to be worth refining. */
    private suspend fun trackedTriggers(context: Context): List<EventTrigger> {
        if (!Prefs.liveEtaEnabled(context)) return emptyList()
        if (!GeofenceManager.hasLocationPermission(context)) return emptyList()
        val now = System.currentTimeMillis()
        return AlarmRepository.get(context).getEnabledEventTriggers().filter { trigger ->
            val eta = trigger.effectiveEtaMillis
            trigger.sourceType == EventTrigger.SOURCE_GEOFENCE &&
                trigger.hasDestination &&
                eta != null && eta > now && eta - now <= TRACK_WINDOW_MS
        }
    }

    /** Delay until the next check — the soonest any tracked alarm wants one. Null = stop tracking. */
    private suspend fun nextDelayMs(context: Context): Long? {
        val now = System.currentTimeMillis()
        return trackedTriggers(context).minOfOrNull { trigger ->
            if (trigger.lastSignalAt == null) {
                FIRST_CHECK_MS
            } else {
                when (val remaining = (trigger.effectiveEtaMillis ?: now) - now) {
                    in FAR_THRESHOLD_MS..Long.MAX_VALUE -> CHECK_FAR_MS
                    in MID_THRESHOLD_MS until FAR_THRESHOLD_MS -> CHECK_MID_MS
                    else -> minOf(CHECK_NEAR_MS, (remaining / 2).coerceAtLeast(60_000L))
                }
            }
        }
    }

    private suspend fun refine(context: Context, trigger: EventTrigger, location: Location) {
        val lat = trigger.destLat ?: return
        val lng = trigger.destLng ?: return
        val distance = GeoResolver.distanceMeters(location.latitude, location.longitude, lat, lng)
        val speedMps = trigger.assumedSpeedKmh.coerceAtLeast(1) * 1000.0 / 3600.0
        val now = System.currentTimeMillis()
        val computed = now + (distance / speedMps * 1000).toLong()
        // Never later than the promise the user set; only ever pull the ring earlier.
        val fallback = trigger.fallbackEtaMillis ?: computed
        val eta = minOf(computed, fallback)
        val current = trigger.effectiveEtaMillis
        val distanceChanged = trigger.lastDistanceM == null ||
            kotlin.math.abs(trigger.lastDistanceM - distance.toInt()) > 100
        if (current != null && kotlin.math.abs(current - eta) < MIN_SHIFT_MS && !distanceChanged) return
        EventAlarmCoordinator.applyEtaSignal(
            context, trigger.alarmId, eta, distance.toInt(), EventTrigger.SIGNAL_LIVE_ETA
        )
        EventAlarmCoordinator.postLiveProgress(context, trigger.alarmId, distance.toInt(), eta, trigger.placeName)
    }

    /**
     * ONE location fix, balanced power, happy to reuse a fix another app took in the last two
     * minutes. Requires background location on API 29+, which [trackedTriggers] already checked.
     */
    @SuppressLint("MissingPermission")
    private suspend fun oneShotLocation(context: Context): Location? {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(2 * 60_000)
            .setDurationMillis(20_000)
            .build()
        return suspendCancellableCoroutine { cont ->
            try {
                client.getCurrentLocation(request, null)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            } catch (e: SecurityException) {
                Log.w(TAG, "getCurrentLocation SecurityException", e)
                cont.resume(null)
            }
        }
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext, RC_LIVE_ETA,
            Intent(context.applicationContext, LiveEtaReceiver::class.java)
                .setAction(LiveEtaReceiver.ACTION_CHECK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
