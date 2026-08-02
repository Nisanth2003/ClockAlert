package com.example.alarmtracker.ring

import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.alarmtracker.AlarmTrackerApp
import com.example.alarmtracker.R
import com.example.alarmtracker.data.Alarm
import com.example.alarmtracker.data.AlarmEvent
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.data.EventTrigger
import com.example.alarmtracker.data.NotificationMatchRule
import com.example.alarmtracker.scheduling.AlarmScheduler
import com.example.alarmtracker.util.MeetingDetector
import com.example.alarmtracker.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service (type systemExempted) that owns the actual ring:
 * MediaPlayer with USAGE_ALARM audio, vibration, optional volume ramp and the
 * full-screen-intent notification. Snooze / dismiss end up here and write the
 * corresponding AlarmEvents.
 */
class AlarmRingService : Service() {

    data class RingState(
        val alarmId: Long,
        val label: String,
        val missionType: String,
        val missionDifficulty: Int,
        val missionBarcode: String?,
        val missionPhotoHash: String?,
        val snoozeCount: Int,
        /** false when the alarm's snooze length is 0 ("No snooze") — hides every snooze affordance. */
        val snoozeEnabled: Boolean,
        /** Optional "open this app" ring action (e.g. a limit-reset alarm → open Claude). */
        val actionPackage: String? = null,
        val actionLabel: String? = null,
        /** True while the ring is muted because the user is on a call (vibration still runs). */
        val soundSuppressed: Boolean = false,
        /**
         * True when the ring should be audible and simply isn't: no playable tone, or the alarm stream
         * is at zero. Deliberately separate from [soundSuppressed] — "muted for your call" and "this
         * phone cannot play your alarm" need different words and different fixes.
         */
        val soundUnavailable: Boolean = false
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var rampAnimator: ValueAnimator? = null
    private var alarm: Alarm? = null

    // Effective player volume = rampVolume * volumeScale. rampVolume follows the optional fade-in;
    // volumeScale is ducked live by the steps mission (quiet while moving, loud while stationary).
    private var rampVolume = 1f
    @Volatile private var volumeScale = 1f

    private var alarmId = -1L
    private var scheduledFor = 0L
    private var originalScheduledFor = 0L
    private var snoozeCount = 0
    private var ringStartedAt = 0L
    private var ringStartedElapsed = 0L

    // Call-aware ringing: muted because a meeting is live, and the user's explicit override.
    private var soundSuppressed = false
    private var userForcedSound = false

    private val timeoutRunnable = Runnable { onTimeout() }

    /** While muted for a call, keep checking so the ring un-mutes itself when the call ends. */
    private val meetingWatchRunnable = object : Runnable {
        override fun run() {
            if (!soundSuppressed) return
            if (MeetingDetector.inMeeting(this@AlarmRingService)) {
                handler.postDelayed(this, MEETING_POLL_MS)
            } else {
                startSound(alarm, preferredDevice = null)
                setSoundSuppressed(false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_SNOOZE -> handleSnooze()
            ACTION_DISMISS -> handleDismiss(intent.getLongExtra(EXTRA_MISSION_DURATION_MS, -1L))
            ACTION_SET_VOLUME_SCALE -> {
                volumeScale = intent.getFloatExtra(EXTRA_VOLUME_SCALE, 1f).coerceIn(0f, 1f)
                applyPlayerVolume()
            }
            ACTION_PLAY_SOUND -> handlePlaySound()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        // Already ringing? Ignore the new start entirely — for a different alarm the first one wins,
        // and for the SAME alarm a re-entrant start would restart the sound and reset snoozeCount
        // and the ring-start timestamp. Two notification rules matching the same trigger in quick
        // succession used to do exactly that. A snooze re-fire is unaffected: the service has
        // already stopped by then, so `ringing` is null.
        if (ringing.value != null) return
        alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
        scheduledFor = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_FOR, 0L)
        originalScheduledFor =
            intent.getLongExtra(AlarmScheduler.EXTRA_ORIGINAL_SCHEDULED_FOR, scheduledFor)
        snoozeCount = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0)
        ringStartedAt = System.currentTimeMillis()
        ringStartedElapsed = SystemClock.elapsedRealtime()
        volumeScale = 1f
        rampVolume = 1f
        soundSuppressed = false
        userForcedSound = false

        // Foreground immediately with a provisional notification.
        startInForeground(buildNotification(getString(R.string.ring_alarm_label_default), null))

        scope.launch {
            val repo = AlarmRepository.get(applicationContext)
            val loaded = repo.getAlarm(alarmId)
            alarm = loaded
            val label = loaded?.label?.ifBlank { null }
            val action = resolveRingAction(loaded, repo.getEventTrigger(alarmId))
            ringing.value = RingState(
                alarmId = alarmId,
                label = label ?: getString(R.string.ring_alarm_label_default),
                missionType = loaded?.missionType ?: Alarm.MISSION_NONE,
                missionDifficulty = loaded?.missionDifficulty ?: 1,
                missionBarcode = loaded?.missionBarcode,
                missionPhotoHash = loaded?.missionPhotoHash,
                snoozeCount = snoozeCount,
                snoozeEnabled = (loaded?.snoozeMinutes ?: Prefs.defaultSnoozeMinutes(this@AlarmRingService)) > 0,
                actionPackage = action?.first,
                actionLabel = action?.second
            )
            // Re-post with real label and mission-aware actions.
            startInForeground(buildNotification(label ?: getString(R.string.ring_alarm_label_default), loaded))
            startRinging(loaded)
            maybeLaunchFullScreen()
        }

        handler.removeCallbacks(timeoutRunnable)
        handler.postDelayed(timeoutRunnable, RING_TIMEOUT_MS)
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    /**
     * Launch the ring screen directly so it covers the display even when the phone is UNLOCKED —
     * Android only auto-launches a full-screen intent when locked. Works when "Display over other
     * apps" is granted (background-activity-launch exemption); if it's blocked we swallow it and
     * the full-screen-intent notification still covers the locked case. Off if the user disables
     * the full-screen-alarm setting.
     */
    private fun maybeLaunchFullScreen() {
        if (!Prefs.fullScreenAlarmEnabled(this)) return
        try {
            startActivity(
                Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            // Background-activity-launch not permitted (no overlay grant while unlocked) — the
            // full-screen-intent notification remains the fallback.
        }
    }

    /**
     * The "Open <app>" action the ring screen offers, as (packageName, displayLabel), or null.
     *
     * Two sources, in priority order:
     *  1. an app the user explicitly attached to THIS alarm — works for any alarm, including a
     *     plain 7am one or an arrival alarm;
     *  2. failing that, the app a limit-reset / notification alarm was already tracking — the
     *     honest "we can't auto-start your session, so we drop you into it" flow.
     *
     * Either way the app must still be installed; an uninstalled one just yields no button.
     */
    private fun resolveRingAction(alarm: Alarm?, trigger: EventTrigger?): Pair<String, String>? {
        alarm?.actionPackage?.takeIf { packageManager.getLaunchIntentForPackage(it) != null }
            ?.let { pkg ->
                return pkg to (alarm.actionLabel?.takeIf { it.isNotBlank() } ?: appLabel(pkg))
            }
        if (trigger == null) return null
        if (trigger.sourceType != EventTrigger.SOURCE_COOLDOWN &&
            trigger.sourceType != EventTrigger.SOURCE_NOTIFICATION
        ) return null
        val rule = NotificationMatchRule.fromJson(trigger.configJson) ?: return null
        val pkg = rule.packages.firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }
            ?: return null
        val label = trigger.placeName?.takeIf { it.isNotBlank() }
            ?: rule.label?.takeIf { it.isNotBlank() }
            ?: appLabel(pkg)
        return pkg to label
    }

    /** The installed app's display name, falling back to the package id. */
    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull() ?: pkg

    private fun startRinging(alarm: Alarm?) {
        stopSound()
        val wantsSound = alarm?.soundEnabled != false
        val soundRunning = if (wantsSound) startSoundRespectingCalls(alarm) else false
        // An alarm that makes no sound AND no vibration is the worst bug this app can have — it looks
        // like it worked. So vibration is forced whenever anything went wrong with the audio, not only
        // when a call muted it: no usable tone, or the alarm stream turned down to zero (which is
        // separate from media volume, so "my volume was full" does not rule it out).
        val silentStream = wantsSound && alarmStreamMuted()
        if (alarm?.vibrate != false || soundSuppressed || (wantsSound && !soundRunning) || silentStream) {
            startVibration()
        }
        if (silentStream) {
            Log.w(TAG, "alarm stream volume is 0 — ring is inaudible, vibrating instead")
            setSoundUnavailable(true)
        }
    }

    /** The ALARM stream specifically — it is turned down independently of media and ring volume. */
    private fun alarmStreamMuted(): Boolean =
        runCatching {
            getSystemService(android.media.AudioManager::class.java)
                ?.getStreamVolume(android.media.AudioManager.STREAM_ALARM) == 0
        }.getOrDefault(false)

    /**
     * Plays the ring, unless doing so would broadcast it into a call the user is in.
     *
     * The reported problem: an alarm went off during a Teams meeting, came out of the speaker, and
     * everyone on the call heard it. An alarm is for its owner, not the room. So when a call is
     * live we first try to send the sound somewhere only the user hears (a connected headset), and
     * if there is no such output we don't play it at all — vibration and the full-screen ring still
     * fire, and the ring screen offers a one-tap "Play sound" override. [watchForMeetingEnd] then
     * brings the sound back by itself the moment the call ends.
     */
    private fun startSoundRespectingCalls(alarm: Alarm?): Boolean {
        val meetingAware = Prefs.meetingAwareEnabled(this) && !userForcedSound
        if (!meetingAware || !MeetingDetector.inMeeting(this)) {
            val started = startSound(alarm, preferredDevice = null)
            setSoundSuppressed(false)
            // Distinct from the muted-for-a-call state: nothing is playing and no call explains it.
            setSoundUnavailable(!started)
            return started
        }
        val privateDevice = MeetingDetector.privateOutputDevice(this)
        if (privateDevice != null && startSound(alarm, privateDevice)) {
            // Routed to a headset: audible to the user, inaudible to the call.
            setSoundSuppressed(false)
            return true
        }
        stopSound()
        setSoundSuppressed(true)
        watchForMeetingEnd()
        return false
    }

    /**
     * Starts the looping ring, optionally pinned to [preferredDevice]. Returns false when the
     * sound could not be started, or when it ended up on the phone's own speaker despite the
     * requested device — [android.media.MediaPlayer.setPreferredDevice] is only a request, and a
     * silent fall back to the loudspeaker is exactly the leak we are trying to avoid.
     */
    private fun startSound(alarm: Alarm?, preferredDevice: AudioDeviceInfo?): Boolean {
        // Every tone worth trying, best first. The chosen one can fail long after it was picked — the
        // file gets deleted, the SD card is unmounted, or the URI permission doesn't survive a reboot —
        // and the old code treated that as "no sound", with no fallback and nothing on screen to say so.
        // A silent alarm is the one failure this app exists to prevent, so exhaust the alternatives.
        val candidates = listOfNotNull(
            alarm?.soundUri?.takeIf { it.isNotBlank() }?.let { runCatching { android.net.Uri.parse(it) }.getOrNull() },
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        ).distinct()
        for ((index, uri) in candidates.withIndex()) {
            if (startSoundWithUri(uri, preferredDevice)) {
                if (index > 0) {
                    Log.w(TAG, "alarm tone unusable, fell back to candidate #$index")
                }
                return true
            }
        }
        Log.e(TAG, "no usable alarm tone at all — vibration only")
        return false
    }

    private fun startSoundWithUri(uri: android.net.Uri, preferredDevice: AudioDeviceInfo?): Boolean {
        try {
            val mp = MediaPlayer().apply {
                setDataSource(this@AlarmRingService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
            }
            if (preferredDevice != null && !mp.setPreferredDevice(preferredDevice)) {
                mp.release()
                return false
            }
            player = mp
            if (Prefs.volumeRampEnabled(this)) {
                rampVolume = 0f
                applyPlayerVolume()
                rampAnimator = ValueAnimator.ofFloat(0.05f, 1f).apply {
                    duration = VOLUME_RAMP_MS
                    addUpdateListener { anim ->
                        rampVolume = anim.animatedValue as Float
                        applyPlayerVolume()
                    }
                    start()
                }
            } else {
                rampVolume = 1f
                applyPlayerVolume()
            }
            mp.start()
            if (preferredDevice != null && MeetingDetector.isBuiltIn(mp.routedDevice)) {
                stopSound()
                return false
            }
            return true
        } catch (_: Exception) {
            // Never let a sound failure kill the ring pipeline; vibration still runs.
            player = null
            return false
        }
    }

    private fun startVibration() {
        if (vibrator != null) return
        // hasVibrator(): asking a device without a motor to buzz silently does nothing, so check
        // before claiming the alarm is alerting — this is what makes "only vibrate" honest.
        vibrator = getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }?.also { v ->
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 600), 0)
            if (Build.VERSION.SDK_INT >= 33) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(
                    effect,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                )
            }
        }
    }

