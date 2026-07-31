package com.example.alarmtracker.util

import com.example.alarmtracker.data.Alarm
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/**
 * Pure next-occurrence math for alarms.
 * daysOfWeek bitmask: Mon = 1&lt;&lt;0 … Sun = 1&lt;&lt;6; 0 = one-shot.
 *
 * The scheduling model is "recurrence rule -> materialized nextTriggerAt": each
 * schedule type answers "what is the next trigger after [from]?" so new rule
 * types (shift patterns, calendar-aware) slot in without touching the scheduler.
 */
object AlarmTimes {

    /** Bit index (0=Mon … 6=Sun) for a [Calendar.DAY_OF_WEEK] value. */
    fun bitIndexForCalendarDay(calendarDay: Int): Int = when (calendarDay) {
        Calendar.MONDAY -> 0
        Calendar.TUESDAY -> 1
        Calendar.WEDNESDAY -> 2
        Calendar.THURSDAY -> 3
        Calendar.FRIDAY -> 4
        Calendar.SATURDAY -> 5
        else -> 6 // Calendar.SUNDAY
    }

    /**
     * Next trigger time (epoch millis) strictly after [from] for an alarm at
     * [hour]:[minute] with the given repeat [daysOfWeek] bitmask.
     */
    fun nextTrigger(
        hour: Int,
        minute: Int,
        daysOfWeek: Int,
        from: Long = System.currentTimeMillis()
    ): Long {
        val base = Calendar.getInstance().apply {
            timeInMillis = from
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (daysOfWeek == 0) {
            if (base.timeInMillis <= from) base.add(Calendar.DAY_OF_YEAR, 1)
            return base.timeInMillis
        }
        for (offset in 0..7) {
            val candidate = base.clone() as Calendar
            candidate.add(Calendar.DAY_OF_YEAR, offset)
            val bit = 1 shl bitIndexForCalendarDay(candidate.get(Calendar.DAY_OF_WEEK))
            if (candidate.timeInMillis > from && (daysOfWeek and bit) != 0) {
                return candidate.timeInMillis
            }
        }
        // Unreachable for any non-zero mask, but never crash the scheduler.
        return base.timeInMillis + 24 * 60 * 60 * 1000L
    }

    // ---- Shift-pattern schedule ----

    /** Cycle length of a shift pattern (work + rest days). */
    fun shiftCycleLength(alarm: Alarm): Int = alarm.shiftWorkDays + alarm.shiftRestDays

    /** True if [epochDay] is a work (on) day for the alarm's rotating pattern. */
    fun isShiftWorkDay(alarm: Alarm, epochDay: Long): Boolean {
        val cycle = shiftCycleLength(alarm)
        if (alarm.shiftWorkDays <= 0 || cycle <= 0) return false
        val pos = Math.floorMod(epochDay - alarm.shiftAnchorDate, cycle.toLong())
        return pos < alarm.shiftWorkDays
    }

    /**
     * Next trigger (epoch millis) strictly after [from] for a SHIFT alarm.
     * A misconfigured pattern (no work days) degrades to a daily alarm so the
     * scheduler never silently loses it.
     */
    fun nextShiftTrigger(alarm: Alarm, from: Long = System.currentTimeMillis()): Long {
        val cycle = shiftCycleLength(alarm)
        if (alarm.shiftWorkDays <= 0 || cycle <= 0) {
            return nextTrigger(alarm.hour, alarm.minute, 0b1111111, from)
        }
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        // Scanning one full cycle from today is guaranteed to hit a work day.
        for (offset in 0..cycle) {
            val date = startDate.plusDays(offset.toLong())
            if (isShiftWorkDay(alarm, date.toEpochDay())) {
                val trigger = date.atTime(alarm.hour, alarm.minute)
                    .atZone(zone).toInstant().toEpochMilli()
                if (trigger > from) return trigger
            }
        }
        return startDate.plusDays(1).atTime(alarm.hour, alarm.minute)
            .atZone(zone).toInstant().toEpochMilli()
    }

    /** The next [count] shift fire times (epoch millis) — used for the editor preview. */
    fun nextShiftTriggers(alarm: Alarm, count: Int, from: Long = System.currentTimeMillis()): List<Long> {
        val result = ArrayList<Long>(count)
        var cursor = from
        repeat(count) {
            val next = nextShiftTrigger(alarm, cursor)
            result.add(next)
            cursor = next
        }
        return result
    }
}
