package com.example.alarmtracker.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.example.alarmtracker.AlarmTrackerApp
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.data.SleepSignal
import java.util.Calendar
import kotlinx.coroutines.launch

/**
 * Zero-permission lock-status sleep proxy (the fallback the user asked for): while the app process
 * is alive, a night-time screen-off is recorded as a "went to bed" signal and a morning unlock as a
 * "woke up" signal — all in the device's LOCAL time. If the phone stays off/locked through the
 * night, the gap between them is that night's sleep opportunity.
 *
 * SCREEN_OFF / USER_PRESENT can't be declared in the manifest, so this is registered dynamically in
 * [AlarmTrackerApp]. Throttled to one signal per window so brief night glances don't spam the log.
 */
class ScreenSleepReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val app = context.applicationContext as? AlarmTrackerApp ?: return
        when (intent.action) {
            // Screen off late evening / overnight → bedtime proxy.
            Intent.ACTION_SCREEN_OFF -> if (hour >= NIGHT_START || hour < NIGHT_END) {
                record(app, SleepSignal.SOURCE_SCREEN_OFF)
            }
            // First unlock in the morning → wake proxy.
            Intent.ACTION_USER_PRESENT -> if (hour in MORNING_START until MORNING_END) {
                record(app, SleepSignal.SOURCE_SCREEN_ON)
            }
        }
    }

    private fun record(app: AlarmTrackerApp, source: String) {
        app.applicationScope.launch {
            val repo = AlarmRepository.get(app)
            val latest = repo.latestSleepSignalOfSource(source)
            val now = System.currentTimeMillis()
            // Keep the FIRST screen-off of the night (earliest bedtime) and FIRST morning unlock.
            if (latest == null || now - latest.occurredAt > THROTTLE_MS) {
                repo.recordSleepSignal(source, now)
            }
        }
    }

    companion object {
        private const val NIGHT_START = 21   // 9 PM local
        private const val NIGHT_END = 4      // until 4 AM
        private const val MORNING_START = 4
        private const val MORNING_END = 11
        private const val THROTTLE_MS = 90L * 60 * 1000 // 90 min

        fun register(app: AlarmTrackerApp) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            app.registerReceiver(ScreenSleepReceiver(), filter)
        }
    }
}
