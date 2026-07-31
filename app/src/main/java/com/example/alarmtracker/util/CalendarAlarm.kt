package com.example.alarmtracker.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.example.alarmtracker.data.Alarm
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Calendar-aware smart-alarm computation. Reads the device calendar (all visible
 * calendars) to wake the user a prep buffer before the day's first event, floored
 * at the alarm's own time. Holiday all-day events auto-skip the day; all-day and
 * declined events are ignored when picking "the first event".
 *
 * Everything degrades gracefully: with no READ_CALENDAR permission, or on any
 * query failure, the alarm falls back to its plain floor time so it never goes
 * silent.
 */
object CalendarAlarm {

    /** How many days ahead to look for a valid firing day before giving up. */
    private const val LOOKAHEAD_DAYS = 21

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Next trigger (epoch millis) strictly after [from] for a CALENDAR alarm, or
     * null if every considered day within the lookahead is skipped.
     */
    fun nextTrigger(context: Context, alarm: Alarm, from: Long = System.currentTimeMillis()): Long? {
        if (!hasPermission(context)) {
            // No access — behave like a plain weekly/daily alarm at the floor time.
            return AlarmTimes.nextTrigger(alarm.hour, alarm.minute, alarm.daysOfWeek, from)
        }
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(from).atZone(zone).toLocalDate()
        for (offset in 0..LOOKAHEAD_DAYS) {
            val date = startDate.plusDays(offset.toLong())
            if (!isConsideredDay(alarm, date)) continue

            val floor = date.atTime(alarm.hour, alarm.minute).atZone(zone).toInstant().toEpochMilli()
            val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

            val scan = try {
                scanDay(context, dayStart, dayEnd)
            } catch (_: Exception) {
                DayScan(isHoliday = false, firstEventStart = null)
            }

            if (scan.isHoliday) continue // holiday auto-skip

            val candidate = if (scan.firstEventStart != null) {
                val buffered = scan.firstEventStart - alarm.prepBufferMinutes * 60_000L
                maxOf(floor, buffered)
            } else {
                if (alarm.calendarSkipIfNoEvent) continue else floor
            }
            if (candidate > from) return candidate
        }
        // Everything in range skipped — let the caller (scheduler) treat as no trigger.
        return if (alarm.calendarSkipIfNoEvent) null
        else AlarmTimes.nextTrigger(alarm.hour, alarm.minute, alarm.daysOfWeek, from)
    }

    private fun isConsideredDay(alarm: Alarm, date: LocalDate): Boolean {
        if (alarm.daysOfWeek == 0) return true // 0 = every day for calendar mode
        // LocalDate.dayOfWeek: MONDAY=1..SUNDAY=7 -> bit index 0..6
        val bit = 1 shl (date.dayOfWeek.value - 1)
        return alarm.daysOfWeek and bit != 0
    }

    private data class DayScan(val isHoliday: Boolean, val firstEventStart: Long?)

    private fun scanDay(context: Context, dayStart: Long, dayEnd: Long): DayScan {
        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.SELF_ATTENDEE_STATUS,
            CalendarContract.Instances.STATUS,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        )
        // Instances.CONTENT_URI expands recurrences; begin/end are appended as path segments.
        val queryUri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            appendPath(dayStart.toString())
            appendPath(dayEnd.toString())
        }.build()

        var isHoliday = false
        var firstEventStart: Long? = null

        context.contentResolver.query(queryUri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")
            ?.use { cursor ->
                val idxBegin = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val idxAllDay = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                val idxStatus = cursor.getColumnIndexOrThrow(CalendarContract.Instances.SELF_ATTENDEE_STATUS)
                val idxEvStatus = cursor.getColumnIndexOrThrow(CalendarContract.Instances.STATUS)
                val idxCalName = cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val begin = cursor.getLong(idxBegin)
                    val allDay = cursor.getInt(idxAllDay) == 1
                    val attendeeStatus = cursor.getInt(idxStatus)
                    val eventStatus = if (cursor.isNull(idxEvStatus)) -1 else cursor.getInt(idxEvStatus)
                    val calName = cursor.getString(idxCalName) ?: ""

                    // Declined or cancelled events are ignored entirely.
                    if (attendeeStatus == CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED) continue
                    if (eventStatus == CalendarContract.Events.STATUS_CANCELED) continue

                    if (allDay) {
                        if (looksLikeHolidayCalendar(calName)) isHoliday = true
                        continue // all-day events never count as "the first event"
                    }
                    // First timed event that actually starts within the day.
                    if (begin >= dayStart && begin < dayEnd) {
                        if (firstEventStart == null || begin < firstEventStart!!) {
                            firstEventStart = begin
                        }
                    }
                }
            }
        return DayScan(isHoliday, firstEventStart)
    }

    private fun looksLikeHolidayCalendar(displayName: String): Boolean {
        val n = displayName.lowercase()
        return n.contains("holiday") || n.contains("observance")
    }
}
