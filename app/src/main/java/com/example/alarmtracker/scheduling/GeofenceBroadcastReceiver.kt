package com.example.alarmtracker.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * OS-woken entry point for geofence transitions. Does no polling; it only reacts to the sparse
 * ENTER events the system delivers, then hands off to [EventAlarmCoordinator]:
 *  - arrival ring ENTER  -> fire the alarm now (hard event)
 *  - outer ring ENTER    -> one-shot ETA refine
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRANSITION) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "Geofence event error: ${event.errorCode}")
            return
        }
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        val triggering = event.triggeringGeofences ?: return

        val appContext = context.applicationContext
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                for (fence in triggering) {
                    val alarmId = GeofenceManager.parseAlarmId(fence.requestId) ?: continue
                    when (GeofenceManager.parseRing(fence.requestId)) {
                        GeofenceManager.RING_ARRIVAL ->
                            EventAlarmCoordinator.fireNow(
                                appContext, alarmId,
                                com.example.alarmtracker.data.EventTrigger.SIGNAL_ARRIVAL
                            )
                        GeofenceManager.RING_OUTER ->
                            EventAlarmCoordinator.onOuterRingEntered(appContext, alarmId)
                    }
                }
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_TRANSITION = "com.example.alarmtracker.ACTION_GEOFENCE_TRANSITION"
        private const val TAG = "GeofenceReceiver"
    }
}
