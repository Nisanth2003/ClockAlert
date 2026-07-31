package com.example.alarmtracker.ui.friends

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.alarmtracker.R
import com.example.alarmtracker.data.Friend
import com.example.alarmtracker.data.FriendWatch
import com.example.alarmtracker.databinding.ActivityFriendsBinding
import com.example.alarmtracker.databinding.DialogFriendWatchBinding
import com.example.alarmtracker.databinding.DialogRelayServerBinding
import com.example.alarmtracker.databinding.ItemFriendBinding
import com.example.alarmtracker.databinding.ItemFriendWatchBinding
import com.example.alarmtracker.friends.FriendGeofences
import com.example.alarmtracker.friends.FriendsRepository
import com.example.alarmtracker.friends.FriendsSessionService
import com.example.alarmtracker.friends.FriendsSync
import com.example.alarmtracker.friends.FriendsSyncScheduler
import com.example.alarmtracker.friends.NtfyTransport
import com.example.alarmtracker.ui.map.MapPickerActivity
import com.example.alarmtracker.util.Format
import com.example.alarmtracker.util.NetworkState
import com.example.alarmtracker.util.Prefs
import com.example.alarmtracker.util.ShareUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar
import kotlinx.coroutines.launch

/**
 * "Is he actually on his way?" — pair with a friend, tell the app which places matter, and get a
 * notification when they really cross one.
 *
 * The screen is built around consent being visible rather than assumed: pairing is an explicit
 * exchange, sharing your own location is a separate deliberate act with an end time attached, and
 * both are undoable from here in one tap.
 */
class FriendsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFriendsBinding
    private val repo by lazy { FriendsRepository.get(this) }

    /** Friend a place is currently being picked for. */
    private var pendingWatchFriendId: Long = 0

    /** In-flight list render, cancelled before starting another so cards can't be added twice. */
    private var renderJob: kotlinx.coroutines.Job? = null

    /** Last rendered list, so dialogs can read a contact's settings without another DB round trip. */
    private var shown: List<Friend> = emptyList()

    private fun friendById(id: Long): Friend? = shown.firstOrNull { it.id == id }

    private val mapPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult
        val lat = data.getDoubleExtra(MapPickerActivity.EXTRA_LAT, Double.NaN)
        val lng = data.getDoubleExtra(MapPickerActivity.EXTRA_LNG, Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return@registerForActivityResult
        val name = data.getStringExtra(MapPickerActivity.EXTRA_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.map_pinned_fmt, lat, lng)
        showWatchDialog(pendingWatchFriendId, name, lat, lng)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFriendsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.friendsToolbar.setNavigationOnClickListener { finish() }
        binding.addFriend.setOnClickListener { showAddFriendChoice() }
        binding.relayConfigure.setOnClickListener { showRelayDialog() }
        binding.relayUpgrade.setOnClickListener { openUpgradePage() }

        observeFriends()
        handleInviteIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInviteIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        updateRelayStatus()
        // Opening this screen is the cheapest moment to check the relay, so don't make the user
        // wait for the 15-minute background pass.
        lifecycleScope.launch {
            FriendsSync.syncOnce(applicationContext)
            FriendsSessionService.syncRunState(applicationContext)
        }
    }

    private fun observeFriends() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.observeFriends().collect { friends -> renderFriends(friends) }
            }
        }
    }

    /**
     * A handful of friends at most, so a plain list of inflated cards beats an adapter here.
     *
     * Loads every watch list BEFORE touching the views, and cancels any render still in flight.
     * Clearing the container up front and filling it from a coroutine let two renders interleave —
     * the second wiped the container while the first was still adding, and every card appeared
     * twice. Heartbeats update the friends table often enough that this happened readily.
     */
    private fun renderFriends(friends: List<Friend>) {
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            val watchesByFriend = friends.associate { it.id to repo.watchesFor(it.id) }
            shown = friends
            binding.friendsEmpty.visibility = if (friends.isEmpty()) View.VISIBLE else View.GONE
            val container = binding.friendsList
            container.removeAllViews()
            for (friend in friends) {
                val row = ItemFriendBinding.inflate(layoutInflater, container, false)
                bindFriend(row, friend, watchesByFriend[friend.id].orEmpty())
                container.addView(row.root)
            }
        }
    }

    private fun bindFriend(row: ItemFriendBinding, friend: Friend, watches: List<FriendWatch>) {
        // The relationship is worth showing on the card: it silently changes whether these alerts can
        // wake you, and an invisible setting that does that is a nasty surprise either way.
        row.friendName.text = if (friend.isCare) {
            getString(R.string.friend_name_care_fmt, friend.name)
        } else {
            friend.name
        }
        row.friendStatus.text = statusFor(friend)

        row.friendWatches.removeAllViews()
        for (watch in watches) {
            val watchRow = ItemFriendWatchBinding.inflate(layoutInflater, row.friendWatches, false)
            watchRow.watchText.text = describeWatch(watch)
            watchRow.watchDelete.setOnClickListener { deleteWatch(friend, watch) }
            watchRow.watchRow.setOnClickListener {
                showWatchDialog(friend.id, watch.placeName, watch.lat, watch.lng, watch)
            }
            row.friendWatches.addView(watchRow.root)
        }

        row.friendAddWatch.setOnClickListener {
            pendingWatchFriendId = friend.id
            mapPicker.launch(MapPickerActivity.intent(this, null, null))
        }

        val sharing = friend.isSharingAt(System.currentTimeMillis())
        row.friendShare.text = when {
            // "Until 3:04 pm" is meaningless for an open-ended share — and formatting Long.MAX_VALUE
            // as a clock time would print something absurd.
            sharing && friend.sharingIndefinitely -> getString(R.string.friend_sharing_no_end)
            sharing -> getString(R.string.friend_sharing_until, clock(friend.shareUntil))
            else -> getString(R.string.friend_share_mine)
        }
        row.friendShare.setOnClickListener {
            if (sharing) confirmStopSharing(friend) else showShareDurationDialog(friend)
        }
        row.friendMenu.setOnClickListener { showFriendMenu(friend, it) }
    }

    private fun statusFor(friend: Friend): String {
        val theirs = FriendsSync.friendIsSharing(this, friend.id)
        val parts = mutableListOf<String>()
        friend.lastStatus?.takeIf { it.isNotBlank() }?.let { parts += it }
        friend.lastDistanceM?.let {
            parts += getString(
                R.string.friend_distance_away,
                com.example.alarmtracker.scheduling.EventAlarmCoordinator.formatKm(this, it)
            )
        }
        if (parts.isEmpty()) {
            parts += getString(
                if (theirs) R.string.friend_status_sharing else R.string.friend_status_idle
            )
        }
        friend.lastHeardAt?.let { parts += getString(R.string.friend_heard_at, clock(it)) }
        return parts.joinToString(" · ")
    }

    private fun describeWatch(watch: FriendWatch): String {
        val base = getString(
            if (watch.condition == FriendWatch.CONDITION_LEAVES) {
                R.string.friend_watch_leaves
            } else if (watch.radiusM <= com.example.alarmtracker.friends.FriendAlerts.NEARBY_RADIUS_M) {
                R.string.friend_watch_within
            } else {
                R.string.friend_watch_approaches
            },
            watch.placeName,
            watch.radiusM
        )
        // Say which watches can wake you, so the loud ones are never a surprise.
        return if (watch.alertAsAlarm) getString(R.string.friend_watch_rings_fmt, base) else base
    }

    // ---- Pairing ----

    private fun showAddFriendChoice() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.friends_add)
            .setItems(
                arrayOf(
                    getString(R.string.friends_add_invite),
                    getString(R.string.friends_add_accept)
                )
            ) { _, which -> if (which == 0) showCreateInvite() else showAcceptInvite() }
            .show()
    }

    private fun showCreateInvite() {
        promptForText(R.string.friends_invite_title, R.string.friends_name_hint) { name ->
            lifecycleScope.launch {
                val created = repo.createInvite(name)
                if (created == null) {
                    toast(R.string.friends_invite_invalid)
                    return@launch
                }
                val (friend, link) = created
                FriendsSyncScheduler.apply(applicationContext)
                MaterialAlertDialogBuilder(this@FriendsActivity)
                    .setTitle(getString(R.string.friends_invite_ready, friend.name))
                    // The invite IS the key. Anyone who gets it can pair, so say so plainly.
                    .setMessage(R.string.friends_invite_warning)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.friends_invite_send) { _, _ ->
                        ShareUtil.shareText(
                            this@FriendsActivity,
                            getString(R.string.friends_invite_send),
                            getString(R.string.friends_invite_message, link)
                        )
                    }
                    .show()
            }
        }
    }

    private fun showAcceptInvite() {
        promptForText(R.string.friends_add_accept, R.string.friends_invite_paste_hint) { pasted ->
            lifecycleScope.launch {
                val friend = repo.acceptInvite(pasted, getString(R.string.friends_default_name))
                if (friend == null) {
                    toast(R.string.friends_invite_invalid)
                } else {
                    FriendsSyncScheduler.apply(applicationContext)
                    toast(R.string.friends_paired)
                }
            }
        }
    }

    private fun handleInviteIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != FriendsRepository.INVITE_SCHEME) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.friends_invite_incoming_title)
            .setMessage(
                getString(
                    R.string.friends_invite_incoming_body,
                    data.getQueryParameter("n") ?: getString(R.string.friends_default_name)
                )
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.friends_invite_accept) { _, _ ->
                lifecycleScope.launch {
                    val friend = repo.acceptInvite(
                        data.toString(), getString(R.string.friends_default_name)
                    )
                    if (friend == null) toast(R.string.friends_invite_invalid)
                    else {
                        FriendsSyncScheduler.apply(applicationContext)
                        toast(R.string.friends_paired)
                    }
                }
            }
            .show()
    }

    private fun showFriendMenu(friend: Friend, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.friends_rename)
            menu.add(0, 2, 1, R.string.friend_menu_relationship)
            menu.add(0, 3, 2, R.string.friend_menu_phone)
            menu.add(0, 4, 3, R.string.friend_menu_ask)
            menu.add(0, 5, 4, R.string.friends_resend_invite)
            menu.add(0, 6, 5, R.string.friends_remove)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> promptForText(R.string.friends_rename, R.string.friends_name_hint) { name ->
                        lifecycleScope.launch { repo.rename(friend, name) }
                    }
                    2 -> showRelationshipDialog(friend)
                    3 -> promptForText(R.string.friend_menu_phone, R.string.friend_phone_hint) { number ->
                        lifecycleScope.launch {
                            repo.setPhoneNumber(friend, number)
                            renderFriends(repo.friends())
                        }
                    }
                    4 -> askWhereTheyAre(friend)
                    5 -> resendInvite(friend)
                    6 -> confirmRemove(friend)
                }
                true
            }
            show()
        }
    }

    /**
     * Meeting up or looking after someone. Both are the same mechanism — a person, a place, a boundary
     * — so this only moves the defaults: a care contact's watches ring like an alarm and can share for
     * longer, because "did she get to school" is worth being woken for and a coffee meet-up isn't.
     */
    private fun showRelationshipDialog(friend: Friend) {
        val labels = arrayOf(
            getString(R.string.friend_relationship_meet),
            getString(R.string.friend_relationship_care)
        )
        val current = if (friend.isCare) 1 else 0
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.friend_menu_relationship)
            .setMessage(R.string.friend_relationship_body)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                val relationship = if (which == 1) Friend.REL_CARE else Friend.REL_MEET
                lifecycleScope.launch {
                    repo.setRelationship(friend, relationship)
                    renderFriends(repo.friends())
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Poke them over the same encrypted channel — no call, no location needed on either side. */
    private fun askWhereTheyAre(friend: Friend) {
        if (NetworkState.blocked(this)) {
            NetworkState.promptToConnect(this, R.string.net_feature_friends)
            return
        }
        lifecycleScope.launch {
            val sent = repo.nudge(friend)
            toast(if (sent) R.string.friend_nudge_sent else R.string.friend_nudge_failed)
        }
    }

    private fun resendInvite(friend: Friend) {
        val key = repo.pairKey(friend)
        if (key == null) {
            toast(R.string.friends_key_unavailable)
            return
        }
        val link = Uri.Builder()
            .scheme(FriendsRepository.INVITE_SCHEME)
            .authority(FriendsRepository.INVITE_HOST)
            .appendQueryParameter("t", friend.topic)
            .appendQueryParameter("k", key)
            .appendQueryParameter("n", friend.name)
            .build()
            .toString()
        ShareUtil.shareText(
            this,
            getString(R.string.friends_invite_send),
            getString(R.string.friends_invite_message, link)
        )
    }

    private fun confirmRemove(friend: Friend) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.friends_remove_title, friend.name))
            .setMessage(R.string.friends_remove_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.friends_remove) { _, _ ->
                lifecycleScope.launch {
                    FriendGeofences.forget(applicationContext, friend.id)
                    repo.removeFriend(friend.id)
                    FriendsSyncScheduler.apply(applicationContext)
                    FriendsSessionService.syncRunState(applicationContext)
                }
            }
            .show()
    }

    // ---- Sharing my own location ----

    private fun showShareDurationDialog(friend: Friend) {
        // A share session with no connection publishes nothing — the friend would simply never hear
        // from us, with a "sharing" notification on screen claiming otherwise.
        if (NetworkState.blocked(this)) {
            NetworkState.promptToConnect(this, R.string.net_feature_friends)
            return
        }
        // A care contact gets longer windows and an open-ended option: a school run that expires
        // halfway through is worse than useless, and being nagged to re-arm it daily is how people
        // stop using a safety feature. It is still explicit, still visible, still one tap to stop.
        val choices = if (friend.isCare) CARE_SHARE_MINUTES else SHARE_MINUTES
        val labels = choices.map {
            if (it == 0) {
                getString(R.string.friend_share_until_stopped)
            } else {
                resources.getQuantityString(R.plurals.friend_share_minutes, it, it)
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.friend_share_title, friend.name))
            .setMessage(if (friend.isCare) R.string.friend_share_body_care else R.string.friend_share_body)
            .setItems(labels) { _, which ->
                val minutes = choices[which]
                val until = if (minutes == 0) {
                    Friend.SHARE_INDEFINITE
                } else {
                    System.currentTimeMillis() + minutes * 60_000L
                }
                lifecycleScope.launch {
                    val updated = repo.startSharing(friend, until)
                    // Send them our watch list too, so their phone can arm its geofences at once.
                    repo.pushWatches(updated)
                    FriendsSessionService.syncRunState(applicationContext)
                    if (!FriendGeofences.hasLocationPermission(this@FriendsActivity)) {
                        toast(R.string.friend_share_needs_location)
                    }
                }
            }
            .show()
    }

    private fun confirmStopSharing(friend: Friend) {
        lifecycleScope.launch {
            repo.stopSharing(friend)
            FriendGeofences.disarmAll(applicationContext, friend.id)
            FriendsSessionService.syncRunState(applicationContext)
            toast(R.string.friend_share_stopped)
        }
    }

    // ---- Watches ----

    private fun showWatchDialog(
        friendId: Long,
        placeName: String,
        lat: Double,
        lng: Double,
        existing: FriendWatch? = null
    ) {
        val view = DialogFriendWatchBinding.inflate(layoutInflater)
        view.watchPlace.text = placeName
        val conditions = resources.getStringArray(R.array.friend_watch_conditions)
        view.watchCondition.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, conditions)
        )
        var condition = existing?.condition ?: FriendWatch.CONDITION_ENTERS
        view.watchCondition.setText(
            conditions[if (condition == FriendWatch.CONDITION_LEAVES) 1 else 0], false
        )
        view.watchCondition.setOnItemClickListener { _, _, position, _ ->
            condition = if (position == 1) FriendWatch.CONDITION_LEAVES else FriendWatch.CONDITION_ENTERS
        }
        val radius = existing?.radiusM ?: 500
        view.watchRadius.value = radius.coerceIn(100, 3000).toFloat()
        view.watchRadiusValue.text = getString(R.string.friend_watch_radius_fmt, radius)
        view.watchRadius.addOnChangeListener { _, value, _ ->
            view.watchRadiusValue.text = getString(R.string.friend_watch_radius_fmt, value.toInt())
        }
        // A new watch on a care contact defaults to ringing — that is the whole point of marking
        // someone as a care contact. An existing watch keeps whatever it was saved with.
        val careContact = friendById(friendId)?.isCare == true
        view.watchAlarm.isChecked = existing?.alertAsAlarm ?: careContact

        MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.friend_add_watch else R.string.friend_edit_watch)
            .setView(view.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                lifecycleScope.launch {
                    val watch = (existing ?: FriendWatch(friendId = friendId, placeName = placeName, lat = lat, lng = lng))
                        .copy(
                            placeName = placeName,
                            lat = lat,
                            lng = lng,
                            radiusM = view.watchRadius.value.toInt(),
                            condition = condition,
                            alertAsAlarm = view.watchAlarm.isChecked
                        )
                    repo.saveWatch(watch)
                    repo.friend(friendId)?.let { repo.pushWatches(it) }
                    renderFriends(repo.friends())
                }
            }
            .show()
    }

    private fun deleteWatch(friend: Friend, watch: FriendWatch) {
        lifecycleScope.launch {
            repo.deleteWatch(watch.id)
            repo.friend(friend.id)?.let { repo.pushWatches(it) }
            renderFriends(repo.friends())
        }
    }

    // ---- Relay server / upgrade ----

    private fun updateRelayStatus() {
        val base = Prefs.relayBaseUrl(this)
        val isDefault = base.trimEnd('/') == NtfyTransport.DEFAULT_BASE_URL
        val hasToken = !Prefs.relayToken(this).isNullOrBlank()
        val relay = when {
            isDefault && !hasToken -> getString(R.string.friends_relay_free)
            isDefault -> getString(R.string.friends_relay_free_account)
            else -> getString(R.string.friends_relay_custom, base)
        }
        // Everything on this screen travels through the relay, so with no connection the cards are
        // showing history, not live positions. Say it here rather than letting stale distances look live.
        binding.relayStatus.text = if (NetworkState.blocked(this)) {
            "$relay\n${getString(R.string.friends_offline_note)}"
        } else {
            relay
        }
    }

    private fun showRelayDialog() {
        val view = DialogRelayServerBinding.inflate(layoutInflater)
        view.relayUrl.setText(Prefs.relayBaseUrl(this))
        view.relayTokenInput.setText(Prefs.relayToken(this).orEmpty())
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.friends_relay_configure)
            .setView(view.root)
            .setNeutralButton(R.string.friends_relay_reset) { _, _ ->
                Prefs.setRelay(this, NtfyTransport.DEFAULT_BASE_URL, null)
                updateRelayStatus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val url = view.relayUrl.text?.toString()?.trim().orEmpty()
                    .ifBlank { NtfyTransport.DEFAULT_BASE_URL }
                Prefs.setRelay(this, url, view.relayTokenInput.text?.toString()?.trim())
                updateRelayStatus()
            }
            .show()
    }

    /**
     * The upgrade is ntfy's, not ours — the app is free and takes no cut. Anyone who wants higher
     * limits or a guarantee behind the relay buys it from ntfy directly, or self-hosts for nothing.
     */
    private fun openUpgradePage() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.friends_relay_upgrade)
            .setMessage(R.string.friends_relay_upgrade_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.friends_relay_open_pricing) { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(NTFY_PRICING_URL)))
                }
            }
            .show()
    }

    // ---- Small helpers ----

    private fun promptForText(titleRes: Int, hintRes: Int, onDone: (String) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_single_input, null)
        val layout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.input_layout)
        val input = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_field)
        layout.setHint(hintRes)
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) onDone(text)
            }
            .show()
    }

    private fun clock(millis: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = millis }
        return Format.timeText(
            this,
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    private companion object {
        val SHARE_MINUTES = intArrayOf(30, 60, 120, 240)

        /** Care contacts: longer, plus 0 = "until I turn it off" (see [Friend.SHARE_INDEFINITE]). */
        val CARE_SHARE_MINUTES = intArrayOf(60, 240, 480, 0)
        const val NTFY_PRICING_URL = "https://ntfy.sh/#pricing"
    }
}
