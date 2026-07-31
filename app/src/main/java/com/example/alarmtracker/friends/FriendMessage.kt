package com.example.alarmtracker.friends

import org.json.JSONArray
import org.json.JSONObject

/**
 * The tiny protocol two paired phones speak over the relay. Every message is JSON, encrypted with
 * the pair key before it leaves the device, and stamped with [from] so each side can drop its own
 * echoes off the shared topic.
 *
 * Deliberately small: the relay is untrusted and the payload is the thing we most want to keep
 * cheap and boring. Nothing here carries an identity, an account, or anything the relay operator
 * could correlate — just a random device id and, when relevant, one coordinate.
 */
sealed class FriendMessage {

    abstract val from: String

    /**
     * The watcher tells the sharer what to watch for. The sharer's phone turns each entry into an
     * OS geofence — this is why nothing has to poll: the watcher's rules run on the friend's device,
     * where the location already is.
     */
    data class Watches(
        override val from: String,
        val watches: List<Entry>
    ) : FriendMessage() {
        data class Entry(
            val id: Long,
            val placeName: String,
            val lat: Double,
            val lng: Double,
            val radiusM: Int,
            val condition: String
        )
    }

    /** A watched geofence fired on the sharer's phone. This is what produces the actual alert. */
    data class Crossing(
        override val from: String,
        val watchId: Long,
        val placeName: String,
        val condition: String,
        val atMillis: Long
    ) : FriendMessage()

    /** Coarse "still on my way" update, only sent while a share session is live. */
    data class Heartbeat(
        override val from: String,
        val distanceM: Int?,
        val placeName: String?,
        val atMillis: Long
    ) : FriendMessage()

    /**
     * "Where are you?" — a poke from the watcher to the sharer.
     *
     * The counterpart to an alert: you've just been told someone reached (or left) somewhere, and the
     * next thing you want is to reach THEM. This is the option that doesn't require them to pick up a
     * call. Carries no location and no identity, so it costs the same nothing as everything else here.
     */
    data class Nudge(
        override val from: String,
        val atMillis: Long
    ) : FriendMessage()

    /** Sharing started or stopped, so the other side's UI can stop claiming it knows anything. */
    data class ShareState(
        override val from: String,
        val sharing: Boolean,
        val untilMillis: Long
    ) : FriendMessage()

    fun toJson(): String {
        val json = JSONObject().put(KEY_FROM, from)
        when (this) {
            is Watches -> {
                json.put(KEY_TYPE, TYPE_WATCHES)
                val array = JSONArray()
                watches.forEach { entry ->
                    array.put(
                        JSONObject()
                            .put("id", entry.id)
                            .put("place", entry.placeName)
                            .put("lat", entry.lat)
                            .put("lng", entry.lng)
                            .put("r", entry.radiusM)
                            .put("cond", entry.condition)
                    )
                }
                json.put("watches", array)
            }
            is Crossing -> json.put(KEY_TYPE, TYPE_CROSSING)
                .put("watchId", watchId)
                .put("place", placeName)
                .put("cond", condition)
                .put("at", atMillis)
            is Heartbeat -> json.put(KEY_TYPE, TYPE_HEARTBEAT)
                .put("dist", distanceM ?: JSONObject.NULL)
                .put("place", placeName ?: JSONObject.NULL)
                .put("at", atMillis)
            is Nudge -> json.put(KEY_TYPE, TYPE_NUDGE).put("at", atMillis)
            is ShareState -> json.put(KEY_TYPE, TYPE_SHARE_STATE)
                .put("sharing", sharing)
                .put("until", untilMillis)
        }
        return json.toString()
    }

    companion object {
        private const val KEY_TYPE = "t"
        private const val KEY_FROM = "f"
        private const val TYPE_WATCHES = "watches"
        private const val TYPE_CROSSING = "crossing"
        private const val TYPE_HEARTBEAT = "hb"
        private const val TYPE_SHARE_STATE = "share"
        private const val TYPE_NUDGE = "nudge"

        /** Parses a decrypted payload. Null for anything malformed — never trust the wire. */
        fun fromJson(raw: String): FriendMessage? = try {
            val json = JSONObject(raw)
            val from = json.optString(KEY_FROM).takeIf { it.isNotBlank() }
            when {
                from == null -> null
                json.optString(KEY_TYPE) == TYPE_WATCHES -> {
                    val array = json.optJSONArray("watches") ?: JSONArray()
                    Watches(
                        from,
                        (0 until array.length()).mapNotNull { index ->
                            array.optJSONObject(index)?.let { entry ->
                                Watches.Entry(
                                    id = entry.optLong("id"),
                                    placeName = entry.optString("place"),
                                    lat = entry.optDouble("lat"),
                                    lng = entry.optDouble("lng"),
                                    radiusM = entry.optInt("r", 500),
                                    condition = entry.optString("cond")
                                )
                            }
                        }
                    )
                }
                json.optString(KEY_TYPE) == TYPE_CROSSING -> Crossing(
                    from,
                    watchId = json.optLong("watchId"),
                    placeName = json.optString("place"),
                    condition = json.optString("cond"),
                    atMillis = json.optLong("at")
                )
                json.optString(KEY_TYPE) == TYPE_HEARTBEAT -> Heartbeat(
                    from,
                    distanceM = if (json.isNull("dist")) null else json.optInt("dist"),
                    placeName = if (json.isNull("place")) null else json.optString("place"),
                    atMillis = json.optLong("at")
                )
                json.optString(KEY_TYPE) == TYPE_NUDGE -> Nudge(from, atMillis = json.optLong("at"))
                json.optString(KEY_TYPE) == TYPE_SHARE_STATE -> ShareState(
                    from,
                    sharing = json.optBoolean("sharing"),
                    untilMillis = json.optLong("until")
                )
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}
