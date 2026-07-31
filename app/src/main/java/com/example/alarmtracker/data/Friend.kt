package com.example.alarmtracker.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A paired person you can watch, and who can watch you. Pairing is mutual and symmetric: both
 * phones end up holding the same [topic] (where messages are relayed) and the same [sharedKeyWrapped]
 * (what makes them readable). Deleting the row is a complete revoke — without the key the relay
 * traffic is meaningless, and without the topic there is nowhere to listen.
 *
 * NOTHING here implies live tracking. Location only flows during an explicit share session that each
 * side starts for themselves (see [shareUntil] / [FriendsRepository]).
 *
 * [relationship] is the one thing that separates the two use cases, which are otherwise the same
 * mechanism: a person, a place, and a boundary being crossed. Meeting up is short and mutual — is he
 * actually on his way. Looking after someone is longer and one-directional — did she get to school —
 * and it has to be able to WAKE you rather than sit politely in the notification shade. So the
 * relationship changes the defaults (alarm-grade alerts, longer share windows) and nothing else.
 */
@Entity(
    tableName = "friends",
    indices = [Index(value = ["topic"], unique = true)]
)
data class Friend(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** What you call them. Local only — never sent anywhere. */
    val name: String,
    /** Random relay channel both phones publish to and subscribe from. Unguessable by design. */
    val topic: String,
    /**
     * The pair's shared AES-256 secret, wrapped by an Android Keystore key so it is useless if the
     * database is lifted off the device. Provides both encryption and authenticity (AES-GCM), so a
     * stranger who learned the topic can neither read positions nor forge them.
     */
    val sharedKeyWrapped: String,
    /**
     * Stable random id for THIS device on this pairing, stamped on every outgoing message so each
     * side can ignore its own echoes on the shared topic.
     */
    val selfId: String,
    /** Epoch millis this device stops sharing its own location; 0 = not sharing. */
    val shareUntil: Long = 0,
    /** Their last reported distance, in metres, to whichever place we last heard about. Display only. */
    val lastDistanceM: Int? = null,
    /** When we last heard anything at all from them (epoch millis). */
    val lastHeardAt: Long? = null,
    /** Their last reported status line, e.g. "Arrived at Home". Display only. */
    val lastStatus: String? = null,
    /** [REL_MEET] or [REL_CARE] — see the class comment. Only changes defaults, never mechanism. */
    val relationship: String = REL_MEET,
    /**
     * Optional number for the "Call them" button on an alert. Local only, never sent over the relay.
     * Dialled through ACTION_DIAL, so the app never needs permission to place a call.
     */
    val phoneNumber: String? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** True while this device is actively sharing its location with this friend. */
    fun isSharingAt(now: Long): Boolean = shareUntil > now

    /** Sharing with no end time — offered for [REL_CARE] only; still revocable at any moment. */
    val sharingIndefinitely: Boolean get() = shareUntil == SHARE_INDEFINITE

    val isCare: Boolean get() = relationship == REL_CARE

    companion object {
        /** Meeting up: short, mutual, a notification is enough. */
        const val REL_MEET = "meet"

        /**
         * Looking after someone: longer share windows, and alerts default to ringing like an alarm.
         * Deliberately NOT a different data path — the consent rules are identical, because a
         * "care" pairing that could share silently would be a tracking device.
         */
        const val REL_CARE = "care"

        /**
         * "Until I turn it off". A real timestamp rather than a flag so every existing comparison
         * (`shareUntil > now`, the expiry sweep, the wire format) keeps working untouched.
         */
        const val SHARE_INDEFINITE = Long.MAX_VALUE
    }
}

/**
 * "Tell me when <friend> enters / leaves this place." One OS geofence pair on the friend's phone
 * backs each of these — there is no polling anywhere in the chain.
 *
 * The three things the user asked for are all this one shape:
 *  - "alert me before he reaches the destination" → [CONDITION_ENTERS] with a wide radius (1–2 km)
 *  - "alert me when he leaves"                    → [CONDITION_LEAVES]
 *  - "alert me when he's within 100 m"            → [CONDITION_ENTERS] with radius 100
 */
@Entity(
    tableName = "friend_watches",
    indices = [Index(value = ["friendId"])]
)
data class FriendWatch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val friendId: Long,
    /** Readable name of the watched place, e.g. "Cafe on 5th". */
    val placeName: String,
    val lat: Double,
    val lng: Double,
    /** Trigger radius in metres. 100 = "practically here"; 2000 = "give me a heads-up". */
    val radiusM: Int = 500,
    /** [CONDITION_ENTERS] or [CONDITION_LEAVES]. */
    val condition: String = CONDITION_ENTERS,
    val enabled: Boolean = true,
    /**
     * Ring like an alarm instead of posting a notification — full screen, over the lock screen, and it
     * ignores silent mode. This is what makes the "did my daughter get to school" case work at all: a
     * notification is fine when you're already looking at the phone and useless when you're not.
     * Defaults on for a [Friend.REL_CARE] pairing, off for meeting up.
     */
    val alertAsAlarm: Boolean = false,
    /** Last time this watch fired, so a jittery geofence can't spam you. */
    val lastFiredAt: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val CONDITION_ENTERS = "ENTERS"
        const val CONDITION_LEAVES = "LEAVES"
    }
}
