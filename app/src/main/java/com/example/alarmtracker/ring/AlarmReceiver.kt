package com.example.alarmtracker.ring

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.alarmtracker.AlarmTrackerApp
import com.example.alarmtracker.R
import com.example.alarmtracker.data.Alarm
import com.example.alarmtracker.data.AlarmEvent
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.scheduling.AlarmScheduler
import com.example.alarmtracker.scheduling.EventAlarmCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Entry point of the ring pipeline. Immediately hands off to the foreground
 * service (which owns sound, vibration and the full-screen notification),
 * then logs FIRED / FIRED_LATE and advances the alarm's next occurrence.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_GLOW) {
            handleGlow(context, intent)
            return
        }
        if (intent.action != ACTION_FIRE) return
        val alarmId = intent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L)
        if (alarmId <= 0L) return
        val scheduledFor = intent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_FOR, 0L)
        val originalScheduledFor =
            intent.getLongExtra(AlarmScheduler.EXTRA_ORIGINAL_SCHEDULED_FOR, scheduledFor)
        val snoozeCount = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0)
        val now = System.currentTimeMillis()

        // 1. Start the ring service first — nothing may delay the ring.
        val serviceIntent = Intent(context, AlarmRingService::class.java).apply {
            action = AlarmRingService.ACTION_START
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_FOR, scheduledFor)
            putExtra(AlarmScheduler.EXTRA_ORIGINAL_SCHEDULED_FOR, originalScheduledFor)
            putExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, snoozeCount)
        }
        ContextCompat.startForegroundService(context, serviceIntent)

        // 2. Log the fire and re-derive scheduling state.
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val repo = AlarmRepository.get(context)
                // This ring consumes any pending snooze (this fire may BE the snooze ring).
                repo.setSnoozedUntil(alarmId, 0)
                if (snoozeCount == 0) {
                    val delta = now - scheduledFor
                    val type = if (delta > LATE_THRESHOLD_MS) {
                        AlarmEvent.TYPE_FIRED_LATE
                    } else {
                        AlarmEvent.TYPE_FIRED
                    }
                    repo.logEvent(
                        alarmId, type, originalScheduledFor,
                        snoozeCount = 0,
                        detail = if (type == AlarmEvent.TYPE_FIRED_LATE) "late_ms=$delta" else null
                    )
                    // The ring instance is now owned by the service; advance the row.
                    repo.advanceAfterRing(alarmId, now)
                    // Event alarms: tear down geofences + cancel the (now-consumed) registration.
                    val alarm = repo.getAlarm(alarmId)
                    if (alarm?.scheduleType == Alarm.SCHEDULE_EVENT) {
                        EventAlarmCoordinator.onAlarmFired(context, alarmId)
                    }
                }
                AlarmScheduler.rescheduleNext(context)
            } finally {
                result.finish()
            }
        }
    }

    /**
     * Silent sunrise pre-alarm. Opens the ring activity in glow mode via a full-screen
     * intent (so it shows over the lockscreen) WITHOUT starting the ring service — no
     * sound, no vibration. The real alarm fires independently and swaps in the ring.
     */
    private fun handleGlow(context: Context, intent: Intent) {
        // If the real alarm is already ringing, there is nothing gentle to do.
        if (AlarmRingService.ringing.value != null) return
        val realAt = intent.getLongExtra(AlarmScheduler.EXTRA_GLOW_REAL_AT, 0L)
        if (realAt <= 0L || realAt <= System.currentTimeMillis()) return

        val activityIntent = Intent(context, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AlarmActivity.EXTRA_GLOW, true)
            .putExtra(AlarmActivity.EXTRA_GLOW_REAL_AT, realAt)
        val fullScreen = PendingIntent.getActivity(
            context, 300, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AlarmTrackerApp.CHANNEL_GLOW)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle(context.getString(R.string.notification_glow_title))
            .setContentText(context.getString(R.string.notification_glow_text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(fullScreen)
            .setFullScreenIntent(fullScreen, true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(GLOW_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — glow is best-effort; the real alarm is unaffected.
        }
    }

    companion object {
        const val ACTION_FIRE = "com.example.alarmtracker.ACTION_ALARM_FIRE"
        const val ACTION_GLOW = "com.example.alarmtracker.ACTION_ALARM_GLOW"
        private const val LATE_THRESHOLD_MS = 60_000L
        private const val GLOW_NOTIFICATION_ID = 2
    }
}
