package com.example.alarmtracker.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.alarmtracker.R
import com.example.alarmtracker.data.AlarmEvent
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.util.Format
import com.example.alarmtracker.util.Prefs
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class StatsRange(val days: Int) { WEEK(7), MONTH(30), THREE_MONTHS(90) }

data class StatsUiState(
    val range: StatsRange,
    val hasData: Boolean,
    val score: Int?,
    val explanationRes: Int,
    val avgSnoozes: String,
    val onTimePercent: Int?,
    val streak: Int,
    val wakePoints: List<WakeChartView.Point>,
    val snoozePoints: List<WakeChartView.Point>,
    val mostSnoozed: String?,
    /** Snooze-coaching card: snoozes used in the last 7 days and the global weekly budget (0 = off). */
    val snoozesThisWeek: Int,
    val snoozeBudget: Int,
    /** Zero-snooze streak reward string res, or null when no milestone reached. */
    val rewardRes: Int?
)

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = AlarmRepository.get(application)

    private val range = MutableStateFlow(StatsRange.WEEK)

    val uiState: StateFlow<StatsUiState?> = range
        .flatMapLatest { r ->
            val since = System.currentTimeMillis() - r.days * DAY_MS
            repo.observeEventsSince(since).map { events -> buildState(r, events) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setRange(r: StatsRange) {
        range.value = r
    }

    private suspend fun buildState(r: StatsRange, events: List<AlarmEvent>): StatsUiState {
        val dismissals = events.filter { it.type == AlarmEvent.TYPE_DISMISSED }
        val snoozes = events.filter { it.type == AlarmEvent.TYPE_SNOOZED }
        val missed = events.filter { it.type == AlarmEvent.TYPE_MISSED }
        val hasData = events.any { it.type != AlarmEvent.TYPE_SCHEDULED }

        // ---- Wake Score (0–100), computed from three penalties: ----
        // 1. Wake-time variance vs. target: average dismissal delay in minutes
        //    (occurredAt − scheduledFor), 2 points per minute, capped at 50.
        // 2. Snooze penalty: average snooze count per wake, 12 points per snooze,
        //    capped at 30.
        // 3. Time-to-dismiss sluggishness: average seconds from ring to dismissal,
        //    1 point per 15 s, capped at 20.
        // Score = 100 − penalties, clamped to 0..100.
        val score: Int? = if (dismissals.isEmpty()) null else {
            val avgDelayMin = dismissals
                .map { ((it.occurredAt - it.scheduledFor).coerceAtLeast(0L)) / 60_000.0 }
                .average()
            val avgSnoozeCount = dismissals.map { it.snoozeCount.toDouble() }.average()
            val avgTtdSec = dismissals
                .mapNotNull { it.timeToDismissMs?.let { ms -> ms / 1000.0 } }
                .ifEmpty { listOf(0.0) }
                .average()
            val delayPenalty = (avgDelayMin * 2).coerceAtMost(50.0)
            val snoozePenalty = (avgSnoozeCount * 12).coerceAtMost(30.0)
            val ttdPenalty = (avgTtdSec / 15).coerceAtMost(20.0)
            (100 - delayPenalty - snoozePenalty - ttdPenalty).roundToInt().coerceIn(0, 100)
        }

        val explanationRes = when {
            score == null -> R.string.empty_stats_body
            score >= 85 -> R.string.score_explain_great
            score >= 65 -> R.string.score_explain_good
            score >= 40 -> R.string.score_explain_ok
            else -> R.string.score_explain_poor
        }

        val avgSnoozes = if (dismissals.isEmpty()) "—" else {
            String.format(Locale.getDefault(), "%.1f", dismissals.map { it.snoozeCount }.average())
        }

        // On-time: dismissed within 5 minutes of the scheduled time.
        val outcomes = dismissals.size + missed.size
        val onTimePercent = if (outcomes == 0) null else {
            val onTime = dismissals.count { it.occurredAt - it.scheduledFor <= 5 * 60_000L }
            (onTime * 100.0 / outcomes).roundToInt()
        }

        // Current zero-snooze streak: consecutive latest wakes without snoozing.
        val streak = dismissals.sortedByDescending { it.occurredAt }
            .takeWhile { it.snoozeCount == 0 }
            .count()

        // ---- Charts (per-day aggregation) ----
        val wakePoints = dismissals
            .groupBy { dayKey(it.occurredAt) }
            .toSortedMap()
            .map { (key, dayEvents) ->
                WakeChartView.Point(
                    dayLabel(key, r),
                    dayEvents.map { minutesOfDay(it.occurredAt) }.average().toFloat()
                )
            }

        val snoozePoints = snoozes
            .groupBy { dayKey(it.occurredAt) }
            .toSortedMap()
            .map { (key, dayEvents) ->
                WakeChartView.Point(dayLabel(key, r), dayEvents.size.toFloat())
            }

        // ---- Most snoozed alarm callout ----
        val mostSnoozed = snoozes.groupBy { it.alarmId }
            .maxByOrNull { it.value.size }
            ?.let { (alarmId, _) ->
                val alarm = repo.getAlarm(alarmId)
                if (alarm == null) null
                else {
                    val context = getApplication<Application>()
                    val time = Format.timeText(context, alarm.hour, alarm.minute)
                    if (alarm.label.isBlank()) time else "$time · ${alarm.label}"
                }
            }

        // ---- Snooze-coaching card ----
        val weekAgo = System.currentTimeMillis() - 7 * DAY_MS
        val snoozesThisWeek = snoozes.count { it.occurredAt >= weekAgo }
        val snoozeBudget = Prefs.weeklySnoozeBudget(getApplication<Application>())
        val rewardRes = when {
            streak >= 30 -> R.string.reward_streak_30
            streak >= 14 -> R.string.reward_streak_14
            streak >= 7 -> R.string.reward_streak_7
            streak >= 3 -> R.string.reward_streak_3
            else -> null
        }

        return StatsUiState(
            range = r,
            hasData = hasData,
            score = score,
            explanationRes = explanationRes,
            avgSnoozes = avgSnoozes,
            onTimePercent = onTimePercent,
            streak = streak,
            wakePoints = wakePoints,
            snoozePoints = snoozePoints,
            mostSnoozed = mostSnoozed,
            snoozesThisWeek = snoozesThisWeek,
            snoozeBudget = snoozeBudget,
            rewardRes = rewardRes
        )
    }

    /** yyyyMMdd sortable key for the local calendar day. */
    private fun dayKey(timestamp: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.YEAR) * 10_000 +
            (cal.get(Calendar.MONTH) + 1) * 100 +
            cal.get(Calendar.DAY_OF_MONTH)
    }

    private fun dayLabel(dayKey: Int, r: StatsRange): String {
        val year = dayKey / 10_000
        val month = dayKey / 100 % 100
        val day = dayKey % 100
        return if (r == StatsRange.WEEK) {
            val cal = Calendar.getInstance().apply { set(year, month - 1, day) }
            java.text.DateFormatSymbols.getInstance().shortWeekdays[cal.get(Calendar.DAY_OF_WEEK)]
        } else {
            String.format(Locale.getDefault(), "%d/%d", day, month)
        }
    }

    private fun minutesOfDay(timestamp: Long): Double {
        val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
        return cal.get(Calendar.HOUR_OF_DAY) * 60.0 + cal.get(Calendar.MINUTE) +
            cal.get(Calendar.SECOND) / 60.0
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
