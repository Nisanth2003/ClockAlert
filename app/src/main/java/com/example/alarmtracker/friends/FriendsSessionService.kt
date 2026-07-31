package com.example.alarmtracker.friends

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.alarmtracker.AlarmTrackerApp
import com.example.alarmtracker.R
import com.example.alarmtracker.ui.friends.FriendsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Runs ONLY while a share session is open in either direction, and stops itself the moment none is.
 * Two jobs while it lives: keep the relay checked often enough that a crossing lands within about a
 * minute, and — the part that matters most — put a permanent, unmissable notification on screen for
 * as long as this phone is sharing its location.
 *
 * That notification is not decoration. Location sharing that can run invisibly is the thing this
 * feature must never become, so sharing is always visible, always attributable to specific people,
 * and always one tap from off.
 */
class FriendsSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SHARING) {
            // Go foreground first even on the way out: if the process was restarted to deliver this
            // action, Android still expects a notification within a few seconds of the service
            // starting, and stopping asynchronously would otherwise trip that.
            startForeground(buildNotification(sharingCount = 0, watchingCount = 0))
            scope.launch {
                val repo = FriendsRepository.get(applicationContext)
                repo.friends().filter { it.shareUntil > 0 }.forEach { friend ->
                    repo.stopSharing(friend)
                    FriendGeofences.disarmAll(applicationContext, friend.id)
                }
                stopEverything()
            }
            return START_NOT_STICKY
        }
        startForeground(buildNotification(sharingCount = 0, watchingCount = 0))
        if (loop == null) loop = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        val repo = FriendsRepository.get(applicationContext)
        while (true) {
            val now = System.currentTimeMillis()
            repo.expireFinishedShares(now)
            val friends = repo.friends()
            val sharing = friends.count { it.isSharingAt(now) }
            val watching = friends.count { FriendsSync.friendIsSharing(applicationContext, it.id, now) }
            if (sharing == 0 && watching == 0) {
                stopEverything()
                return
            }
            startForeground(buildNotification(sharing, watching))
            FriendsSync.syncOnce(applicationContext)
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun stopEverything() {
        loop?.cancel()
        loop = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForeground(notification: Notification) {
        // Android 14 rejects a "location" foreground service unless the location permission is
        // actually held at this moment. When we're only watching a friend we never touch location
        // at all, so declare the honest type instead of crashing on a permission we don't need.
        val type = when {
            Build.VERSION.SDK_INT < 34 -> 0
            FriendGeofences.hasLocationPermission(this) ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        try {
            ServiceCompat.startForeground(
                this, FriendAlerts.SESSION_NOTIFICATION_ID, notification, type
            )
        } catch (e: Exception) {
            // Never let a foreground-service restriction take the whole app down; the periodic
            // worker still delivers alerts, just less promptly.
            android.util.Log.w("FriendsSession", "startForeground refused", e)
            stopSelf()
        }
    }

    private fun buildNotification(sharingCount: Int, watchingCount: Int): Notification {
        val text = when {
            sharingCount > 0 && watchingCount > 0 ->
                getString(R.string.friend_session_both, sharingCount, watchingCount)
            sharingCount > 0 -> resources.getQuantityString(
                R.plurals.friend_session_sharing, sharingCount, sharingCount
            )
            else -> resources.getQuantityString(
                R.plurals.friend_session_watching, watchingCount, watchingCount
            )
        }
        val open = PendingIntent.getActivity(
            this, RC_OPEN,
            Intent(this, FriendsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, AlarmTrackerApp.CHANNEL_FRIENDS)
            .setSmallIcon(R.drawable.ic_friends)
            .setContentTitle(getString(R.string.friend_session_title))
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
        if (sharingCount > 0) {
            // Stopping must always be reachable without hunting through the app.
            val stop = PendingIntent.getService(
                this, RC_STOP,
                Intent(this, FriendsSessionService::class.java).setAction(ACTION_STOP_SHARING),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_close, getString(R.string.friend_stop_sharing), stop)
        }
        return builder.build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP_SHARING = "com.example.alarmtracker.friends.STOP_SHARING"
        private const val POLL_INTERVAL_MS = 60_000L

        // Distinct from every other PendingIntent request code in the app (see AlarmScheduler,
        // AlarmRingService 200s, AlarmReceiver 300, PreflightWorker 400s, TimerRingService 500s).
        private const val RC_OPEN = 600
        private const val RC_STOP = 601

        /** Starts the service if something is live, stops it if nothing is. Safe to call often. */
        suspend fun syncRunState(context: Context) {
            if (FriendsSync.hasActiveSession(context)) start(context) else stop(context)
        }

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context.applicationContext, FriendsSessionService::class.java)
                )
            }
        }

        fun stop(context: Context) {
            context.applicationContext.stopService(
                Intent(context.applicationContext, FriendsSessionService::class.java)
            )
        }
    }
}
