package com.example.alarmtracker.scheduling

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.alarmtracker.AlarmTrackerApp
import com.example.alarmtracker.MainActivity
import com.example.alarmtracker.R
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.ui.health.HealthCheckActivity
import com.example.alarmtracker.util.Format
import com.example.alarmtracker.util.Reliability

/**
 * Nightly pre-flight alarm health check (feature 4). Verifies the next alarm is
 * registered and every reliability condition is met, then posts a reassuring or a
 * warning notification. It NEVER fires the actual alarm — that stays with
 * AlarmManager; this only reports status.
 */
class PreflightWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val repo = AlarmRepository.get(context)
        val next = repo.getNextEnabled()
        val problems = Reliability.problems(context)

        val builder = NotificationCompat.Builder(context, AlarmTrackerApp.CHANNEL_PREFLIGHT)
            .setSmallIcon(R.drawable.ic_alarm)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        when {
            next == null -> {
                builder.setContentTitle(context.getString(R.string.preflight_no_alarm_title))
                    .setContentText(context.getString(R.string.preflight_no_alarm_body))
                    .setContentIntent(openApp(context))
            }
            problems.isEmpty() -> {
                val time = Format.timeText(context, next.hour, next.minute)
                builder.setContentTitle(context.getString(R.string.preflight_ok_title))
                    .setContentText(context.getString(R.string.preflight_ok_fmt, time))
                    .setContentIntent(openApp(context))
            }
            else -> {
                val list = problems.joinToString(", ") { context.getString(it.titleRes) }
                builder.setContentTitle(context.getString(R.string.preflight_warn_title))
                    .setContentText(context.getString(R.string.preflight_warn_fmt, list))
                    .setStyle(
                        NotificationCompat.BigTextStyle()
                            .bigText(context.getString(R.string.preflight_warn_fmt, list))
                    )
                    .setContentIntent(openHealth(context))
            }
        }

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied — nothing to show; work still succeeds.
        }
        return Result.success()
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 400, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openHealth(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 401,
            Intent(context, HealthCheckActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private companion object {
        const val NOTIFICATION_ID = 3
    }
}
