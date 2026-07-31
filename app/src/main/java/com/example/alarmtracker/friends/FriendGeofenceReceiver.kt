package com.example.alarmtracker.friends

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.alarmtracker.data.FriendWatch
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Runs on the SHARER's phone. The OS wakes it when the user crosses one of the places a friend
 * asked to be told about, and it publishes a single encrypted "I entered/left X" message. That one
 * message is the entire cost of an armed watch — nothing polls, nothing streams.
 *
 * Crossings are only honoured while a share session is open. If sharing lapsed but a geofence is
 * still registered, the event is dropped and the fence torn down.
 */
class FriendGeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRANSITION) return
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.w(TAG, "friend geofence error: ${event.errorCode}")
            return
        }
        val condition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> FriendWatch.CONDITION_ENTERS
            Geofence.GEOFENCE_TRANSITION_EXIT -> FriendWatch.CONDITION_LEAVES
            else -> return
        }
        val triggering = event.triggeringGeofences ?: return

        val appContext = context.applicationContext
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val repo = FriendsRepository.get(appContext)
                val now = System.currentTimeMillis()
                for (fence in triggering) {
                    val friendId = FriendGeofences.parseFriendId(fence.requestId) ?: continue
                    val watchId = FriendGeofences.parseWatchId(fence.requestId) ?: continue
                    val friend = repo.friend(friendId)
                    if (friend == null || !friend.isSharingAt(now)) {
                        // Not sharing any more — make sure we stop being woken for this.
                        FriendGeofences.disarmAll(appContext, friendId)
                        continue
                    }
                    val entry = FriendGeofences.remembered(appContext, friendId)
                        .firstOrNull { it.id == watchId } ?: continue
                    // Only report the direction this friend actually asked about.
                    if (entry.condition != condition) continue
                    repo.send(
                        friend,
                        FriendMessage.Crossing(
                            from = friend.selfId,
                            watchId = watchId,
                            placeName = entry.placeName,
                            condition = condition,
                            atMillis = now
                        )
                    )
                }
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val ACTION_TRANSITION = "com.example.alarmtracker.ACTION_FRIEND_GEOFENCE"
        private const val TAG = "FriendGeofenceRx"
    }
}
