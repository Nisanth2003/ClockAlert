package com.example.alarmtracker.ui.recycle

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.alarmtracker.data.Alarm
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.databinding.ActivityRecycleBinBinding
import com.example.alarmtracker.databinding.ItemRecycleBinding
import com.example.alarmtracker.R
import com.example.alarmtracker.scheduling.AlarmScheduler
import com.example.alarmtracker.scheduling.EventAlarmCoordinator
import com.example.alarmtracker.ui.timer.TimerController
import com.example.alarmtracker.ui.timer.TimerItem
import com.example.alarmtracker.util.Format
import java.util.Locale
import kotlinx.coroutines.launch

/** Recently-deleted alarms + timers, restorable within their retention window. */
class RecycleBinActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecycleBinBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecycleBinBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.recycleRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        lifecycleScope.launch {
            val alarms = repoDeletedSnapshot()
            val timers = TimerController.deleted(applicationContext)
            val container = binding.recycleContainer
            container.removeAllViews()
            val inflater = LayoutInflater.from(this@RecycleBinActivity)
            val empty = alarms.isEmpty() && timers.isEmpty()
            binding.recycleEmpty.visibility = if (empty) View.VISIBLE else View.GONE
            binding.recycleScroll.visibility = if (empty) View.GONE else View.VISIBLE

            if (alarms.isNotEmpty()) {
                container.addView(sectionHeader(getString(R.string.recycle_section_alarms), container))
                alarms.forEach { addAlarmRow(inflater, container, it) }
            }
            if (timers.isNotEmpty()) {
                container.addView(sectionHeader(getString(R.string.recycle_section_timers), container))
                timers.forEach { addTimerRow(inflater, container, it.first, it.second) }
            }
        }
    }

    private suspend fun repoDeletedSnapshot(): List<Alarm> =
        AlarmRepository.get(applicationContext).let { repo ->
            // A one-shot read of the current bin (Flow.first would also work; keep it simple).
            repo.allAlarms().filter { it.deletedAt > 0 }.sortedByDescending { it.deletedAt }
        }

    private fun sectionHeader(text: String, parent: ViewGroup): View {
        val d = resources.displayMetrics.density
        return android.widget.TextView(this).apply {
            this.text = text
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    this, com.google.android.material.R.attr.colorOnSurface
                )
            )
            setPadding(0, (20 * d).toInt(), 0, (4 * d).toInt())
        }
    }

    private fun addAlarmRow(inflater: LayoutInflater, parent: ViewGroup, alarm: Alarm) {
        val row = ItemRecycleBinding.inflate(inflater, parent, false)
        val time = Format.timeText(this, alarm.hour, alarm.minute)
        row.recycleItemTitle.text = if (alarm.label.isBlank()) time else "$time · ${alarm.label}"
        row.recycleItemSub.text = retentionText(alarm.deletedAt, alarm.retentionMs())
        row.recycleItemRestore.setOnClickListener {
            lifecycleScope.launch {
                val repo = AlarmRepository.get(applicationContext)
                repo.restore(alarm.copy(deletedAt = 0))
                if (alarm.scheduleType == Alarm.SCHEDULE_EVENT && alarm.enabled) {
                    repo.getEventTrigger(alarm.id)?.let { repo.updateEventTrigger(it.copy(enabled = true)) }
                    EventAlarmCoordinator.onTriggerConfigured(applicationContext, alarm.id)
                }
                AlarmScheduler.rescheduleNext(applicationContext)
                toast(R.string.recycle_restored)
                render()
            }
        }
        row.recycleItemPurge.setOnClickListener {
            lifecycleScope.launch {
                AlarmRepository.get(applicationContext).purge(alarm.id)
                render()
            }
        }
        parent.addView(row.root)
    }

    private fun addTimerRow(inflater: LayoutInflater, parent: ViewGroup, item: TimerItem, deletedAt: Long) {
        val row = ItemRecycleBinding.inflate(inflater, parent, false)
        val label = item.label.ifBlank { getString(R.string.timer_default_label) }
        row.recycleItemTitle.text = getString(R.string.recycle_timer_fmt, label, formatDuration(item.durationMs))
        row.recycleItemSub.text = retentionText(deletedAt, TimerController.retentionMs())
        row.recycleItemRestore.setOnClickListener {
            TimerController.restore(applicationContext, item.id)
            toast(R.string.recycle_restored)
            render()
        }
        row.recycleItemPurge.setOnClickListener {
            TimerController.purgeFromBin(applicationContext, item.id)
            render()
        }
        parent.addView(row.root)
    }

    private fun retentionText(deletedAt: Long, retentionMs: Long): String {
        val remainingMs = (deletedAt + retentionMs - System.currentTimeMillis()).coerceAtLeast(0L)
        val days = remainingMs / (24L * 60 * 60 * 1000)
        return if (days < 1) getString(R.string.recycle_removes_today)
        else getString(R.string.recycle_removes_in_days_fmt, days)
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        else String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }

    private fun toast(res: Int) =
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show()
}