    /** Publishes "this should have been audible and wasn't", so the ring screen can say so. */
    private fun setSoundUnavailable(unavailable: Boolean) {
        ringing.value = ringing.value?.copy(soundUnavailable = unavailable)
    }

    /** Publishes the muted-for-a-call state so the ring screen can explain itself. */
    private fun setSoundSuppressed(suppressed: Boolean) {
        soundSuppressed = suppressed
        ringing.value = ringing.value?.copy(soundSuppressed = suppressed)
    }

    /** Poll for the call ending, then bring the sound back on its own. */
    private fun watchForMeetingEnd() {
        handler.removeCallbacks(meetingWatchRunnable)
        handler.postDelayed(meetingWatchRunnable, MEETING_POLL_MS)
    }

    /** Ring screen's "Play sound" override — the user decides the call can cope. */
    private fun handlePlaySound() {
        userForcedSound = true
        handler.removeCallbacks(meetingWatchRunnable)
        if (player != null) return
        startSound(alarm, preferredDevice = null)
        setSoundSuppressed(false)
    }

    private fun handleSnooze() {
        val a = alarm
        val base = a?.snoozeMinutes ?: Prefs.defaultSnoozeMinutes(this)
        // "No snooze" alarms ignore any stray snooze request and keep ringing.
        if (base <= 0) return
        val coaching = a?.snoozeCoaching == true
        val newCount = snoozeCount + 1
        val appCtx = applicationContext
        val appScope = (appCtx as AlarmTrackerApp).applicationScope
        val id = alarmId
        val origScheduled = originalScheduledFor
        val budget = Prefs.weeklySnoozeBudget(appCtx)
        // Compute the (possibly coached) snooze length, register the snooze and log it
        // on the app scope so it survives this service stopping immediately below.
        appScope.launch {
            val repo = AlarmRepository.get(appCtx)
            var minutes = base
            if (coaching) {
                // Each snooze in a session is shorter than the last (SNOOZE_SHRINK_STEP_MIN
                // per prior snooze), floored so it never hits zero.
                minutes = (base - (newCount - 1) * SNOOZE_SHRINK_STEP_MIN)
                    .coerceAtLeast(SNOOZE_MIN_MINUTES)
                if (budget > 0) {
                    val weekStart = System.currentTimeMillis() - WEEK_MS
                    if (repo.snoozeCountSince(weekStart) >= budget) minutes = SNOOZE_MIN_MINUTES
                }
            }
            val triggerAt = System.currentTimeMillis() + minutes * 60_000L
            AlarmScheduler.scheduleSnooze(appCtx, id, origScheduled, newCount, triggerAt)
            // Keep the row visibly "snoozed until X" — a ONCE/EVENT alarm was already flipped to
            // disabled when it fired, so without this the list would claim it's off.
            repo.setSnoozedUntil(id, triggerAt)
            repo.logEvent(
                id, AlarmEvent.TYPE_SNOOZED, origScheduled,
                snoozeCount = newCount,
                detail = "snooze_minutes=$minutes${if (coaching) ";coached" else ""}"
            )
        }
        stopRingAndSelf()
    }

