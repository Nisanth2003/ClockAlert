package com.example.alarmtracker.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.alarmtracker.MainActivity
import com.example.alarmtracker.data.AlarmEvent
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.data.Alarm
import com.example.alarmtracker.ring.AlarmReceiver
import com.example.alarmtracker.ui.widget.NextAlarmWidget

/**
 * Owns the single system alarm registration. The earliest enabled alarm's
 * nextTriggerAt is re-derived from the DB on every change and registered via
 * setAlarmClock(). Snoozes use a separate PendingIntent so they never fight
 * with the regular derivation.
 */
object AlarmScheduler {

    const val EXTRA_ALARM_ID = "extra_alarm_id"
    const val EXTRA_SCHEDULED_FOR = "extra_scheduled_for"
    const val EXTRA_ORIGINAL_SCHEDULED_FOR = "extra_original_scheduled_for"
    const val EXTRA_SNOOZE_COUNT = "extra_snooze_count"

    const val EXTRA_GLOW_REAL_AT = "extra_glow_real_at"

    private const val RC_FIRE = 100
    private const val RC_SNOOZE = 101
    private const val RC_SHOW = 102
    private const val RC_GLOW = 103

    /**
     * Base request code for per-event-alarm PendingIntents. Each event alarm gets its own
     * dedicated setAlarmClock (request code = base + alarmId) so it never fights with the
     * single-slot [rescheduleNext] derivation used by regular alarms, mirroring how snooze
     * and glow already use independent PendingIntents.
     */
    private const val RC_EVENT_BASE = 200_000

