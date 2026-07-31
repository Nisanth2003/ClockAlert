package com.example.alarmtracker.util

import com.example.alarmtracker.data.Alarm
import com.example.alarmtracker.data.AlarmEvent
import com.example.alarmtracker.data.SleepSignal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds an on-device CSV export of alarms + events + bedtime signals (feature 7). */
object DataExport {

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    fun buildCsv(
        alarms: List<Alarm>,
        events: List<AlarmEvent>,
        signals: List<SleepSignal>
    ): String {
        val sb = StringBuilder()

        sb.append("ALARMS\n")
        sb.append("id,hour,minute,label,enabled,scheduleType,daysOfWeek,missionType,")
        sb.append("snoozeMinutes,nextTriggerAt,nextTriggerAtIso,createdAt\n")
        alarms.forEach { a ->
            sb.append(
                row(
                    a.id, a.hour, a.minute, a.label, a.enabled, a.scheduleType, a.daysOfWeek,
                    a.missionType, a.snoozeMinutes, a.nextTriggerAt ?: "",
                    a.nextTriggerAt?.let { iso.format(Date(it)) } ?: "", a.createdAt
                )
            )
        }

        sb.append("\nEVENTS\n")
        sb.append("id,alarmId,type,scheduledFor,scheduledForIso,occurredAt,occurredAtIso,")
        sb.append("occurredElapsed,snoozeCount,timeToDismissMs,missionDurationMs,detail\n")
        events.forEach { e ->
            sb.append(
                row(
                    e.id, e.alarmId, e.type,
                    e.scheduledFor, iso.format(Date(e.scheduledFor)),
                    e.occurredAt, iso.format(Date(e.occurredAt)),
                    e.occurredElapsed, e.snoozeCount,
                    e.timeToDismissMs ?: "", e.missionDurationMs ?: "", e.detail ?: ""
                )
            )
        }

        sb.append("\nSLEEP_SIGNALS\n")
        sb.append("id,occurredAt,occurredAtIso,source\n")
        signals.forEach { s ->
            sb.append(row(s.id, s.occurredAt, iso.format(Date(s.occurredAt)), s.source))
        }

        return sb.toString()
    }

    private fun row(vararg cells: Any): String =
        cells.joinToString(",") { escape(it.toString()) } + "\n"

    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
