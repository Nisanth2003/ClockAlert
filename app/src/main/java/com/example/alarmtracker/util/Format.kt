package com.example.alarmtracker.util

import android.content.Context
import com.example.alarmtracker.R
import com.example.alarmtracker.data.Alarm
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Text formatting helpers for alarm times, repeat summaries and countdowns. */
object Format {

    /** Returns time text and an optional AM/PM suffix, honoring the time-format setting. */
    fun timeParts(context: Context, hour: Int, minute: Int): Pair<String, String?> {
        return if (Prefs.is24Hour(context)) {
            String.format(Locale.getDefault(), "%02d:%02d", hour, minute) to null
        } else {
            val h12 = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            val amPm = DateFormatSymbols.getInstance().amPmStrings[if (hour < 12) Calendar.AM else Calendar.PM]
            String.format(Locale.getDefault(), "%d:%02d", h12, minute) to amPm
        }
    }

    fun timeText(context: Context, hour: Int, minute: Int): String {
        val (time, amPm) = timeParts(context, hour, minute)
        return if (amPm != null) "$time $amPm" else time
    }

    /** "Every day" / "Weekdays" / "Weekends" / "Mon, Tue" / "Today" / "Tomorrow". */
    fun repeatSummary(context: Context, alarm: Alarm): String {
        val days = alarm.daysOfWeek
        if (days == 0) {
            val next = alarm.nextTriggerAt
                ?: AlarmTimes.nextTrigger(alarm.hour, alarm.minute, 0)
            val today = Calendar.getInstance()
            val trigger = Calendar.getInstance().apply { timeInMillis = next }
            return if (today.get(Calendar.DAY_OF_YEAR) == trigger.get(Calendar.DAY_OF_YEAR) &&
                today.get(Calendar.YEAR) == trigger.get(Calendar.YEAR)
            ) {
                context.getString(R.string.repeat_today)
            } else {
                context.getString(R.string.repeat_tomorrow)
            }
        }
        if (days == 0b1111111) return context.getString(R.string.repeat_every_day)
        if (days == 0b0011111) return context.getString(R.string.repeat_weekdays)
        if (days == 0b1100000) return context.getString(R.string.repeat_weekends)

        val symbols = DateFormatSymbols.getInstance().shortWeekdays
        val calendarDays = intArrayOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )
        return (0..6)
            .filter { days and (1 shl it) != 0 }
            .joinToString(", ") { symbols[calendarDays[it]] }
    }

    /** Localized medium date, e.g. "Jul 25". */
    fun dateMedium(context: Context, millis: Long): String =
        android.text.format.DateFormat.getMediumDateFormat(context).format(Date(millis))

    /** "Mon, Jul 21 · 6:00 AM" — one line of the shift-pattern preview. */
    fun dateTimeLine(context: Context, millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val dayDate = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(millis))
        val time = timeText(context, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        return "$dayDate · $time"
    }

    /** "7 h 32 min" style countdown text for a duration in millis. */
    fun untilText(context: Context, millis: Long): String {
        if (millis < 60_000) return context.getString(R.string.duration_less_than_min)
        val totalMinutes = millis / 60_000
        val hours = totalMinutes / 60
        val minutes = (totalMinutes % 60).toInt()
        return if (hours > 0) {
            context.getString(R.string.duration_h_min, hours, minutes)
        } else {
            context.getString(R.string.duration_min, minutes)
        }
    }
}
