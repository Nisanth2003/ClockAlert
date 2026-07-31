package com.example.alarmtracker.friends

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.alarmtracker.AlarmTrackerApp
import com.example.alarmtracker.R
import com.example.alarmtracker.data.Friend
import com.example.alarmtracker.data.FriendWatch
import com.example.alarmtracker.util.MeetingDetector
import com.example.alarmtracker.util.Prefs
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Makes a person-arrival alert ring like an alarm instead of sitting in the notification shade.
 *
 * Why this exists: "he's actually on his way" and "she got to school" are the same mechanism, but not
 * the same urgency. A notification is right when you are already holding the phone. It is useless when
 * the phone is face-down in another room, on silent, or you are asleep — which is exactly when you most
 * want to know that a child reached (or left) somewhere. So a watch can be marked
 * [FriendWatch.alertAsAlarm] and comes through here: alarm tone on a loop, vibration, and a
 * full-screen-intent notification that opens [FriendRingActivity] over the lock screen.
 *
 * Deliberately mirrors [com.example.alarmtracker.ui.timer.TimerRingService] rather than reusing the
 * alarm's own service: an alarm ring is driven by an `Alarm` row with missions, snooze and
 * gentle-wake state, none of which mean anything here. What it DOES copy is the part that is hard-won
 * — the CHANNEL_RING full-screen path that MIUI respects, and the meeting-aware muting, because an
 * alert firing mid-call must not be broadcast to everyone on the call either.
 *
 * The two buttons are what the alert is FOR: call them, or ask them where they are without needing
 * them to pick up.
 */
