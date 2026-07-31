package com.example.alarmtracker.ui.timer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.alarmtracker.R
import com.example.alarmtracker.databinding.ItemTimerBinding
import java.util.Locale

/**
 * Multi-timer list. A DiffUtil [ListAdapter] so add / delete / restore animate smoothly; running
 * rows tick their time text via a lightweight payload rebind (no structural diff).
 */
class TimerAdapter(
    private val onToggle: (Long) -> Unit,
    private val onAddTime: (Long) -> Unit,
    private val onEdit: (Long) -> Unit
) : ListAdapter<TimerItem, TimerAdapter.VH>(DIFF) {

    /** Refresh only the time text + progress of every row (called ~2×/s while any timer runs). */
    fun tick() {
        if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_TICK)
    }

    fun itemAt(position: Int): TimerItem = getItem(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemTimerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bindFull(getItem(position))

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_TICK)) holder.bindTime(getItem(position))
        else holder.bindFull(getItem(position))
    }

    inner class VH(private val binding: ItemTimerBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bindFull(item: TimerItem) {
            val ctx = binding.root.context
            bindTime(item)
            val duration = formatClock(item.durationMs, roundUp = false)
            val label = item.label.ifBlank { ctx.getString(R.string.timer_default_label) }
            val base = "$label · $duration"
            binding.timerLabel.text = item.actionLabel?.let {
                "$base · ${ctx.getString(R.string.timer_on_finish_fmt, it)}"
            } ?: base
            binding.timerPlayPause.setIconResource(
                if (item.running) R.drawable.ic_pause else R.drawable.ic_play
            )
            binding.timerPlayPause.setOnClickListener { onToggle(item.id) }
            binding.timerAdd.setOnClickListener { onAddTime(item.id) }
            binding.timerCard.setOnClickListener { onEdit(item.id) }
        }

        fun bindTime(item: TimerItem) {
            val remaining = item.remaining(System.currentTimeMillis())
            binding.timerTime.text = formatClock(remaining, roundUp = true)
            val total = item.durationMs.coerceAtLeast(1L)
            binding.timerProgress.setProgressCompat(
                ((remaining.toDouble() / total) * 1000).toInt().coerceIn(0, 1000),
                true
            )
        }
    }

    private fun formatClock(ms: Long, roundUp: Boolean): String {
        val totalSec = if (roundUp) (ms + 999) / 1000 else ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", m, s)
        }
    }

    companion object {
        private const val PAYLOAD_TICK = "tick"

        private val DIFF = object : DiffUtil.ItemCallback<TimerItem>() {
            override fun areItemsTheSame(oldItem: TimerItem, newItem: TimerItem): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: TimerItem, newItem: TimerItem): Boolean =
                oldItem == newItem
        }
    }
}
