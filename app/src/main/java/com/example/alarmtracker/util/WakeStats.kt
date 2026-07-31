package com.example.alarmtracker.util

import com.example.alarmtracker.data.AlarmEvent
import java.util.Calendar

/** Small pure helpers over DISMISSED events, shared by the widget, report card and share. */
object WakeStats {

    /** Current no-snooze streak: consecutive most-recent wakes dismissed without snoozing. */
    fun streak(dismissals: List<AlarmEvent>): Int =
        dismissals.sortedByDescending { it.occurredAt }
            .takeWhile { it.snoozeCount == 0 }
            .count()

    /** Minutes past local midnight for a timestamp (0..1439). */
    fun minutesOfDay(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    /** Average wake time as minutes-past-midnight, or null when there are no dismissals. */
    fun averageWakeMinutes(dismissals: List<AlarmEvent>): Double? =
        if (dismissals.isEmpty()) null
        else dismissals.map { minutesOfDay(it.occurredAt).toDouble() }.average()
}
