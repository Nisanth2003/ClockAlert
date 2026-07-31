package com.example.alarmtracker.friends

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * The slow "has anything happened?" channel — one relay check per paired friend, every 15 minutes,
 * and only while at least one friend exists. This is how a phone finds out that a friend has just
 * started sharing; once it knows, [FriendsSessionService] takes over at a much tighter cadence for
 * the length of that session and this worker goes back to costing nothing.
 *
 * KNOWN LIMIT, worth being straight about: a poll-based relay means up to ~15 minutes before this
 * device notices a session it wasn't told about in advance. Opening the Friends screen syncs
 * immediately, and once a session is running alerts arrive within about a minute. Making discovery
 * instant needs a real push channel (ntfy speaks UnifiedPush, which would be the natural upgrade)
 * — that's a transport change, and nothing above [FriendsTransport] would have to move.
 */
class FriendsSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            FriendsSync.syncOnce(applicationContext)
            FriendsSessionService.syncRunState(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

object FriendsSyncScheduler {

    private const val WORK = "friends_sync"
    private const val INTERVAL_MINUTES = 15L

    /** Enqueues the periodic check while any friend is paired; cancels it once none are. */
    suspend fun apply(context: Context) {
        val ctx = context.applicationContext
        if (FriendsRepository.get(ctx).friends().isEmpty()) {
            cancel(ctx)
            return
        }
        val request = PeriodicWorkRequestBuilder<FriendsSyncWorker>(
            INTERVAL_MINUTES, TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK)
    }
}
