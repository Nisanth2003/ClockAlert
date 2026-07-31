package com.example.alarmtracker.ui.postmortem

import android.content.Context
import android.os.SystemClock
import com.example.alarmtracker.R
import com.example.alarmtracker.data.AlarmEvent
import com.example.alarmtracker.util.Format
import com.example.alarmtracker.util.Reliability

/**
 * Turns forensic problem events (MISSED / FIRED_LATE) into human diagnoses,
 * combining what the event log recorded with the device's current reliability
 * state. OEM process-kills are phrased as "likely cause," never definitive.
 */
object PostmortemAnalyzer {

    data class Incident(
        val whenText: String,
        val headlineRes: Int,
        val isMissed: Boolean,
        /** Ordered, already-localized diagnosis sentences. */
        val causes: List<String>
    )

    fun analyze(context: Context, events: List<AlarmEvent>): List<Incident> {
        // Snapshot current device state once (used as "contributing cause" evidence).
        val problems = Reliability.problems(context).map { it.id }.toSet()
        val oem = Reliability.oemGuidance(context)
        // Wall-clock instant the device last booted; a scheduled time before this
        // means the phone was powered off when the alarm should have rung.
        val bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()

        return events.map { event ->
            val isMissed = event.type == AlarmEvent.TYPE_MISSED
            val causes = mutableListOf<String>()

            // ---- Primary cause from the log ----
            var explained = false
            if (event.type == AlarmEvent.TYPE_FIRED_LATE) {
                val lateMs = parseLateMs(event.detail) ?: (event.occurredAt - event.scheduledFor)
                causes += context.getString(
                    R.string.postmortem_cause_late_fmt,
                    Format.untilText(context, lateMs.coerceAtLeast(0))
                )
                explained = true
            } else if (isMissed) {
                when {
                    event.scheduledFor < bootAt -> {
                        causes += context.getString(R.string.postmortem_cause_phone_off)
                        explained = true
                    }
                    event.detail == "ring_timeout" -> {
                        causes += context.getString(R.string.postmortem_cause_rang_out)
                        explained = true
                    }
                }
            }

            // ---- Contributing current-state causes ----
            val contributing = contributingCauses(context, event.type, problems)
            causes += contributing

            // ---- Fallbacks ----
            if (!explained && contributing.isEmpty()) {
                if (isMissed && oem != null) {
                    causes += context.getString(
                        R.string.postmortem_cause_oem_fmt, context.getString(oem.nameRes)
                    )
                } else {
                    causes += context.getString(R.string.postmortem_cause_unknown)
                }
            }

            Incident(
                whenText = context.getString(
                    R.string.postmortem_when_fmt,
                    Format.dateTimeLine(context, event.occurredAt),
                    Format.dateTimeLine(context, event.scheduledFor)
                ),
                headlineRes = if (isMissed) {
                    R.string.postmortem_incident_missed
                } else {
                    R.string.postmortem_incident_late
                },
                isMissed = isMissed,
                causes = causes
            )
        }
    }

    private fun contributingCauses(
        context: Context,
        type: String,
        problems: Set<Reliability.Id>
    ): List<String> {
        // For late alarms only permission/battery issues are plausible contributors;
        // for missed alarms any unmet condition could be the culprit.
        val relevant = if (type == AlarmEvent.TYPE_FIRED_LATE) {
            listOf(Reliability.Id.EXACT_ALARM, Reliability.Id.BATTERY_OPT)
        } else {
            listOf(
                Reliability.Id.EXACT_ALARM, Reliability.Id.NOTIFICATIONS,
                Reliability.Id.FULL_SCREEN_INTENT, Reliability.Id.ALARM_VOLUME,
                Reliability.Id.BATTERY_OPT, Reliability.Id.DND
            )
        }
        return relevant.filter { it in problems }.map { context.getString(causeRes(it)) }
    }

    private fun causeRes(id: Reliability.Id): Int = when (id) {
        Reliability.Id.EXACT_ALARM -> R.string.postmortem_cause_exact
        Reliability.Id.NOTIFICATIONS -> R.string.postmortem_cause_notifications
        Reliability.Id.FULL_SCREEN_INTENT -> R.string.postmortem_cause_fsi
        Reliability.Id.OVERLAY -> R.string.postmortem_cause_fsi
        Reliability.Id.ALARM_VOLUME -> R.string.postmortem_cause_volume
        Reliability.Id.BATTERY_OPT -> R.string.postmortem_cause_battery
        Reliability.Id.DND -> R.string.postmortem_cause_dnd
        // Never reached: LISTENER_BOUND is not in any `relevant` list above, because an unbound listener
        // explains an alarm that never fired at all rather than one that fired late or was missed. Kept
        // exhaustive so adding a future Id is a compile error here rather than a silent gap.
        Reliability.Id.LISTENER_BOUND -> R.string.health_listener_problem
    }

    private fun parseLateMs(detail: String?): Long? =
        detail?.substringAfter("late_ms=", "")?.toLongOrNull()
}
