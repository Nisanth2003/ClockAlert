package com.example.alarmtracker

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import com.example.alarmtracker.scheduling.AlarmScheduler
import com.example.alarmtracker.scheduling.PreflightScheduler
import com.example.alarmtracker.util.CalendarAlarm
import com.example.alarmtracker.util.Prefs
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import kotlinx.coroutines.launch

class AlarmTrackerApp : Application() {

    /** Scope for fire-and-forget work that must outlive short-lived UI (e.g. sheet auto-save). */
    val applicationScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private var calendarObserver: ContentObserver? = null
    private val recomputeCalendar = Runnable {
        applicationScope.launch { AlarmScheduler.recomputeCalendarAndReschedule(this@AlarmTrackerApp) }
    }

    override fun onCreate() {
        super.onCreate()
        Prefs.applyThemeFromPrefs(this)
        applyDynamicColours()
        createRingChannel()
        registerCalendarObserver()
        // Zero-permission lock-status sleep proxy (fallback): record night screen-off / morning unlock.
        try {
            com.example.alarmtracker.util.ScreenSleepReceiver.register(this)
        } catch (_: Exception) {
        }
        // Keep the nightly pre-flight check in sync with its setting on every launch.
        PreflightScheduler.apply(this)
        // Keep the connector background poll in sync (enqueues only if a connector is linked).
        com.example.alarmtracker.connector.ConnectorScheduler.apply(this)
        // Recycle bin housekeeping: drop soft-deleted alarms/timers past their retention window.
        applicationScope.launch {
            com.example.alarmtracker.data.AlarmRepository.get(this@AlarmTrackerApp).purgeExpiredDeleted()
            com.example.alarmtracker.ui.timer.TimerController.purgeExpired(this@AlarmTrackerApp)
            // A force-stop drops pending AlarmManager wake-ups; re-arm the in-transit ETA re-check.
            com.example.alarmtracker.scheduling.LiveEtaTracker.sync(this@AlarmTrackerApp)
            // Friend alerts: keep the periodic relay check enqueued while any friend is paired,
            // and restart the session service if a share window survived a process death.
            com.example.alarmtracker.friends.FriendsSyncScheduler.apply(this@AlarmTrackerApp)
            com.example.alarmtracker.friends.FriendsSessionService.syncRunState(this@AlarmTrackerApp)
        }
    }

    /**
     * Recolours every activity as it's created, from the user's chosen accent.
     *
     * Deliberately NOT `DynamicColors.applyToActivitiesIfAvailable`: that builds its options once
     * at startup, so a seed chosen later would never take effect. Hooking the lifecycle ourselves
     * lets us read the preference per activity, which is what makes "pick an accent, see it
     * immediately on recreate" work.
     *
     * With no seed chosen we pass no content source, which is plain Material You (wallpaper).
     * Either way this needs Android 12+; below that the static palette in colors.xml is used.
     */
    private fun applyDynamicColours() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (!Prefs.dynamicColorEnabled(this@AlarmTrackerApp)) return
                val builder = DynamicColorsOptions.Builder()
                Prefs.accentSeed(this@AlarmTrackerApp)?.let { builder.setContentBasedSource(it) }
                DynamicColors.applyToActivityIfAvailable(activity, builder.build())
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * Lightweight recompute of CALENDAR alarms while the process is alive: a change
     * to the device calendar debounces a recompute (5s) so shifting an event moves
     * the alarm. RescheduleReceiver and the manual action cover the process-dead case.
     */
    private fun registerCalendarObserver() {
        if (!CalendarAlarm.hasPermission(this)) return
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                mainHandler.removeCallbacks(recomputeCalendar)
                mainHandler.postDelayed(recomputeCalendar, CALENDAR_DEBOUNCE_MS)
            }
        }
        try {
            contentResolver.registerContentObserver(
                CalendarContract.CONTENT_URI, true, observer
            )
            calendarObserver = observer
        } catch (_: Exception) {
            // Some devices/emulators without a calendar provider — safe to ignore.
        }
    }

    private fun createRingChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_RING,
            getString(R.string.notification_channel_ring),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_ring_description)
            setSound(null, null) // the foreground service owns the alarm sound
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)

        // Sunrise-glow pre-alarm: silent, but high importance so its full-screen intent
        // can wake the screen ahead of the real alarm.
        val glow = NotificationChannel(
            CHANNEL_GLOW,
            getString(R.string.notification_channel_glow),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_glow_description)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(glow)

        // Nightly pre-flight health check: informational, default importance.
        val preflight = NotificationChannel(
            CHANNEL_PREFLIGHT,
            getString(R.string.notification_channel_preflight),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_preflight_description)
        }
        manager.createNotificationChannel(preflight)

        // Event alarms: quiet "≈X km to go" progress updates. Low importance, never sounds.
        val event = NotificationChannel(
            CHANNEL_EVENT,
            getString(R.string.notification_channel_event),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_event_description)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(event)

        // Countdown timer finished: alerts with the alarm sound + vibration.
        val timer = NotificationChannel(
            CHANNEL_TIMER,
            getString(R.string.notification_channel_timer),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.notification_channel_timer_description)
            val sound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(sound, attrs)
            enableVibration(true)
            setBypassDnd(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(timer)

        // Friend arrival alerts + the visible "you are sharing" status. Default importance: this
        // saves you standing in the street, it isn't an alarm and must not behave like one.
        val friends = NotificationChannel(
            CHANNEL_FRIENDS,
            getString(R.string.notification_channel_friends),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_friends_description)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(friends)
    }

    companion object {
        const val CHANNEL_RING = "alarm_ring"
        const val CHANNEL_GLOW = "alarm_glow"
        const val CHANNEL_PREFLIGHT = "alarm_preflight"
        const val CHANNEL_EVENT = "alarm_event"
        const val CHANNEL_TIMER = "alarm_timer"
        const val CHANNEL_FRIENDS = "friend_alerts"
        private const val CALENDAR_DEBOUNCE_MS = 5_000L
    }
}