    /** Grace period: a trigger this recently in the past is "about to ring", not missed. */
    private const val STALE_GRACE_MS = 90_000L

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        val am = context.getSystemService(AlarmManager::class.java)
        return am.canScheduleExactAlarms()
    }

    /**
     * Re-derives the next alarm from the DB and registers it with the system.
     * Stale (missed while powered off) triggers are logged MISSED and advanced.
     */
    suspend fun rescheduleNext(context: Context) {
        val repo = AlarmRepository.get(context)
        val now = System.currentTimeMillis()

        // Heal stale triggers (e.g. after reboot past the alarm time).
        repo.getEnabled().forEach { alarm ->
            // Event alarms deliberately have no nextTriggerAt (EventAlarmCoordinator owns their
            // scheduling). Re-"materializing" one writes null back every single pass, which just
            // churns the row and re-emits the list flow for nothing.
            if (alarm.scheduleType == Alarm.SCHEDULE_EVENT) return@forEach
            val t = alarm.nextTriggerAt
            if (t == null) {
                repo.save(alarm) // materialize missing trigger
            } else if (t < now - STALE_GRACE_MS) {
                repo.logEvent(alarm.id, AlarmEvent.TYPE_MISSED, t, detail = "stale_at_reschedule")
                repo.advanceAfterRing(alarm.id, now)
            }
        }

        val am = context.getSystemService(AlarmManager::class.java)
        val next = repo.getNextEnabled()
        // Keep the home-screen widget in sync with the next-alarm/streak on every change.
        NextAlarmWidget.refresh(context)
        if (next?.nextTriggerAt == null) {
            am.cancel(firePendingIntent(context, 0L, 0L))
            cancelGlow(context)
            return
        }
        if (!canScheduleExact(context)) return // surfaced by the list banner

        val triggerAt = next.nextTriggerAt
        val fire = firePendingIntent(context, next.id, triggerAt)
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showPendingIntent(context)), fire)
        repo.logScheduledOnce(next.id, triggerAt)
        // Sunrise glow: a separate, best-effort silent pre-alarm. It never affects the
        // real alarm above (independent PendingIntent); if it fails the alarm still rings.
        scheduleGlow(context, am, next.id, triggerAt, next.gentleWakeMinutes)
    }

    /**
     * Schedules (or cancels) the silent sunrise pre-alarm for [alarmId]. Fires
     * [gentleWakeMinutes] before [realTriggerAt] via a distinct PendingIntent so it can
     * never double-fire or interfere with the real alarm.
     */
    private fun scheduleGlow(
        context: Context,
        am: AlarmManager,
        alarmId: Long,
        realTriggerAt: Long,
        gentleWakeMinutes: Int
    ) {
        cancelGlow(context)
        if (gentleWakeMinutes <= 0) return
        val glowAt = realTriggerAt - gentleWakeMinutes * 60_000L
        if (glowAt <= System.currentTimeMillis()) return // too late to glow; alarm rings as normal
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, glowAt, glowPendingIntent(context, alarmId, realTriggerAt))
        } catch (_: SecurityException) {
            // Missing exact-alarm permission — silently skip the glow; the real alarm is unaffected.
        }
    }

    fun cancelGlow(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(glowPendingIntent(context, 0L, 0L))
    }

    private fun glowPendingIntent(context: Context, alarmId: Long, realTriggerAt: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_GLOW
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_GLOW_REAL_AT, realTriggerAt)
        }
        return PendingIntent.getBroadcast(
            context, RC_GLOW, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Recomputes every enabled alarm's trigger (wall clock / timezone changed). */
    suspend fun recomputeAllAndReschedule(context: Context) {
        val repo = AlarmRepository.get(context)
        // repo.save re-materializes nextTriggerAt from each alarm's recurrence rule.
        repo.getEnabled().forEach { alarm -> repo.save(alarm) }
        rescheduleNext(context)
    }

    /**
     * Recomputes only CALENDAR alarms and re-registers the next alarm. Called from
     * the calendar ContentObserver and the manual "recompute now" action so a
     * changed schedule is reflected without touching unrelated alarms.
     */
    suspend fun recomputeCalendarAndReschedule(context: Context) {
        val repo = AlarmRepository.get(context)
        repo.getEnabled()
            .filter { it.scheduleType == Alarm.SCHEDULE_CALENDAR }
            .forEach { alarm -> repo.save(alarm) }
        rescheduleNext(context)
    }

    /**
     * Registers (or re-registers) the single event alarm for [alarmId] at [triggerAt] via a
     * dedicated PendingIntent, independent of the regular earliest-alarm derivation. Called by
     * [EventAlarmCoordinator] on setup and on every ETA refinement. The fired intent routes
     * through the normal ring pipeline (AlarmReceiver.ACTION_FIRE), so an event alarm rings
     * with the same sound/mission machinery as any other alarm.
     */
    fun scheduleEventAlarm(context: Context, alarmId: Long, triggerAt: Long) {
        if (!canScheduleExact(context)) return // surfaced by the list banner
        val am = context.getSystemService(AlarmManager::class.java)
        am.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerAt, showPendingIntent(context)),
            eventPendingIntent(context, alarmId, triggerAt)
        )
    }

    /** Cancels the event alarm for [alarmId] (on disable/delete/arrival-fired). */
    fun cancelEventAlarm(context: Context, alarmId: Long) {
        val am = context.getSystemService(AlarmManager::class.java)
        am.cancel(eventPendingIntent(context, alarmId, 0L))
    }

    private fun eventPendingIntent(context: Context, alarmId: Long, scheduledFor: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_SCHEDULED_FOR, scheduledFor)
            putExtra(EXTRA_ORIGINAL_SCHEDULED_FOR, scheduledFor)
            putExtra(EXTRA_SNOOZE_COUNT, 0)
        }
        return PendingIntent.getBroadcast(
            context, RC_EVENT_BASE + alarmId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Registers an exact snooze ring, independent of the regular derivation. */
    fun scheduleSnooze(
        context: Context,
        alarmId: Long,
        originalScheduledFor: Long,
        snoozeCount: Int,
        triggerAt: Long
    ) {
        if (!canScheduleExact(context)) return
        val am = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_SCHEDULED_FOR, triggerAt)
            putExtra(EXTRA_ORIGINAL_SCHEDULED_FOR, originalScheduledFor)
            putExtra(EXTRA_SNOOZE_COUNT, snoozeCount)
        }
        val pi = PendingIntent.getBroadcast(
            context, RC_SNOOZE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showPendingIntent(context)), pi)
    }

    fun cancelSnooze(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
        }
        val pi = PendingIntent.getBroadcast(
            context, RC_SNOOZE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
    }

    private fun firePendingIntent(context: Context, alarmId: Long, scheduledFor: Long): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_SCHEDULED_FOR, scheduledFor)
            putExtra(EXTRA_ORIGINAL_SCHEDULED_FOR, scheduledFor)
            putExtra(EXTRA_SNOOZE_COUNT, 0)
        }
        return PendingIntent.getBroadcast(
            context, RC_FIRE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun showPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, RC_SHOW,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
