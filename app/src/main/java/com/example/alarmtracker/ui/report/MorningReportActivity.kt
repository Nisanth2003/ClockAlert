package com.example.alarmtracker.ui.report

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.alarmtracker.R
import com.example.alarmtracker.data.AlarmEvent
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.databinding.ActivityMorningReportBinding
import com.example.alarmtracker.ui.stats.WakeChartView
import com.example.alarmtracker.util.Format
import com.example.alarmtracker.util.Prefs
import com.example.alarmtracker.util.ShareUtil
import com.example.alarmtracker.util.SleepEstimate
import com.example.alarmtracker.util.WakeStats
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Post-dismiss Morning Report Card (feature 2). Non-blocking and skippable — the
 * dismiss already completed before this opens; Done/back simply close it. Shows
 * today's wake vs the 30-day average, the no-snooze streak, a consistency sparkline,
 * an honest sleep-opportunity estimate (feature 6) and one insight. Shareable (feature 8).
 */
class MorningReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMorningReportBinding
    private var shareText: String = ""

    private data class Report(
        val wakeTime: String,
        val vsAverage: String,
        val streak: Int,
        val streakText: String,
        val sleepText: String,
        val hasSleepEstimate: Boolean,
        val insight: String,
        val points: List<WakeChartView.Point>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMorningReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.morningRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, right = bars.right)
            binding.reportScroll.updatePadding(top = bars.top)
            binding.reportActions.updatePadding(bottom = bars.bottom + resources.getDimensionPixelSize(R.dimen.screen_margin))
            insets
        }

        binding.reportDone.setOnClickListener { finish() }
        binding.reportShare.setOnClickListener {
            ShareUtil.shareViewImage(
                this, binding.reportCard, getString(R.string.report_share_title), shareText
            )
        }

        val wakeAt = intent.getLongExtra(EXTRA_WAKE_AT, System.currentTimeMillis())
        val snoozeCount = intent.getIntExtra(EXTRA_SNOOZE_COUNT, 0)
        load(wakeAt, snoozeCount)
    }

    private fun load(wakeAt: Long, snoozeCount: Int) {
        lifecycleScope.launch {
            val report = withContext(Dispatchers.Default) { buildReport(wakeAt, snoozeCount) }
            bind(report)
        }
    }

    private suspend fun buildReport(wakeAt: Long, snoozeCount: Int): Report {
        val repo = AlarmRepository.get(applicationContext)
        val dismissals = repo.dismissalsSince(wakeAt - THIRTY_DAYS_MS)

        // Ensure the just-completed wake is represented even if its row hasn't landed yet.
        val hasToday = dismissals.any { abs(it.occurredAt - wakeAt) < 60_000 }
        val synthetic = AlarmEvent(
            alarmId = 0, type = AlarmEvent.TYPE_DISMISSED, scheduledFor = wakeAt,
            occurredAt = wakeAt, occurredElapsed = 0, snoozeCount = snoozeCount
        )
        val effective = if (hasToday) dismissals else listOf(synthetic) + dismissals

        val streak = WakeStats.streak(effective)
        val todayMin = WakeStats.minutesOfDay(wakeAt)
        val todayKey = dayKey(wakeAt)
        val priorAvg = WakeStats.averageWakeMinutes(
            effective.filter { dayKey(it.occurredAt) != todayKey }
        )

        val vsAverage = when {
            priorAvg == null -> getString(R.string.report_vs_average_none)
            else -> {
                val diff = (todayMin - priorAvg).roundToInt()
                when {
                    abs(diff) < 3 -> getString(R.string.report_vs_average_same)
                    diff < 0 -> getString(
                        R.string.report_vs_average_earlier_fmt,
                        Format.untilText(this, abs(diff) * 60_000L)
                    )
                    else -> getString(
                        R.string.report_vs_average_later_fmt,
                        Format.untilText(this, diff * 60_000L)
                    )
                }
            }
        }

        val streakText = if (streak > 0) {
            getString(R.string.report_streak_fmt, streak)
        } else {
            getString(R.string.report_streak_zero)
        }

        val bedtime = repo.bedtimeSignalFor(wakeAt, SleepEstimate.WINDOW_MS)?.occurredAt
        val sleepMs = SleepEstimate.opportunityMs(bedtime, wakeAt)
        val hasSleep = sleepMs != null
        val sleepText = if (sleepMs != null) {
            getString(R.string.sleep_estimate_fmt, Format.untilText(this, sleepMs))
        } else {
            getString(R.string.sleep_estimate_none)
        }

        val insight = getString(
            when {
                snoozeCount > 0 -> R.string.report_insight_snoozed
                streak >= 3 -> R.string.report_insight_streak
                priorAvg != null && todayMin <= priorAvg - 5 -> R.string.report_insight_early
                priorAvg != null && todayMin >= priorAvg + 20 -> R.string.report_insight_late
                else -> R.string.report_insight_neutral
            }
        )

        // Sparkline: average wake minute-of-day per calendar day, last 14 days.
        val points = effective
            .groupBy { dayKey(it.occurredAt) }
            .toSortedMap()
            .toList()
            .takeLast(14)
            .map { (key, dayEvents) ->
                WakeChartView.Point(
                    dayLabel(key),
                    dayEvents.map { e -> WakeStats.minutesOfDay(e.occurredAt).toFloat() }
                        .average().toFloat()
                )
            }

        return Report(
            wakeTime = Format.timeText(this, hourOf(wakeAt), minuteOf(wakeAt)),
            vsAverage = vsAverage,
            streak = streak,
            streakText = streakText,
            sleepText = sleepText,
            hasSleepEstimate = hasSleep,
            insight = insight,
            points = points
        )
    }

    private fun bind(report: Report) {
        binding.reportWakeTime.text = report.wakeTime
        binding.reportVsAverage.text = report.vsAverage
        binding.reportStreak.text = report.streakText
        binding.reportSleep.text = report.sleepText
        binding.reportSleepExplainer.visibility =
            if (report.hasSleepEstimate) View.VISIBLE else View.GONE
        binding.reportInsight.text = report.insight
        binding.reportChart.setData(WakeChartView.Mode.LINE, report.points) { minutesOfDay ->
            formatMinutesOfDay(minutesOfDay)
        }

        shareText = getString(
            R.string.share_report_fmt,
            getString(R.string.title_morning_report),
            report.wakeTime,
            report.streakText,
            report.insight
        )
    }

    private fun formatMinutesOfDay(value: Float): String {
        val total = value.toInt().coerceIn(0, 24 * 60 - 1)
        val h = total / 60
        val m = total % 60
        return if (Prefs.is24Hour(this)) {
            String.format(Locale.getDefault(), "%02d:%02d", h, m)
        } else {
            val h12 = when {
                h == 0 -> 12
                h > 12 -> h - 12
                else -> h
            }
            String.format(Locale.getDefault(), "%d:%02d", h12, m)
        }
    }

    private fun dayKey(ts: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return c.get(Calendar.YEAR) * 10_000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    private fun dayLabel(key: Int): String =
        String.format(Locale.getDefault(), "%d/%d", key % 100, key / 100 % 100)

    private fun hourOf(ts: Long) =
        Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.HOUR_OF_DAY)

    private fun minuteOf(ts: Long) =
        Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.MINUTE)

    companion object {
        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_WAKE_AT = "extra_wake_at"
        const val EXTRA_SNOOZE_COUNT = "extra_snooze_count"
        private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }
}
