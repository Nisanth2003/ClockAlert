package com.example.alarmtracker.ui.stopwatch

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.alarmtracker.R
import com.example.alarmtracker.databinding.FragmentStopwatchBinding
import com.example.alarmtracker.util.ShareUtil
import java.util.Locale

/**
 * A simple, reliable stopwatch with lap splits and a CSV/text export — the record-keeping that
 * stock stopwatches (play/lap/reset only) skip, useful for runners/swimmers keeping times.
 * Runs off [SystemClock.elapsedRealtime] so it's immune to wall-clock changes; state is in-memory
 * for the session and preserved via export.
 */
class StopwatchFragment : Fragment() {

    private var _binding: FragmentStopwatchBinding? = null
    private val binding get() = _binding!!

    private var running = false
    private var accumulatedMs = 0L
    private var startElapsed = 0L

    /** Cumulative elapsed time (ms) captured at each Lap press. */
    private val laps = mutableListOf<Long>()

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateTimeText()
            if (running) handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentStopwatchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.swStartPause.setOnClickListener { toggleStartPause() }
        binding.swLap.setOnClickListener { recordLap() }
        binding.swReset.setOnClickListener { reset() }
        binding.swExport.setOnClickListener { exportLaps() }
        updateTimeText()
        updateStartPauseLabel()
        binding.swLapsEmpty.visibility = if (laps.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun elapsedMs(): Long =
        accumulatedMs + if (running) SystemClock.elapsedRealtime() - startElapsed else 0L

    private fun toggleStartPause() {
        binding.swStartPause.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (running) {
            accumulatedMs = elapsedMs()
            running = false
            handler.removeCallbacks(ticker)
        } else {
            startElapsed = SystemClock.elapsedRealtime()
            running = true
            handler.post(ticker)
        }
        updateStartPauseLabel()
    }

    private fun recordLap() {
        if (!running && accumulatedMs == 0L) return
        binding.swLap.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val total = elapsedMs()
        laps.add(total)
        addLapRow(laps.size - 1)
        binding.swLapsEmpty.visibility = View.GONE
    }

    private fun reset() {
        binding.swReset.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        running = false
        handler.removeCallbacks(ticker)
        accumulatedMs = 0L
        laps.clear()
        binding.swLapContainer.removeAllViews()
        binding.swLapHeader.visibility = View.GONE
        binding.swLapsEmpty.visibility = View.VISIBLE
        updateTimeText()
        updateStartPauseLabel()
    }

    private fun addLapRow(index: Int) {
        val total = laps[index]
        val split = total - (if (index > 0) laps[index - 1] else 0L)
        val pad = (10 * resources.displayMetrics.density).toInt()
        val onSurface = com.google.android.material.color.MaterialColors.getColor(
            binding.swLapContainer, com.google.android.material.R.attr.colorOnSurface
        )
        val row = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, pad, 0, pad)
            addView(lapCell(getString(R.string.sw_lap_row_fmt, index + 1), android.view.Gravity.START, onSurface))
            addView(lapCell(getString(R.string.sw_lap_split_fmt, format(split)), android.view.Gravity.END, onSurface))
            addView(lapCell(format(total), android.view.Gravity.END, onSurface))
        }
        // Newest lap on top.
        binding.swLapContainer.addView(row, 0)
        binding.swLapHeader.visibility = View.VISIBLE
    }

    private fun lapCell(text: String, gravity: Int, color: Int): TextView =
        TextView(requireContext()).apply {
            this.text = text
            this.gravity = gravity
            textSize = 16f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(color)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        }

    private fun exportLaps() {
        if (laps.isEmpty()) {
            Toast.makeText(requireContext(), R.string.sw_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        ShareUtil.shareText(requireContext(), getString(R.string.sw_export_subject), buildExportText())
    }

    /** A clean, aligned, human-readable table (also pastes fine into a sheet). */
    private fun buildExportText(): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            .format(java.util.Date())
        val sb = StringBuilder()
        sb.append("AlarmTracker · Stopwatch\n")
        sb.append("${laps.size} lap(s) · total ${format(laps.last())}\n")
        sb.append("$stamp\n\n")
        sb.append("Lap".padEnd(6)).append("Split".padEnd(12)).append("Total\n")
        sb.append("─".repeat(28)).append("\n")
        laps.forEachIndexed { i, total ->
            val split = total - (if (i > 0) laps[i - 1] else 0L)
            sb.append("${i + 1}".padEnd(6))
                .append(format(split).padEnd(12))
                .append(format(total))
                .append("\n")
        }
        return sb.toString()
    }

    private fun updateTimeText() {
        _binding?.swTime?.text = format(elapsedMs())
    }

    private fun updateStartPauseLabel() {
        binding.swStartPause.setText(
            when {
                running -> R.string.sw_pause
                accumulatedMs > 0L -> R.string.sw_resume
                else -> R.string.sw_start
            }
        )
    }

    /** mm:ss.cs */
    private fun format(ms: Long): String {
        val cs = (ms / 10) % 100
        val s = (ms / 1000) % 60
        val m = ms / 60_000
        return String.format(Locale.getDefault(), "%02d:%02d.%02d", m, s, cs)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(ticker)
        _binding = null
    }

    companion object {
        private const val TICK_MS = 31L
    }
}
