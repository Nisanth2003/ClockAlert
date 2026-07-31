package com.example.alarmtracker.friends

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.alarmtracker.AlarmTrackerApp
import com.example.alarmtracker.R
import com.example.alarmtracker.data.Friend
import com.example.alarmtracker.data.FriendWatch
import com.example.alarmtracker.ui.friends.FriendsActivity

/** The "your friend actually left the house" notification, plus the sharing status notification. */
object FriendAlerts {

    private const val CROSSING_ID_BASE = 7000

    /** Far above [CROSSING_ID_BASE] so a high watch id can never collide with the session status. */
    const val SESSION_NOTIFICATION_ID = 6999

    /**
     * A friend crossed one of the places you asked about. High priority and it alerts, because the
     * whole point is to save you standing outside for twenty minutes — but it's a notification, not
     * an alarm: it doesn't seize the screen or override silent mode.
     */
    /**
     * The one sentence that describes what just happened, shared by the notification and the
     * alarm-grade ring screen so both say exactly the same thing.
     */
    fun crossingText(
        context: Context,
        friend: Friend,
        watch: FriendWatch,
        message: FriendMessage.Crossing
    ): String = if (message.condition == FriendWatch.CONDITION_LEAVES) {
        context.getString(R.string.friend_alert_left, friend.name, watch.placeName)
    } else if (watch.radiusM <= NEARBY_RADIUS_M) {
        context.getString(R.string.friend_alert_nearby, friend.name, watch.placeName, watch.radiusM)
    } else {
        context.getString(R.string.friend_alert_arrived, friend.name, watch.placeName)
    }

    fun notifyCrossing(
        context: Context,
        friend: Friend,
        watch: FriendWatch,
        message: FriendMessage.Crossing
    ) {
        val text = crossingText(context, friend, watch, message)
        val open = PendingIntent.getActivity(
            context, CROSSING_ID_BASE + watch.id.toInt(),
            Intent(context, FriendsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AlarmTrackerApp.CHANNEL_FRIENDS)
            .setSmallIcon(R.drawable.ic_friends)
            .setContentTitle(friend.name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context)
                .notify(CROSSING_ID_BASE + watch.id.toInt(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — nothing else to do.
        }
    }

    /**
     * "<name> is asking where you are" — the receiving end of [FriendsRepository.nudge].
     *
     * A notification, never a ring: being asked is not an emergency, and a poke that could seize a
     * child's screen would be the wrong power to hand anyone. Tapping it opens the People screen,
     * where sharing is one tap away if they want to answer with their position.
     */
    fun notifyNudge(context: Context, friend: Friend) {
        val text = context.getString(R.string.friend_nudge_received, friend.name)
        val open = PendingIntent.getActivity(
            context, NUDGE_ID,
            Intent(context, FriendsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AlarmTrackerApp.CHANNEL_FRIENDS)
            .setSmallIcon(R.drawable.ic_friends)
            .setContentTitle(friend.name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NUDGE_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — nothing else to do.
        }
    }

    /** Radius at or below which we phrase the alert as "within N m" rather than "arrived at". */
    const val NEARBY_RADIUS_M = 200

    /** Well clear of [CROSSING_ID_BASE] + watch ids and of the session notification. */
    private const val NUDGE_ID = 6998
}
