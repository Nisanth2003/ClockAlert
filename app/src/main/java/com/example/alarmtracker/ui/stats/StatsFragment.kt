package com.example.alarmtracker.ui.stats

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.alarmtracker.R
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.data.SleepSignal
import com.example.alarmtracker.databinding.FragmentStatsBinding
import com.example.alarmtracker.ui.health.HealthCheckActivity
import com.example.alarmtracker.ui.postmortem.PostmortemActivity
import com.example.alarmtracker.util.Prefs
import com.example.alarmtracker.util.ShareUtil
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialFadeThrough
import java.util.Locale
import kotlinx.coroutines.launch

class StatsFragment : Fragment(), com.example.alarmtracker.ui.common.TabMenuHost {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: StatsViewModel by viewModels()
    private var lastState: StatsUiState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    // Action items live in the activity's shared top bar; see TabMenuHost.
    override val tabMenuRes: Int get() = R.menu.stats_menu

    override fun onTabMenuItemSelected(itemId: Int): Boolean = when (itemId) {
        R.id.action_share_stats -> { shareStats(); true }
        R.id.action_log_bedtime -> { logBedtime(); true }
        R.id.action_postmortem -> {
            startActivity(Intent(requireContext(), PostmortemActivity::class.java)); true
        }
        R.id.action_health -> {
            startActivity(Intent(requireContext(), HealthCheckActivity::class.java)); true
        }
        else -> false
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rangeGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val range = when (checkedIds.firstOrNull()) {
                R.id.chip_month -> StatsRange.MONTH
                R.id.chip_3_months -> StatsRange.THREE_MONTHS
                else -> StatsRange.WEEK
            }
            viewModel.setRange(range)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    if (state != null) bindState(state)
                }
            }
        }
    }

    private fun shareStats() {
        val state = lastState
        val text = when {
            state?.score != null -> getString(
                R.string.share_stats_fmt, state.score, state.streak
            )
            state != null -> getString(R.string.share_stats_no_score, state.streak)
            else -> getString(R.string.share_stats_no_score, 0)
        }
        ShareUtil.shareText(requireContext(), getString(R.string.action_share_stats), text)
    }

    private fun logBedtime() {
        val ctx = requireContext().applicationContext
        lifecycleScope.launch {
            AlarmRepository.get(ctx).recordSleepSignal(SleepSignal.SOURCE_MANUAL)
            _binding?.let {
                Snackbar.make(it.root, R.string.bedtime_logged, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindState(state: StatsUiState) {
        val b = _binding ?: return
        lastState = state
        val empty = !state.hasData
        b.statsEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
        b.statsScroll.visibility = if (empty) View.INVISIBLE else View.VISIBLE
        if (empty) return

        b.scoreValue.text = state.score?.toString() ?: "—"
        b.scoreExplanation.setText(state.explanationRes)
        b.statAvgSnoozes.text = state.avgSnoozes
        b.statOnTime.text = state.onTimePercent
            ?.let { getString(R.string.percent_fmt, it) } ?: "—"
        b.statStreak.text = getString(R.string.streak_days_fmt, state.streak)

        b.wakeChart.setData(WakeChartView.Mode.LINE, state.wakePoints) { minutesOfDay ->
            formatMinutesOfDay(minutesOfDay)
        }
        b.snoozeChart.setData(WakeChartView.Mode.BAR, state.snoozePoints) { count ->
            count.toInt().toString()
        }
        b.mostSnoozed.text = state.mostSnoozed
            ?.let { getString(R.string.most_snoozed_fmt, it) }
            ?: getString(R.string.most_snoozed_none)

        b.coachingBudget.text = if (state.snoozeBudget > 0) {
            getString(R.string.coaching_budget_fmt, state.snoozesThisWeek, state.snoozeBudget)
        } else {
            getString(R.string.coaching_budget_off_fmt, state.snoozesThisWeek)
        }
        b.coachingReward.text = state.rewardRes?.let { getString(it) }
            ?: getString(R.string.coaching_no_reward)
    }

    private fun formatMinutesOfDay(value: Float): String {
        val total = value.toInt().coerceIn(0, 24 * 60 - 1)
        val h = total / 60
        val m = total % 60
        return if (Prefs.is24Hour(requireContext())) {
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
