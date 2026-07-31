package com.example.alarmtracker.notif

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
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

    private fun component(context: Context) =
        ComponentName(context.applicationContext, AlarmNotificationListener::class.java)

    /**
     * Ask the OS to bind our listener now.
     *
     * Access being granted does NOT mean the service is running: Android binds notification listeners
     * lazily, and an aggressive OEM (MIUI in particular) will unbind ours whenever it cleans up the
     * process — after which it may stay unbound indefinitely. The symptom is the UI sitting on
     * "connecting" forever and no notification ever reaching us, which reads exactly like a broken
     * feature. [requestRebind] is the documented remedy and is cheap to call; the OS ignores it when the
     * listener is already connected or access isn't granted.
     */
    fun requestRebind(context: Context) {
        if (!isGranted(context)) return
        try {
            NotificationListenerService.requestRebind(component(context))
        } catch (_: Exception) {
            // Nothing to do — the status UI already tells the user it isn't connected.
        }
    }

    /** Granted, but the OS hasn't bound the service — the state worth acting on rather than showing. */
    fun grantedButNotConnected(context: Context): Boolean =
        isGranted(context) && !AlarmNotificationListener.isConnected()
}
