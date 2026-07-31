package com.example.alarmtracker.friends

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import org.json.JSONArray
import org.json.JSONObject

/**
 * The battery story of this whole feature, in one class.
 *
 * A friend's watch rules are not evaluated by the watcher — they're shipped to the SHARER's phone
 * and registered there as OS geofences. The operating system already tracks position for its own
 * reasons and wakes the app only on a crossing, so an armed watch costs effectively nothing until
 * it fires. No location stream, no timer, no service running for hours.
 *
 * The rules only stay armed while a share session is open, so outside that window this class holds
 * nothing at all.
 */
object FriendGeofences {

    private const val TAG = "FriendGeofences"
    private const val RC_FRIEND_GEOFENCE = 900
    private const val PREFS = "friend_geofences"
    private const val KEY_ARMED = "armed_watches"

    /** Geofence id encodes the friend and the watch: "fr_<friendId>_<watchId>". */
    fun geofenceId(friendId: Long, watchId: Long): String = "fr_${friendId}_$watchId"

    fun parseFriendId(id: String): Long? =
        id.removePrefix("fr_").substringBefore('_').toLongOrNull()

    fun parseWatchId(id: String): Long? = id.substringAfterLast('_').toLongOrNull()

    fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine) return false
        return if (Build.VERSION.SDK_INT >= 29) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Arms the watches [friendId] asked us to honour. Stored locally too, so the crossing handler
     * can name the place in the message it sends back and so a reboot can re-arm them.
     */
    @SuppressLint("MissingPermission") // guarded by hasLocationPermission()
    fun arm(context: Context, friendId: Long, watches: List<FriendMessage.Watches.Entry>) {
        // Tear down against the PREVIOUS list, not the new one — otherwise a watch the friend just
        // deleted keeps its geofence registered forever and goes on waking us for nothing.
        val previousIds = remembered(context, friendId).map { it.id }
        remember(context, friendId, watches)
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "no background location — cannot arm friend watches")
            return
        }
        disarm(context, friendId, previousIds)
        prefs(context).edit().putBoolean(armedKey(friendId), false).apply()
        if (watches.isEmpty()) return
        val fences = watches.map { entry ->
            Geofence.Builder()
                .setRequestId(geofenceId(friendId, entry.id))
                .setCircularRegion(entry.lat, entry.lng, entry.radiusM.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
                )
                .build()
        }
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(fences)
            .build()
        try {
            LocationServices.getGeofencingClient(context.applicationContext)
                .addGeofences(request, pendingIntent(context))
                .addOnSuccessListener {
                    prefs(context).edit().putBoolean(armedKey(friendId), true).apply()
                }
                .addOnFailureListener { e -> Log.w(TAG, "addGeofences failed for friend $friendId", e) }
        } catch (e: SecurityException) {
            Log.w(TAG, "addGeofences SecurityException", e)
        }
    }

    /** True once the OS has actually accepted this friend's geofences. */
    fun isArmed(context: Context, friendId: Long): Boolean =
        prefs(context).getBoolean(armedKey(friendId), false)

    /** Removes the given watches for a friend (or all of them when [watchIds] is null). */
    fun disarm(context: Context, friendId: Long, watchIds: List<Long>? = null) {
        val ids = watchIds ?: remembered(context, friendId).map { it.id }
        if (ids.isEmpty()) return
        LocationServices.getGeofencingClient(context.applicationContext)
            .removeGeofences(ids.map { geofenceId(friendId, it) })
    }

    /** Drops everything for a friend — sharing ended, or they were removed. */
    fun disarmAll(context: Context, friendId: Long) {
        disarm(context, friendId)
        // Keep the remembered watch list — the next share session re-arms from it. Only the
        // "these are live with the OS" flag is cleared.
        prefs(context).edit().putBoolean(armedKey(friendId), false).apply()
    }

    /** Forgets a friend entirely (they were removed). */
    fun forget(context: Context, friendId: Long) {
        disarm(context, friendId)
        prefs(context).edit().remove(key(friendId)).remove(armedKey(friendId)).apply()
    }

    /** The watches currently armed for [friendId], as last sent by them. */
    fun remembered(context: Context, friendId: Long): List<FriendMessage.Watches.Entry> = try {
        val raw = prefs(context).getString(key(friendId), null) ?: return emptyList()
        val array = JSONArray(raw)
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let {
                FriendMessage.Watches.Entry(
                    id = it.optLong("id"),
                    placeName = it.optString("place"),
                    lat = it.optDouble("lat"),
                    lng = it.optDouble("lng"),
                    radiusM = it.optInt("r", 500),
                    condition = it.optString("cond")
                )
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun remember(
        context: Context,
        friendId: Long,
        watches: List<FriendMessage.Watches.Entry>
    ) {
        val array = JSONArray()
        watches.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("place", it.placeName)
                    .put("lat", it.lat)
                    .put("lng", it.lng)
                    .put("r", it.radiusM)
                    .put("cond", it.condition)
            )
        }
        prefs(context).edit().putString(key(friendId), array.toString()).apply()
    }

    /** Friend ids we currently hold armed watches for. */
    fun armedFriendIds(context: Context): List<Long> =
        prefs(context).all.keys.mapNotNull { it.removePrefix("$KEY_ARMED:").toLongOrNull() }

    private fun key(friendId: Long) = "$KEY_ARMED:$friendId"

    private fun armedKey(friendId: Long) = "live:$friendId"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context.applicationContext, FriendGeofenceReceiver::class.java)
            .setAction(FriendGeofenceReceiver.ACTION_TRANSITION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context.applicationContext, RC_FRIEND_GEOFENCE, intent, flags)
    }
}
