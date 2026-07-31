package com.example.alarmtracker.notif

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Grant-flow helpers for the notification-listener source. The listener needs the user to enable
 * "Notification access" in system settings; this is separate from POST_NOTIFICATIONS and cannot be
 * requested with a normal runtime-permission dialog — it only deep-links to the settings screen.
 *
 * Everything that reads notifications is gated behind [isGranted]; when it is false the event alarm
 * degrades cleanly to the pure ETA fallback (it still rings at the estimate).
 */
object NotificationAccess {

    /** True when this app is in the enabled notification-listener set (access granted). */
    fun isGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    /** Deep-link to the system "Notification access" screen where the user flips the toggle. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
}
