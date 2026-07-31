package com.example.alarmtracker.scheduling

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.alarmtracker.util.Prefs
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules the nightly pre-flight worker (feature 4) as unique periodic WorkManager
 * work that first runs at a fixed evening time and repeats daily. WorkManager is used
 * only for the reassurance/warning notification — never to fire the actual alarm.
 */
object PreflightScheduler {

    private const val WORK_NAME = "preflight_check"
    private const val EVENING_HOUR = 21 // 9 PM local

    /** Enables or cancels the nightly check to match the current setting. */
    fun apply(context: Context) {
        if (Prefs.preflightEnabled(context)) schedule(context) else cancel(context)
    }

    private fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<PreflightWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayToEveningMs(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** Milliseconds from now until the next [EVENING_HOUR]:00 local time. */
    private fun delayToEveningMs(): Long {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, EVENING_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}