class FriendRingService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { stopRing() }

    private val meetingWatchRunnable = object : Runnable {
        override fun run() {
            if (player != null || !active.value) return
            if (MeetingDetector.inMeeting(this@FriendRingService)) {
                handler.postDelayed(this, MEETING_POLL_MS)
            } else {
                startSound()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_CALL -> handleCall()
            ACTION_NUDGE -> handleNudge()
            ACTION_STOP -> stopRing()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        // An alert already ringing wins: a second crossing arriving in the same breath (two watches on
        // one place, a flapping geofence) must not restart the sound or replace who you're about to call.
        if (active.value) return
        current = RingInfo(
            friendId = intent.getLongExtra(EXTRA_FRIEND_ID, -1L),
            friendName = intent.getStringExtra(EXTRA_FRIEND_NAME).orEmpty(),
            text = intent.getStringExtra(EXTRA_TEXT).orEmpty(),
            phoneNumber = intent.getStringExtra(EXTRA_PHONE)
        )
        active.value = true

        startInForeground(buildNotification())
        startSound()
        maybeLaunchFullScreen()

        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, RING_TIMEOUT_MS)
    }

    /**
     * ACTION_DIAL, not ACTION_CALL: it hands the number to the dialler with the call not yet placed,
     * which needs no permission and cannot dial by accident from a pocket.
     */
    private fun handleCall() {
        val number = current?.phoneNumber
        if (!number.isNullOrBlank()) {
            runCatching {
                startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        stopRing()
    }

    /** Ask them where they are, over the same encrypted channel the alert came down. */
    private fun handleNudge() {
        val friendId = current?.friendId ?: -1L
        if (friendId > 0) {
            val app = applicationContext as AlarmTrackerApp
            app.applicationScope.launch {
                val repo = FriendsRepository.get(app)
                repo.friend(friendId)?.let { repo.nudge(it) }
            }
        }
        stopRing()
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    /** Covers the display even when unlocked (needs the overlay grant); FSI covers the locked case. */
    private fun maybeLaunchFullScreen() {
        try {
            startActivity(ringActivityIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // Background-activity-launch blocked — the full-screen-intent notification still fires.
        }
    }

    private fun startSound() {
        stopSound()
        val inMeeting = Prefs.meetingAwareEnabled(this) && MeetingDetector.inMeeting(this)
        val preferred = if (inMeeting) MeetingDetector.privateOutputDevice(this) else null
        if (!inMeeting || preferred != null) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                if (uri != null) {
                    val mp = MediaPlayer().apply {
                        setDataSource(this@FriendRingService, uri)
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        isLooping = true
                        prepare()
                    }
                    if (preferred != null && !mp.setPreferredDevice(preferred)) {
                        mp.release()
                    } else {
                        mp.start()
                        // A preferred device is only a request. If it still came out of the phone's
                        // speaker, stop rather than leak into the call.
                        if (preferred != null && MeetingDetector.isBuiltIn(mp.routedDevice)) {
                            runCatching { mp.stop() }
                            mp.release()
                        } else {
                            player = mp
                        }
                    }
                }
            } catch (_: Exception) {
                player = null
            }
        }
        if (player == null && inMeeting) watchForMeetingEnd()
        startVibration()
    }

    private fun startVibration() {
        if (vibrator != null) return
        vibrator = getSystemService(Vibrator::class.java)?.also { v ->
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 500, 700), 0)
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        }
    }

    private fun watchForMeetingEnd() {
        handler.removeCallbacks(meetingWatchRunnable)
        handler.postDelayed(meetingWatchRunnable, MEETING_POLL_MS)
    }

    private fun stopSound() {
        player?.run { runCatching { stop() }; release() }
        player = null
    }

    private fun stopRing() {
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(meetingWatchRunnable)
        stopSound()
        vibrator?.cancel()
        vibrator = null
        active.value = false
        current = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ringActivityIntent(): Intent = Intent(this, FriendRingActivity::class.java)

    private fun buildNotification(): Notification {
        val info = current
        val fullScreen = PendingIntent.getActivity(
            this, RC_FULL_SCREEN, ringActivityIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, AlarmTrackerApp.CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_friends)
            .setContentTitle(info?.friendName.orEmpty())
            .setContentText(info?.text.orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(info?.text.orEmpty()))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setSilent(true) // the service makes the noise, not the notification
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
        if (!info?.phoneNumber.isNullOrBlank()) {
            builder.addAction(R.drawable.ic_friends, getString(R.string.friend_ring_call), action(ACTION_CALL, RC_CALL))
        }
        builder.addAction(
            R.drawable.ic_notifications, getString(R.string.friend_ring_nudge), action(ACTION_NUDGE, RC_NUDGE)
        )
        builder.addAction(R.drawable.ic_close, getString(R.string.friend_ring_dismiss), action(ACTION_STOP, RC_STOP))
        return builder.build()
    }

    private fun action(what: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode, Intent(this, FriendRingService::class.java).setAction(what),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    override fun onDestroy() {
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(meetingWatchRunnable)
        stopSound()
        vibrator?.cancel()
        active.value = false
        current = null
        super.onDestroy()
    }

    data class RingInfo(
        val friendId: Long,
        val friendName: String,
        val text: String,
        val phoneNumber: String?
    )

    companion object {
        const val ACTION_START = "com.example.alarmtracker.friends.RING_START"
        const val ACTION_STOP = "com.example.alarmtracker.friends.RING_STOP"
        const val ACTION_CALL = "com.example.alarmtracker.friends.RING_CALL"
        const val ACTION_NUDGE = "com.example.alarmtracker.friends.RING_NUDGE"

        private const val EXTRA_FRIEND_ID = "extra_friend_id"
        private const val EXTRA_FRIEND_NAME = "extra_friend_name"
        private const val EXTRA_TEXT = "extra_text"
        private const val EXTRA_PHONE = "extra_phone"

        private const val NOTIFICATION_ID = 44

        /** Request codes 700+ — the app's other ranges are documented at 100..600. */
        private const val RC_FULL_SCREEN = 700
        private const val RC_CALL = 701
        private const val RC_NUDGE = 702
        private const val RC_STOP = 703

        /** Shorter than an alarm's: this is information, and it must not ring out for ten minutes. */
        private const val RING_TIMEOUT_MS = 3 * 60_000L
        private const val MEETING_POLL_MS = 3_000L

        /** True while an alert is ringing; [FriendRingActivity] finishes when this goes false. */
        val active = MutableStateFlow(false)

        var current: RingInfo? = null
            private set

        fun start(context: Context, friend: Friend, text: String) {
            val intent = Intent(context, FriendRingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_FRIEND_ID, friend.id)
                .putExtra(EXTRA_FRIEND_NAME, friend.name)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_PHONE, friend.phoneNumber)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) = send(context, ACTION_STOP)

        fun call(context: Context) = send(context, ACTION_CALL)

        fun nudge(context: Context) = send(context, ACTION_NUDGE)

        private fun send(context: Context, what: String) {
            runCatching {
                context.startService(Intent(context, FriendRingService::class.java).setAction(what))
            }
        }
    }
}
