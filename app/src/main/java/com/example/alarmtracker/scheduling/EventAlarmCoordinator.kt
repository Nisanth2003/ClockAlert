package com.example.alarmtracker.scheduling

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import com.example.alarmtracker.util.Dbg
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.alarmtracker.AlarmTrackerApp
import com.example.alarmtracker.R
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.data.EventTrigger
import com.example.alarmtracker.ring.AlarmReceiver
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The event-alarm engine. Owns the lifecycle of an event alarm as ONE dedicated
 * [AlarmScheduler] registration at the current best estimate, refined by sparse OS-delivered
 * signals and always backed by a guaranteed fallback ETA.
 *
 * BATTERY CONTRACT (do not break): this class NEVER starts a foreground location service, a
 * repeating location request, or any polling loop. Geofences are OS-woken. The only location
 * work is a single one-shot [oneShotLocation] fix, taken exclusively after the outer ring is
 * crossed (near-target, bounded). Everything else is driven by pushed transitions.
 *
 * ── Entry points a second source (Phase 2 notification listener) plugs into ──
 *   [onTriggerConfigured] – arm a trigger (schedule fallback, register geofences if granted).
 *   [applyEtaSignal]      – push a refined ETA from any source; reschedules the single alarm.
 *   [fireNow]             – a hard "arrived / done" event; ring immediately and clean up.
 *   [onTriggerDisabled] / [onAlarmDeleted] – tear everything down cleanly.
 *   [reregisterAll]       – re-arm all triggers after reboot.
 *
 * Phase 2's NotificationListenerService should, per tracked notification:
 *   - on a parsed remaining-time/distance update → call [applyEtaSignal] with the new ETA;
 *   - on a matching "done"/removed notification   → call [fireNow].
 * No geofence or location code is involved for that source — it reuses the same reschedule/fire
 * plumbing here.
 */
object EventAlarmCoordinator {

    private const val TAG = "EventAlarmCoordinator"
    private const val EVENT_NOTIFICATION_ID_BASE = 5000

    /** Arms (or re-arms) the trigger for [alarmId]: guaranteed fallback + geofences if permitted. */
    suspend fun onTriggerConfigured(context: Context, alarmId: Long) {
        val repo = AlarmRepository.get(context)
        val trigger = repo.getEventTrigger(alarmId) ?: return
        if (!trigger.enabled) return

        // 1. Schedule the Doze-proof fallback immediately. This is the reliability pillar: it
        //    rings at the estimate even with zero signals / no location permission / phone off.
        val effective = trigger.effectiveEtaMillis
        if (effective != null && effective > System.currentTimeMillis()) {
            AlarmScheduler.scheduleEventAlarm(context, alarmId, effective)
        }

        // 2. Register geofences to refine + fire early. Gated behind location grants; if denied
        //    we simply keep the fallback above (degrade-to-fallback, still works).
        if (trigger.hasDestination && GeofenceManager.hasLocationPermission(context)) {
            GeofenceManager.register(context, trigger)
        }

        // 3. Arm the sparse in-transit re-check so a guessed fallback time gets corrected as the
        //    user actually travels (no-op when the setting is off or location isn't granted).
        LiveEtaTracker.sync(context)
    }

    /**
     * Applies a refined ETA from any source and reschedules the single event alarm. Safe for
     * Phase 2 to call directly with a parsed notification ETA. [distanceMeters] is optional
     * status metadata (drives the "≈X km" list line); [source] tags provenance.
     */
    suspend fun applyEtaSignal(
        context: Context,
        alarmId: Long,
        newEtaMillis: Long,
        distanceMeters: Int?,
        source: String
    ) {
        val repo = AlarmRepository.get(context)
        val trigger = repo.getEventTrigger(alarmId) ?: return
        if (!trigger.enabled) return

        val now = System.currentTimeMillis()
        val clampedEta = newEtaMillis.coerceAtLeast(now + MIN_LEAD_MS)
        val updated = trigger.copy(
            currentEtaMillis = clampedEta,
            lastSignalAt = now,
            lastDistanceM = distanceMeters ?: trigger.lastDistanceM
        )
        repo.updateEventTrigger(updated)
        AlarmScheduler.scheduleEventAlarm(context, alarmId, updated.effectiveEtaMillis ?: clampedEta)
    }

