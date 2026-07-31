package com.example.alarmtracker.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.example.alarmtracker.MainActivity
import com.example.alarmtracker.R
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.util.Format
import com.example.alarmtracker.util.WakeStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Home-screen widget (feature 3) showing the next alarm time/label and the current
 * no-snooze streak. Updates are event-driven: [refresh] is called from the scheduler
 * whenever an alarm is saved, toggled, deleted or dismissed. No periodic polling.
 */
class NextAlarmWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                renderInto(context, appWidgetManager, appWidgetIds)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000

        /** Re-renders every placed widget. Safe to call from any background context. */
        suspend fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, NextAlarmWidget::class.java))
            if (ids.isEmpty()) return
            renderInto(context, mgr, ids)
        }

        private suspend fun renderInto(
            context: Context,
            mgr: AppWidgetManager,
            ids: IntArray
        ) {
            val repo = AlarmRepository.get(context)
            val next = repo.getNextEnabled()
            val streak = WakeStats.streak(
                repo.dismissalsSince(System.currentTimeMillis() - THIRTY_DAYS_MS)
            )

            val views = RemoteViews(context.packageName, R.layout.widget_next_alarm)
            if (next == null) {
                views.setTextViewText(R.id.widget_time, context.getString(R.string.widget_no_alarm))
                views.setViewVisibility(R.id.widget_label, View.GONE)
            } else {
                views.setTextViewText(
                    R.id.widget_time,
                    Format.timeText(context, next.hour, next.minute)
                )
                val label = if (next.label.isBlank()) {
                    Format.repeatSummary(context, next)
                } else {
                    "${next.label} · ${Format.repeatSummary(context, next)}"
                }
                views.setViewVisibility(R.id.widget_label, View.VISIBLE)
                views.setTextViewText(R.id.widget_label, label)
            }

            if (streak > 0) {
                views.setViewVisibility(R.id.widget_streak, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_streak,
                    context.getString(R.string.widget_streak_fmt, streak)
                )
            } else {
                views.setViewVisibility(R.id.widget_streak, View.GONE)
            }

            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)

            mgr.updateAppWidget(ids, views)
        }
    }
}