    private fun handleDismiss(missionDurationMs: Long) {
        val appScope = (applicationContext as AlarmTrackerApp).applicationScope
        val id = alarmId
        val origScheduled = originalScheduledFor
        val count = snoozeCount
        val timeToDismiss = SystemClock.elapsedRealtime() - ringStartedElapsed
        appScope.launch {
            val repo = AlarmRepository.get(applicationContext)
            repo.logEvent(
                id, AlarmEvent.TYPE_DISMISSED, origScheduled,
                snoozeCount = count,
                timeToDismissMs = timeToDismiss,
                missionDurationMs = if (missionDurationMs >= 0) missionDurationMs else null
            )
            // The snooze chain ends here — drop the pending-snooze marker and its registration.
            repo.setSnoozedUntil(id, 0)
            AlarmScheduler.cancelSnooze(applicationContext)
            AlarmScheduler.rescheduleNext(applicationContext)
        }
        stopRingAndSelf()
    }

    private fun onTimeout() {
        val appScope = (applicationContext as AlarmTrackerApp).applicationScope
        val id = alarmId
        val origScheduled = originalScheduledFor
        val count = snoozeCount
        appScope.launch {
            val repo = AlarmRepository.get(applicationContext)
            repo.logEvent(
                id, AlarmEvent.TYPE_MISSED, origScheduled,
                snoozeCount = count,
                detail = "ring_timeout"
            )
            repo.setSnoozedUntil(id, 0)
            AlarmScheduler.rescheduleNext(applicationContext)
        }
        stopRingAndSelf()
    }

