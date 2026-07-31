package com.example.alarmtracker.ui.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Fires when a countdown finishes: clears that timer's state and starts the alarm-like ring. */
class TimerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        when (intent.action) {
            ACTION_FIRE -> {
                if (id < 0) return
                val label = TimerController.labelOf(context, id)
                val action = TimerController.actionOf(context, id)
                TimerController.onFired(context, id)
                // Ring like an alarm (foreground service + full-screen lock-screen UI) instead of a
                // plain notification, which MIUI hides on the lock screen / in the foreground app.
                val ring = Intent(context, TimerRingService::class.java)
                    .setAction(TimerRingService.ACTION_START)
                    .putExtra(TimerRingService.EXTRA_TIMER_ID, id)
                    .putExtra(TimerRingService.EXTRA_LABEL, label)
                    .putExtra(TimerRingService.EXTRA_ACTION_PACKAGE, action?.first)
                    .putExtra(TimerRingService.EXTRA_ACTION_LABEL, action?.second)
                ContextCompat.startForegroundService(context, ring)
            }
            ACTION_STOP -> TimerRingService.stop(context)
        }
    }

    companion object {
        const val ACTION_FIRE = "com.example.alarmtracker.timer.FIRE"
        const val ACTION_STOP = "com.example.alarmtracker.timer.STOP"
        const val EXTRA_ID = "timer_id"
    }
}
