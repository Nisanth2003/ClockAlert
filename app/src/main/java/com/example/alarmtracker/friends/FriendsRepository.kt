package com.example.alarmtracker.friends

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.example.alarmtracker.data.AppDatabase
import com.example.alarmtracker.data.Friend
import com.example.alarmtracker.data.FriendWatch
import kotlinx.coroutines.flow.Flow

/**
 * Owns friends, their watches, and the consent rules around both.
 *
 * The consent model, which the rest of the app must not route around:
 *  - pairing is explicit and two-sided — an invite has to be created by one person and accepted by
 *    the other, and it carries the only key that makes the channel readable;
 *  - sharing is never implicit. Being paired does NOT mean being tracked. Each side starts its own
 *    share session, by hand, for a bounded time ([startSharing]), and it expires on its own;
 *  - while sharing there is a persistent notification, and [stopSharing] is always one tap away;
 *  - deleting a friend destroys the key, which retroactively makes the channel useless.
 */
class FriendsRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(appContext).friendDao()

    val transport: FriendsTransport = NtfyTransport(appContext)

    fun observeFriends(): Flow<List<Friend>> = dao.observeAll()

    fun observeWatches(): Flow<List<FriendWatch>> = dao.observeWatches()

    suspend fun friends(): List<Friend> = dao.getAll()

    suspend fun friend(id: Long): Friend? = dao.getById(id)

    suspend fun watchesFor(friendId: Long): List<FriendWatch> = dao.watchesFor(friendId)

    suspend fun watch(id: Long): FriendWatch? = dao.watchById(id)

    // ---- Pairing ----

    /**
     * Creates this side of a pairing and returns the invite to hand over. The invite carries the
     * topic and the key, so it must travel over something the user already trusts — their own
     * message to that friend. It is single-use in practice: anyone else who gets it becomes a
     * paired friend, which is exactly why the UI warns before sharing it.
     */
    suspend fun createInvite(name: String): Pair<Friend, String>? {
        val pairKey = PairCrypto.newPairKey()
        val friend = Friend(
            name = name,
            topic = PairCrypto.newTopic(),
            sharedKeyWrapped = PairCrypto.wrapForStorage(pairKey),
            selfId = PairCrypto.newDeviceId()
        )
        val id = dao.upsert(friend)
        val stored = dao.getById(id) ?: return null
        return stored to inviteLink(friend.topic, pairKey, name)
    }

    /** Accepts an invite produced by [createInvite] on the other phone. */
    suspend fun acceptInvite(link: String, fallbackName: String): Friend? {
        val parsed = parseInvite(link) ?: return null
        dao.getByTopic(parsed.topic)?.let { return it } // already paired — idempotent
        val friend = Friend(
            name = parsed.name?.takeIf { it.isNotBlank() } ?: fallbackName,
            topic = parsed.topic,
            sharedKeyWrapped = PairCrypto.wrapForStorage(parsed.pairKey),
            selfId = PairCrypto.newDeviceId()
        )
        val id = dao.upsert(friend)
        return dao.getById(id)
    }

    suspend fun rename(friend: Friend, name: String) = dao.update(friend.copy(name = name))

    /**
     * Meeting up vs looking after someone. Only defaults change — the consent rules are deliberately
     * identical either way, because a "care" pairing that could share without the other person
     * starting it would be a tracking device rather than a safety feature.
     */
    suspend fun setRelationship(friend: Friend, relationship: String) =
        dao.update(friend.copy(relationship = relationship))

    /** Local only — the number never goes near the relay. Blank clears it. */
    suspend fun setPhoneNumber(friend: Friend, number: String?) =
        dao.update(friend.copy(phoneNumber = number?.takeIf { it.isNotBlank() }))

    /** "Where are you?" — costs one encrypted publish and no location on either side. */
    suspend fun nudge(friend: Friend): Boolean =
        send(friend, FriendMessage.Nudge(friend.selfId, System.currentTimeMillis()))

    /** Full revoke: the key goes with the row, so the channel becomes unreadable to this device. */
    suspend fun removeFriend(friendId: Long) {
        dao.deleteWatchesFor(friendId)
        dao.deleteById(friendId)
    }

    // ---- Watches ----

    suspend fun saveWatch(watch: FriendWatch): Long = dao.upsertWatch(watch)

    suspend fun deleteWatch(id: Long) = dao.deleteWatch(id)

    suspend fun markWatchFired(watch: FriendWatch, at: Long) =
        dao.updateWatch(watch.copy(lastFiredAt = at))

    /**
     * Sends a friend the list of places to tell us about. Their phone registers OS geofences for
     * them; nothing polls on either side. Called whenever the watch list changes and when a share
     * session starts.
     */
    suspend fun pushWatches(friend: Friend) {
        val key = pairKey(friend) ?: return
        val entries = watchesFor(friend.id).filter { it.enabled }.map {
            FriendMessage.Watches.Entry(it.id, it.placeName, it.lat, it.lng, it.radiusM, it.condition)
        }
        send(friend, key, FriendMessage.Watches(friend.selfId, entries))
    }

    // ---- Share sessions (this device sharing ITS location) ----

    /** Opens a bounded share window and tells the friend it's open. Auto-expires at [untilMillis]. */
    suspend fun startSharing(friend: Friend, untilMillis: Long): Friend {
        val updated = friend.copy(shareUntil = untilMillis)
        dao.update(updated)
        pairKey(updated)?.let { key ->
            send(updated, key, FriendMessage.ShareState(updated.selfId, true, untilMillis))
        }
        return updated
    }

    suspend fun stopSharing(friend: Friend): Friend {
        val updated = friend.copy(shareUntil = 0)
        dao.update(updated)
        pairKey(updated)?.let { key ->
            send(updated, key, FriendMessage.ShareState(updated.selfId, false, 0))
        }
        return updated
    }

    /** Friends this device is currently sharing with — drives the geofences and the notification. */
    suspend fun activeShares(now: Long = System.currentTimeMillis()): List<Friend> =
        friends().filter { it.isSharingAt(now) }

    /** Clears share windows that have run out, so an expired session can't linger. */
    suspend fun expireFinishedShares(now: Long = System.currentTimeMillis()): Boolean {
        var changed = false
        friends().filter { it.shareUntil in 1 until now }.forEach {
            dao.update(it.copy(shareUntil = 0))
            changed = true
        }
        return changed
    }

    // ---- Wire ----

    /** Decrypts and stores what a friend told us. Returns the message if it was genuinely theirs. */
    suspend fun receive(friend: Friend, sealedPayload: String): FriendMessage? {
        val key = pairKey(friend) ?: return null
        val plaintext = PairCrypto.open(key, sealedPayload) ?: return null
        val message = FriendMessage.fromJson(plaintext) ?: return null
        // Our own messages come back down the shared topic; ignore them.
        if (message.from == friend.selfId) return null
        dao.update(friend.copy(lastHeardAt = System.currentTimeMillis()))
        return message
    }

    suspend fun send(friend: Friend, message: FriendMessage): Boolean {
        val key = pairKey(friend) ?: return false
        return send(friend, key, message)
    }

    private suspend fun send(friend: Friend, pairKey: String, message: FriendMessage): Boolean =
        transport.publish(friend.topic, PairCrypto.seal(pairKey, message.toJson()))

    suspend fun updateStatus(friend: Friend, status: String?, distanceM: Int?) {
        dao.update(
            friend.copy(
                lastStatus = status,
                lastDistanceM = distanceM,
                lastHeardAt = System.currentTimeMillis()
            )
        )
    }

    fun pairKey(friend: Friend): String? = PairCrypto.unwrapFromStorage(friend.sharedKeyWrapped)

    // ---- Invite encoding ----

    private data class Invite(val topic: String, val pairKey: String, val name: String?)

    private fun inviteLink(topic: String, pairKey: String, name: String): String =
        Uri.Builder()
            .scheme(INVITE_SCHEME)
            .authority(INVITE_HOST)
            .appendQueryParameter("t", topic)
            .appendQueryParameter("k", pairKey)
            .appendQueryParameter("n", name)
            .build()
            .toString()

    private fun parseInvite(raw: String): Invite? = try {
        // Accept either the full link or a bare pasted code, so a mangled paste still works.
        val text = raw.trim()
        val uri = Uri.parse(if (text.startsWith("$INVITE_SCHEME://")) text else "$INVITE_SCHEME://$INVITE_HOST?$text")
        val topic = uri.getQueryParameter("t")
        val key = uri.getQueryParameter("k")
        if (topic.isNullOrBlank() || key.isNullOrBlank()) {
            null
        } else if (runCatching { Base64.decode(key, Base64.NO_WRAP).size }.getOrDefault(0) != 32) {
            null // not a 256-bit key — refuse rather than pair on something malformed
        } else {
            Invite(topic, key, uri.getQueryParameter("n"))
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val INVITE_SCHEME = "alarmtracker"
        const val INVITE_HOST = "pair"

        @Volatile
        private var instance: FriendsRepository? = null

        fun get(context: Context): FriendsRepository =
            instance ?: synchronized(this) {
                instance ?: FriendsRepository(context).also { instance = it }
            }
    }
}
