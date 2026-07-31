package com.example.alarmtracker.friends

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.alarmtracker.data.Friend
import com.example.alarmtracker.data.FriendWatch
import com.example.alarmtracker.util.GeoResolver
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock

/**
 * One pass of "talk to the relay and act on what's there". Called from the periodic worker, from
 * the session service while something is live, and immediately whenever the Friends screen opens.
 *
 * The work per pass is deliberately small: one HTTP GET per paired friend, plus at most one cached
 * location fix if this device is mid-share. Everything genuinely time-critical — the actual
 * "they've arrived" moment — is delivered by an OS geofence on the other phone rather than by
 * anything polled here (see [FriendGeofences]).
 */
object FriendsSync {

    private const val PREFS = "friends_sync"
    private const val KEY_LAST_POLL = "last_poll"
    private const val KEY_LAST_HEARTBEAT = "last_hb"
    private const val KEY_FRIEND_SHARING_UNTIL = "friend_sharing_until"

    /** Don't re-send a heartbeat more often than this while sharing. */
    private const val HEARTBEAT_INTERVAL_MS = 5 * 60_000L

    /** Ignore a repeat crossing for the same watch within this window (geofences can flap). */
    private const val CROSSING_DEBOUNCE_MS = 3 * 60_000L

    /**
     * Serialises passes. The periodic worker, the session service loop and the Friends screen can
     * all ask for a sync at once; overlapping runs would advance each topic's "since" marker past
     * messages a sibling run was still fetching, silently losing them.
     */
    private val syncMutex = kotlinx.coroutines.sync.Mutex()

    suspend fun syncOnce(context: Context) = syncMutex.withLock {
        val repo = FriendsRepository.get(context)
        val friends = repo.friends()
        if (friends.isEmpty()) return@withLock

        repo.expireFinishedShares()
        val now = System.currentTimeMillis()

        for (friend in friends) {
            drainTopic(context, repo, friend)
        }
        // Re-read: the drain above may have changed share state or watch lists.
        for (friend in repo.friends()) {
            maintainSharing(context, repo, friend, now)
        }
    }

    /** Pull everything new off a friend's topic and act on it. */
    private suspend fun drainTopic(context: Context, repo: FriendsRepository, friend: Friend) {
        val prefs = prefs(context)
        val since = prefs.getLong("$KEY_LAST_POLL:${friend.id}", 0L)
        // Stamp the marker from BEFORE the request: anything published while it was in flight must
        // still be picked up next time rather than falling into the gap.
        val startedAt = System.currentTimeMillis()
        val payloads = repo.transport.poll(friend.topic, since)
        prefs.edit().putLong("$KEY_LAST_POLL:${friend.id}", startedAt).apply()
        for (payload in payloads) {
            // receive() rejects anything that fails the AES-GCM tag, so a stranger who found the
            // topic can neither read it nor inject a convincing "your friend is here".
            when (val message = repo.receive(friend, payload)) {
                is FriendMessage.Watches -> onWatchesReceived(context, friend, message)
                is FriendMessage.Crossing -> onCrossingReceived(context, repo, friend, message)
                is FriendMessage.Heartbeat -> onHeartbeatReceived(repo, friend, message)
                is FriendMessage.Nudge -> FriendAlerts.notifyNudge(context, friend)
                is FriendMessage.ShareState -> onShareStateReceived(context, friend, message)
                null -> Unit
            }
        }
    }

    /** They told us what to watch — arm the OS geofences, but only while we're actually sharing. */
    private fun onWatchesReceived(context: Context, friend: Friend, message: FriendMessage.Watches) {
        if (!friend.isSharingAt(System.currentTimeMillis())) {
            FriendGeofences.disarmAll(context, friend.id)
            return
        }
        FriendGeofences.arm(context, friend.id, message.watches)
    }

    /** The alert the whole feature exists for. */
    private suspend fun onCrossingReceived(
        context: Context,
        repo: FriendsRepository,
        friend: Friend,
        message: FriendMessage.Crossing
    ) {
        val watch = repo.watch(message.watchId) ?: return
        if (watch.friendId != friend.id || !watch.enabled) return
        val now = System.currentTimeMillis()
        if (now - watch.lastFiredAt < CROSSING_DEBOUNCE_MS) return
        repo.markWatchFired(watch, now)
        val status = statusLine(context, watch, message)
        repo.updateStatus(friend, status, null)
        // Two urgencies, one mechanism. A watch the user marked as alarm-grade takes over the screen
        // and makes a noise; everything else stays a notification.
        if (watch.alertAsAlarm) {
            FriendRingService.start(context, friend, FriendAlerts.crossingText(context, friend, watch, message))
        } else {
            FriendAlerts.notifyCrossing(context, friend, watch, message)
        }
    }

