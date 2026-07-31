package com.example.alarmtracker.scheduling

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Re-registers the next alarm after events that invalidate AlarmManager state
 * or the materialized nextTriggerAt values.
 */
class RescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val relevant = action in setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        )
        if (!relevant) return

        val recomputeClock = action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED

        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (recomputeClock) {
                    AlarmScheduler.recomputeAllAndReschedule(context)
                } else {
                    // Boot / update / exact-alarm-permission change: heal stale triggers
                    // and refresh calendar-aware alarms against the current calendar.
                    AlarmScheduler.rescheduleNext(context)
                    AlarmScheduler.recomputeCalendarAndReschedule(context)
                    // Geofences and the event alarms' OS registrations are lost on reboot —
                    // re-arm every enabled event trigger (fallback + geofences).
                    EventAlarmCoordinator.reregisterAll(context)
                }
            } finally {
                result.finish()
            }
        }
    }
}
