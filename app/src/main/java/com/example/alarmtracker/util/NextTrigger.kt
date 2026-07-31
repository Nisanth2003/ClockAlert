package com.example.alarmtracker.util

import android.content.Context
import com.example.alarmtracker.data.Alarm

/**
 * Single entry point that materializes an alarm's next trigger (epoch millis) from
 * its recurrence rule, honoring the pause-for-date-range window. Every schedule
 * type routes through here so the scheduler only ever deals with nextTriggerAt.
 *
 * Returns null when there is no future trigger (e.g. a calendar alarm set to skip
 * empty days with nothing scheduled in range).
 */
object NextTrigger {

    fun compute(
        context: Context,
        alarm: Alarm,
        from: Long = System.currentTimeMillis()
    ): Long? {
        // While paused, resume scanning from the end of the pause window.
        val pauseBase = if (alarm.isPausedAt(from)) alarm.pausedUntil!! else from
        // Skip-next: never materialize an occurrence at or before the skip boundary.
        val base = if (alarm.skipUntil > pauseBase) alarm.skipUntil else pauseBase
        return when (alarm.scheduleType) {
            // Event alarms are scheduled out-of-band by EventAlarmCoordinator (their own
            // dedicated setAlarmClock at the refined ETA / fallback), never through the
            // single-slot earliest-alarm derivation. So they materialize no nextTriggerAt.
            Alarm.SCHEDULE_EVENT -> null
            Alarm.SCHEDULE_CALENDAR -> CalendarAlarm.nextTrigger(context, alarm, base)
            Alarm.SCHEDULE_SHIFT -> AlarmTimes.nextShiftTrigger(alarm, base)
            else -> AlarmTimes.nextTrigger(alarm.hour, alarm.minute, alarm.daysOfWeek, base)
        }
    }
}