    /**
     * Hard event — ring the alarm now and tear the source down. Cancels the pending fallback
     * first so it cannot double-ring, then dispatches through the normal ring pipeline.
     */
    suspend fun fireNow(context: Context, alarmId: Long, source: String) {
        Dbg.d(TAG) { "fireNow alarm=$alarmId source=$source" }
        AlarmScheduler.cancelEventAlarm(context, alarmId)
        GeofenceManager.remove(context, alarmId)
        clearProgressNotification(context, alarmId)
        // Dispatch the ring through the same receiver a scheduled fire would hit.
        val fire = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_FOR, System.currentTimeMillis())
            putExtra(AlarmScheduler.EXTRA_ORIGINAL_SCHEDULED_FOR, System.currentTimeMillis())
            putExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0)
        }
        context.sendBroadcast(fire)
        // The row + trigger are finalized by onAlarmFired (invoked from AlarmReceiver).
    }

    /**
     * Outer "getting close" ring crossed → take ONE location fix, recompute the ETA from the
     * remaining distance and the assumed speed, refine the alarm, and post an "≈X km to go"
     * status. This is the only place location is sampled, and it is strictly one-shot.
     */
    suspend fun onOuterRingEntered(context: Context, alarmId: Long) {
        val repo = AlarmRepository.get(context)
        val trigger = repo.getEventTrigger(alarmId) ?: return
        if (!trigger.enabled || !trigger.hasDestination) return

        val location = oneShotLocation(context) ?: return // no fix — keep existing fallback
        val distance = com.example.alarmtracker.util.GeoResolver.distanceMeters(
            location.latitude, location.longitude, trigger.destLat!!, trigger.destLng!!
        )
        val speedMps = (trigger.assumedSpeedKmh.coerceAtLeast(1)) * 1000.0 / 3600.0
        val travelMs = (distance / speedMps * 1000).toLong()
        val eta = System.currentTimeMillis() + travelMs

        applyEtaSignal(context, alarmId, eta, distance.toInt(), EventTrigger.SIGNAL_OUTER_RING)
        postProgressNotification(context, alarmId, distance.toInt(), trigger.placeName)
    }

    /**
     * Finalizes an event alarm that has rung (called from [AlarmReceiver] for EVENT alarms,
     * whether it fired from the fallback or from [fireNow]). Removes geofences, cancels any
     * remaining registration, and disables the source. Idempotent.
     */
    suspend fun onAlarmFired(context: Context, alarmId: Long) {
        AlarmScheduler.cancelEventAlarm(context, alarmId)
        GeofenceManager.remove(context, alarmId)
        clearProgressNotification(context, alarmId)
        val repo = AlarmRepository.get(context)
        repo.getEventTrigger(alarmId)?.let { repo.updateEventTrigger(it.copy(enabled = false)) }
        LiveEtaTracker.sync(context)
    }

    /** Disable the source (alarm toggled off / source revoked) but keep its config row. */
    suspend fun onTriggerDisabled(context: Context, alarmId: Long) {
        AlarmScheduler.cancelEventAlarm(context, alarmId)
        GeofenceManager.remove(context, alarmId)
        clearProgressNotification(context, alarmId)
        val repo = AlarmRepository.get(context)
        repo.getEventTrigger(alarmId)?.let { repo.updateEventTrigger(it.copy(enabled = false)) }
        LiveEtaTracker.sync(context)
    }

    /**
     * Alarm deleted (swipe/UNDO-able) — tear down the live registration but KEEP the trigger row
     * (disabled) so an UNDO can re-arm it exactly. A permanently-deleted event alarm therefore
     * leaves one dormant, non-scheduling trigger row, cleared by wipe-all. See [reregisterAll]/
     * [onTriggerConfigured] for the re-arm on UNDO ([AlarmsViewModel.restore]).
     */
    suspend fun onAlarmDeleted(context: Context, alarmId: Long) = onTriggerDisabled(context, alarmId)

    /** Re-arms every enabled trigger after reboot (geofences + the OS alarm are lost on reboot). */
    suspend fun reregisterAll(context: Context) {
        val repo = AlarmRepository.get(context)
        for (trigger in repo.getEnabledEventTriggers()) {
            onTriggerConfigured(context, trigger.alarmId)
        }
    }

    // ---- One-shot location (the ONLY location sampling; bounded to the near-target window) ----

    @SuppressLint("MissingPermission") // guarded by the FINE check below
    private suspend fun oneShotLocation(context: Context): Location? {
        val fineGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) return null
        val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(60_000) // accept a recent cached fix to save a scan
            .setDurationMillis(30_000)
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

    // ---- "≈X km to go" progress notification ----

    private fun postProgressNotification(
        context: Context,
        alarmId: Long,
        distanceMeters: Int,
        placeName: String?
    ) {
        val text = if (placeName.isNullOrBlank()) {
            context.getString(R.string.event_progress_text, formatKm(context, distanceMeters))
        } else {
            context.getString(R.string.event_progress_text_named, formatKm(context, distanceMeters), placeName)
        }
        val notification = NotificationCompat.Builder(context, AlarmTrackerApp.CHANNEL_EVENT)
            .setSmallIcon(R.drawable.ic_place)
            .setContentTitle(context.getString(R.string.event_progress_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        try {
            NotificationManagerCompat.from(context)
                .notify(EVENT_NOTIFICATION_ID_BASE + alarmId.toInt(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — the status line is best-effort; the alarm is unaffected.
        }
    }

    /**
     * In-transit status from a live re-check: "≈12.4 km to go · rings around 7:35". Same quiet
     * channel as the outer-ring notification and updated in place, so the user can watch the alarm
     * track them instead of wondering whether anything is happening.
     */
    fun postLiveProgress(
        context: Context,
        alarmId: Long,
        distanceMeters: Int,
        etaMillis: Long,
        placeName: String?
    ) {
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = etaMillis }
        val ringsAt = com.example.alarmtracker.util.Format.timeText(
            context,
            calendar.get(java.util.Calendar.HOUR_OF_DAY),
            calendar.get(java.util.Calendar.MINUTE)
        )
        val text = context.getString(
            R.string.event_live_progress_text,
            formatKm(context, distanceMeters),
            ringsAt
        )
        val notification = NotificationCompat.Builder(context, AlarmTrackerApp.CHANNEL_EVENT)
            .setSmallIcon(R.drawable.ic_place)
            .setContentTitle(
                placeName?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.event_progress_title)
            )
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        try {
            NotificationManagerCompat.from(context)
                .notify(EVENT_NOTIFICATION_ID_BASE + alarmId.toInt(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — status is best-effort; the alarm is unaffected.
        }
    }

    private fun clearProgressNotification(context: Context, alarmId: Long) {
        NotificationManagerCompat.from(context).cancel(EVENT_NOTIFICATION_ID_BASE + alarmId.toInt())
    }

    /** Rounds to a friendly "≈X km" / "≈NNN m" string. */
    fun formatKm(context: Context, meters: Int): String =
        if (meters >= 1000) {
            context.getString(R.string.event_distance_km, meters / 1000.0)
        } else {
            context.getString(R.string.event_distance_m, meters)
        }

    /** Minimum lead time we will ever schedule an event alarm at (avoids scheduling in the past). */
    private const val MIN_LEAD_MS = 60_000L
}
