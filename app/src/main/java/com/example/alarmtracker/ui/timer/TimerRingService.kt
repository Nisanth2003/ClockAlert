package com.example.alarmtracker.ui.timer

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
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
import com.example.alarmtracker.util.MeetingDetector
import com.example.alarmtracker.util.Prefs
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Foreground service that makes a finished timer alert like an alarm: loops the alarm tone,
 * vibrates, and posts a full-screen-intent notification that opens [TimerRingActivity] over the
 * lock screen (with Open-app + Stop). This is far more reliable than a plain notification, which
 * MIUI hides on the lock screen and suppresses while the app is in the foreground.
 */
class TimerRingService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { stopRing() }

    private val meetingWatchRunnable = object : Runnable {
        override fun run() {
            if (player != null || !active.value) return
            if (MeetingDetector.inMeeting(this@TimerRingService)) {
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
            ACTION_OPEN -> handleOpen(intent.getStringExtra(EXTRA_ACTION_PACKAGE))
            ACTION_SNOOZE -> handleSnooze()
            ACTION_STOP -> stopRing()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val timerId = intent.getLongExtra(EXTRA_TIMER_ID, -1L)
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        val actionPackage = intent.getStringExtra(EXTRA_ACTION_PACKAGE)
        val actionLabel = intent.getStringExtra(EXTRA_ACTION_LABEL)
        current = RingInfo(timerId, label, actionPackage, actionLabel)
        active.value = true

        startInForeground(buildNotification(label, actionPackage, actionLabel))
        startSound()
        maybeLaunchFullScreen(label, actionPackage, actionLabel)

        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, RING_TIMEOUT_MS)
    }

    private fun handleOpen(pkg: String?) {
        pkg?.let { packageManager.getLaunchIntentForPackage(it) }
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?.let { runCatching { startActivity(it) } }
        stopRing()
    }

    /** Re-run the SAME timer for a few minutes (no new entry); fall back to a fresh one if it's gone. */
    private fun handleSnooze() {
        current?.let { info ->
            val durationMs = snoozeMinutes(this) * 60_000L
            val restarted = info.id > 0 && TimerController.snoozeRestart(this, info.id, durationMs)
            if (!restarted) {
                TimerController.add(this, info.label, durationMs, info.actionPackage, info.actionLabel)
            }
        }
        stopRing()
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    /** Launch the ring screen directly so it covers the display even when unlocked (needs overlay). */
    private fun maybeLaunchFullScreen(label: String, actionPackage: String?, actionLabel: String?) {
        try {
            startActivity(ringActivityIntent(label, actionPackage, actionLabel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // Background-activity-launch blocked (no overlay grant while unlocked) — the FSI
            // notification still covers the locked case.
        }
    }

    private fun startSound() {
        stopSound()
        // Same rule as the alarm ring: a timer going off during a call must not be broadcast to
        // everyone on it. Send it to a headset if there is one, otherwise vibrate only. See
        // [MeetingDetector]. The timer ring has no override button, so it also un-mutes itself
        // when the call ends.
        val inMeeting = Prefs.meetingAwareEnabled(this) && MeetingDetector.inMeeting(this)
        val preferred = if (inMeeting) MeetingDetector.privateOutputDevice(this) else null
        if (!inMeeting || preferred != null) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                if (uri != null) {
                    val mp = MediaPlayer().apply {
                        setDataSource(this@TimerRingService, uri)
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
                        // A "preferred" device is only a request — if it still came out of the
                        // phone's speaker, stop rather than leak into the call.
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
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 600, 600), 0)
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            }
        }
    }

    /** Re-check until the call ends, then let the timer actually make a sound. */
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

    private fun ringActivityIntent(label: String, actionPackage: String?, actionLabel: String?): Intent =
        Intent(this, TimerRingActivity::class.java)
            .putExtra(EXTRA_LABEL, label)
            .putExtra(EXTRA_ACTION_PACKAGE, actionPackage)
            .putExtra(EXTRA_ACTION_LABEL, actionLabel)

    private fun buildNotification(label: String, actionPackage: String?, actionLabel: String?): Notification {
        val fullScreen = PendingIntent.getActivity(
            this, 500, ringActivityIntent(label, actionPackage, actionLabel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 501, Intent(this, TimerRingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozePi = PendingIntent.getService(
            this, 503, Intent(this, TimerRingService::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, AlarmTrackerApp.CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(getString(R.string.timer_done_title))
            .setContentText(label.ifBlank { getString(R.string.timer_done_text) })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
        if (actionPackage != null && actionLabel != null &&
            packageManager.getLaunchIntentForPackage(actionPackage) != null
        ) {
            val openPi = PendingIntent.getService(
                this, 502,
                Intent(this, TimerRingService::class.java).setAction(ACTION_OPEN)
                    .putExtra(EXTRA_ACTION_PACKAGE, actionPackage),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_alarm, getString(R.string.ring_open_fmt, actionLabel), openPi)
        }
        builder.addAction(R.drawable.ic_snooze, getString(R.string.timer_snooze_fmt, snoozeMinutes(this)), snoozePi)
        builder.addAction(R.drawable.ic_close, getString(R.string.timer_stop), stopPi)
        return builder.build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(meetingWatchRunnable)
        stopSound()
        vibrator?.cancel()
        active.value = false
        current = null
        super.onDestroy()
    }

    data class RingInfo(val id: Long, val label: String, val actionPackage: String?, val actionLabel: String?)

    companion object {
        const val ACTION_START = "com.example.alarmtracker.timer.RING_START"
        const val ACTION_STOP = "com.example.alarmtracker.timer.RING_STOP"
        const val ACTION_OPEN = "com.example.alarmtracker.timer.RING_OPEN"
        const val ACTION_SNOOZE = "com.example.alarmtracker.timer.RING_SNOOZE"

        /** Timer-ring snooze length: the user's default snooze, or 5 min if that's "No snooze". */
        fun snoozeMinutes(context: Context): Int =
            com.example.alarmtracker.util.Prefs.defaultSnoozeMinutes(context).takeIf { it > 0 } ?: 5
        const val EXTRA_TIMER_ID = "extra_timer_id"
        const val EXTRA_LABEL = "extra_label"
        const val EXTRA_ACTION_PACKAGE = "extra_action_package"
        const val EXTRA_ACTION_LABEL = "extra_action_label"

        private const val NOTIFICATION_ID = 43
        private const val RING_TIMEOUT_MS = 5 * 60_000L
        private const val MEETING_POLL_MS = 3_000L

        /** True while a timer is ringing; TimerRingActivity finishes when this goes false. */
        val active = MutableStateFlow(false)
        var current: RingInfo? = null
            private set

        fun stop(context: Context) {
            context.startService(Intent(context, TimerRingService::class.java).setAction(ACTION_STOP))
        }

        fun snooze(context: Context) {
            context.startService(Intent(context, TimerRingService::class.java).setAction(ACTION_SNOOZE))
        }

        fun open(context: Context, pkg: String?) {
            context.startService(
                Intent(context, TimerRingService::class.java).setAction(ACTION_OPEN)
                    .putExtra(EXTRA_ACTION_PACKAGE, pkg)
            )
        }
    }
}