    private fun stopRingAndSelf() {
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(meetingWatchRunnable)
        stopSound()
        vibrator?.cancel()
        vibrator = null
        ringing.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Push the effective volume (fade-in level × live duck scale) to the player, if any. */
    private fun applyPlayerVolume() {
        val v = (rampVolume * volumeScale).coerceIn(0f, 1f)
        try {
            player?.setVolume(v, v)
        } catch (_: IllegalStateException) {
        }
    }

    private fun stopSound() {
        rampAnimator?.cancel()
        rampAnimator = null
        player?.run {
            try {
                stop()
            } catch (_: IllegalStateException) {
            }
            release()
        }
        player = null
    }

    private fun buildNotification(label: String, alarm: Alarm?): Notification {
        val fullScreen = PendingIntent.getActivity(
            this, 200,
            Intent(this, AlarmActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozePi = PendingIntent.getService(
            this, 201,
            Intent(this, AlarmRingService::class.java).setAction(ACTION_SNOOZE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val missionRequired = alarm != null &&
            alarm.missionType != Alarm.MISSION_NONE
        // With a mission, the notification's Dismiss routes into the mission UI
        // instead of directly stopping the alarm.
        val dismissPi = if (missionRequired) {
            fullScreen
        } else {
            PendingIntent.getService(
                this, 202,
                Intent(this, AlarmRingService::class.java).setAction(ACTION_DISMISS),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val snoozeAllowed = alarm?.let { it.snoozeMinutes > 0 } ?: true
        return NotificationCompat.Builder(this, AlarmTrackerApp.CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(getString(R.string.notification_ring_title))
            .setContentText(getString(R.string.notification_ring_text, label))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .apply {
                if (snoozeAllowed) addAction(R.drawable.ic_snooze, getString(R.string.ring_snooze), snoozePi)
                // Same "Open <app>" affordance the ring screen offers, for when the alarm was
                // demoted to a plain notification (no full-screen-intent access).
                ringing.value?.actionLabel?.let { label ->
                    addAction(R.drawable.ic_alarm, getString(R.string.ring_open_fmt, label), fullScreen)
                }
            }
            .addAction(R.drawable.ic_close, getString(R.string.ring_dismiss), dismissPi)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeoutRunnable)
        handler.removeCallbacks(meetingWatchRunnable)
        stopSound()
        vibrator?.cancel()
        ringing.value = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AlarmRingService"
        const val ACTION_START = "com.example.alarmtracker.ring.START"
        const val ACTION_SNOOZE = "com.example.alarmtracker.ring.SNOOZE"
        const val ACTION_DISMISS = "com.example.alarmtracker.ring.DISMISS"
        const val ACTION_SET_VOLUME_SCALE = "com.example.alarmtracker.ring.SET_VOLUME_SCALE"
        const val ACTION_PLAY_SOUND = "com.example.alarmtracker.ring.PLAY_SOUND"
        const val EXTRA_MISSION_DURATION_MS = "extra_mission_duration_ms"
        const val EXTRA_VOLUME_SCALE = "extra_volume_scale"

        private const val NOTIFICATION_ID = 1
        private const val RING_TIMEOUT_MS = 10 * 60_000L
        private const val VOLUME_RAMP_MS = 60_000L

        /** How often a call-muted ring re-checks whether the call has ended. */
        private const val MEETING_POLL_MS = 3_000L
        private const val SNOOZE_SHRINK_STEP_MIN = 3
        private const val SNOOZE_MIN_MINUTES = 1
        private const val WEEK_MS = 7 * 24 * 60 * 60 * 1000L

        /** Currently ringing alarm; AlarmActivity finishes when this goes null. */
        val ringing: MutableStateFlow<RingState?> = MutableStateFlow(null)
        val ringingState: StateFlow<RingState?> get() = ringing

        fun snooze(context: Context) {
            context.startService(
                Intent(context, AlarmRingService::class.java).setAction(ACTION_SNOOZE)
            )
        }

        fun dismiss(context: Context, missionDurationMs: Long = -1L) {
            context.startService(
                Intent(context, AlarmRingService::class.java)
                    .setAction(ACTION_DISMISS)
                    .putExtra(EXTRA_MISSION_DURATION_MS, missionDurationMs)
            )
        }

        /** Ring screen override: play the sound anyway even though a call is in progress. */
        fun playSound(context: Context) {
            context.startService(
                Intent(context, AlarmRingService::class.java).setAction(ACTION_PLAY_SOUND)
            )
        }

        /** Live-duck the ring volume (1f = full, lower = quieter). Ignored if nothing is ringing. */
        fun setVolumeScale(context: Context, scale: Float) {
            if (ringing.value == null) return
            context.startService(
                Intent(context, AlarmRingService::class.java)
                    .setAction(ACTION_SET_VOLUME_SCALE)
                    .putExtra(EXTRA_VOLUME_SCALE, scale)
            )
        }
    }
}