    private suspend fun onHeartbeatReceived(
        repo: FriendsRepository,
        friend: Friend,
        message: FriendMessage.Heartbeat
    ) {
        repo.updateStatus(friend, message.placeName, message.distanceM)
    }

    private fun onShareStateReceived(
        context: Context,
        friend: Friend,
        message: FriendMessage.ShareState
    ) {
        prefs(context).edit()
            .putLong(
                "$KEY_FRIEND_SHARING_UNTIL:${friend.id}",
                if (message.sharing) message.untilMillis else 0L
            )
            .apply()
    }

    /** True when [friend] last told us they are sharing with us and that window hasn't closed. */
    fun friendIsSharing(context: Context, friendId: Long, now: Long = System.currentTimeMillis()): Boolean =
        prefs(context).getLong("$KEY_FRIEND_SHARING_UNTIL:$friendId", 0L) > now

    /** Anything live in either direction — the condition for running the session service. */
    suspend fun hasActiveSession(context: Context): Boolean {
        val now = System.currentTimeMillis()
        return FriendsRepository.get(context).friends().any {
            it.isSharingAt(now) || friendIsSharing(context, it.id, now)
        }
    }

    /**
     * Our side of an open share window: keep their watches armed and send an occasional coarse
     * heartbeat so their screen can say "≈3 km away" instead of nothing. One cached fix, at most
     * every [HEARTBEAT_INTERVAL_MS], and only while sharing.
     */
    private suspend fun maintainSharing(
        context: Context,
        repo: FriendsRepository,
        friend: Friend,
        now: Long
    ) {
        if (!friend.isSharingAt(now)) {
            FriendGeofences.disarmAll(context, friend.id)
            return
        }
        // Re-arm on the first pass of a session. Their watch list usually arrived during an EARLIER
        // session and was torn down when that one ended, so without this the geofences would stay
        // down until the friend happened to edit a watch — and nothing would ever fire.
        val armed = FriendGeofences.remembered(context, friend.id)
        if (armed.isNotEmpty() && !FriendGeofences.isArmed(context, friend.id)) {
            FriendGeofences.arm(context, friend.id, armed)
        }

        val prefs = prefs(context)
        val lastBeat = prefs.getLong("$KEY_LAST_HEARTBEAT:${friend.id}", 0L)
        if (now - lastBeat < HEARTBEAT_INTERVAL_MS) return
        prefs.edit().putLong("$KEY_LAST_HEARTBEAT:${friend.id}", now).apply()

        val location = if (armed.isEmpty()) null else cachedLocation(context)
        val nearest = if (location == null) {
            null
        } else {
            armed.minByOrNull {
                GeoResolver.distanceMeters(location.latitude, location.longitude, it.lat, it.lng)
            }
        }
        val distance = if (location != null && nearest != null) {
            GeoResolver.distanceMeters(
                location.latitude, location.longitude, nearest.lat, nearest.lng
            ).toInt()
        } else {
            null
        }
        repo.send(
            friend,
            FriendMessage.Heartbeat(friend.selfId, distance, nearest?.placeName, now)
        )
    }

    /** A cheap fix — happily reuses one another app already paid for in the last few minutes. */
    @SuppressLint("MissingPermission")
    private suspend fun cachedLocation(context: Context): android.location.Location? {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val client = LocationServices.getFusedLocationProviderClient(context.applicationContext)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(5 * 60_000)
            .setDurationMillis(20_000)
            .build()
        return suspendCancellableCoroutine { cont ->
            try {
                client.getCurrentLocation(request, null)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            } catch (_: SecurityException) {
                cont.resume(null)
            }
        }
    }

    fun statusLine(context: Context, watch: FriendWatch, message: FriendMessage.Crossing): String {
        val res = if (message.condition == FriendWatch.CONDITION_LEAVES) {
            com.example.alarmtracker.R.string.friend_status_left
        } else {
            com.example.alarmtracker.R.string.friend_status_arrived
        }
        return context.getString(res, watch.placeName)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
