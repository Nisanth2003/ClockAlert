package com.example.alarmtracker.connector

import android.content.Context
import android.util.Log
import com.example.alarmtracker.util.Dbg
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Background poll for every connected service. Wakes on its schedule, turns near-term items into
 * alarms via [ConnectorAlarmSync], and goes back to sleep — no continuous/foreground work, so it
 * costs almost nothing. A failed connector asks WorkManager to retry (respects backoff + Doze).
 */
class ConnectorPollWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        var anyFailure = false
        for (connector in ConnectorRegistry.all) {
            if (!connector.isConnected(applicationContext)) continue
            try {
                val items = connector.poll(applicationContext)
                val n = ConnectorAlarmSync.sync(applicationContext, connector, items)
                Dbg.d(TAG) { "polled ${connector.id}: ${items.size} items, $n alarms set" }
            } catch (e: Exception) {
                Log.w(TAG, "poll failed for ${connector.id}", e)
                anyFailure = true
            }
        }
        return if (anyFailure) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "ConnectorPoll"
    }
}
