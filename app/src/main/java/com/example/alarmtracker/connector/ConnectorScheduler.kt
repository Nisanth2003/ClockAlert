package com.example.alarmtracker.connector

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.alarmtracker.util.Prefs
import java.util.concurrent.TimeUnit

/**
 * Owns the periodic connector poll. [apply] enqueues it only while at least one connector is
 * linked (and cancels it otherwise), at the user's chosen interval. Call it on app start and
 * whenever a connector is connected/disconnected or the interval changes. WorkManager persists
 * the schedule across reboots on its own.
 */
object ConnectorScheduler {

    private const val WORK = "connector_poll"

    fun apply(context: Context) {
        val ctx = context.applicationContext
        if (!ConnectorRegistry.anyConnected(ctx)) {
            cancel(ctx)
            return
        }
        val hours = Prefs.connectorIntervalHours(ctx).coerceAtLeast(1L)
        val request = PeriodicWorkRequestBuilder<ConnectorPollWorker>(hours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK)
    }
}
