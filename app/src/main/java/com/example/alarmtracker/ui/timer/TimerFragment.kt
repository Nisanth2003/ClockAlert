package com.example.alarmtracker.ui.timer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.NumberPicker
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.alarmtracker.R
import com.example.alarmtracker.databinding.DialogAddTimerBinding
import com.example.alarmtracker.databinding.FragmentTimerBinding
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Multi-timer list (mirrors the Alarms tab): a FAB adds a countdown, each row shows its live time
 * left with play/pause and +1 min, and swipe deletes. State + scheduling live in [TimerController].
 */
class TimerFragment : Fragment(), com.example.alarmtracker.ui.common.TabMenuHost {

    private var _binding: FragmentTimerBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: TimerAdapter
    private var shownSignature = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimerBinding.inflate(inflater, container, false)
        return binding.root
    }

    // Action items live in the activity's shared top bar; see TabMenuHost.
    override val tabMenuRes: Int get() = R.menu.menu_recycle

    override fun onTabMenuItemSelected(itemId: Int): Boolean {
        if (itemId != R.id.action_recycle_bin) return false
        startActivity(
            android.content.Intent(
                requireContext(),
                com.example.alarmtracker.ui.recycle.RecycleBinActivity::class.java
            )
        )
        return true
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = TimerAdapter(
            onToggle = { id -> TimerController.toggle(requireContext(), id); refresh() },
            onAddTime = { id -> TimerController.addTime(requireContext(), id, 60_000L); refresh() },
            onEdit = { id -> TimerController.itemOf(requireContext(), id)?.let { showTimerDialog(it) } }
        )
        binding.timerList.layoutManager = LinearLayoutManager(requireContext())
        binding.timerList.adapter = adapter
        attachSwipeToDelete()
        com.example.alarmtracker.ui.common.PagerSwipeCoexist.attach(binding.timerList)
        binding.timerFab.setOnClickListener { showTimerDialog(null) }
        refresh()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    // A change in ids/running (add, delete, toggle, or a background finish) → full
                    // rebind; otherwise just refresh the ticking time text.
                    if (signatureOf() != shownSignature) refresh() else adapter.tick()
                    delay(500)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun signatureOf(): String =
        TimerController.all(requireContext()).joinToString(",") { "${it.id}:${it.running}" }

    private fun refresh() {
        val b = _binding ?: return
        val list = TimerController.all(requireContext())
        shownSignature = list.joinToString(",") { "${it.id}:${it.running}" }
        adapter.submitList(list)
        b.timerEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun attachSwipeToDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.RIGHT
        ) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            // Deliberate delete so a casual swipe (to change tabs) doesn't remove a timer.
            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.6f

            override fun getSwipeEscapeVelocity(defaultValue: Float): Float = defaultValue * 3f

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return
                val deletedId = adapter.itemAt(pos).id
                TimerController.delete(requireContext(), deletedId)
                refresh()
                Snackbar.make(binding.root, R.string.timer_deleted, Snackbar.LENGTH_LONG)
                    .setAnchorView(binding.timerFab)
                    .setAction(R.string.undo) {
                        TimerController.restore(requireContext(), deletedId)
                        refresh()
                    }
                    .show()
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.timerList)
    }

    /** Add (existing == null) or edit an existing timer, sharing the same dialog. */
    private fun showTimerDialog(existing: TimerItem?) {
        val dialogBinding = DialogAddTimerBinding.inflate(layoutInflater)
        configurePicker(dialogBinding.addHours, 23)
        configurePicker(dialogBinding.addMinutes, 59)
        configurePicker(dialogBinding.addSeconds, 59)
        // Prefill from the existing timer, or default to 5 minutes for a new one.
        var actionPackage: String? = existing?.actionPackage
        var actionLabel: String? = existing?.actionLabel
        if (existing != null) {
            val totalSec = (existing.durationMs / 1000).toInt()
            dialogBinding.addHours.value = totalSec / 3600
            dialogBinding.addMinutes.value = (totalSec % 3600) / 60
            dialogBinding.addSeconds.value = totalSec % 60
            dialogBinding.addLabelInput.setText(existing.label)
            dialogBinding.addFinishAction.text = actionLabel?.let {
                getString(R.string.timer_on_finish_fmt, it)
            } ?: getString(R.string.timer_on_finish_none)
        } else {
            dialogBinding.addMinutes.value = 5
        }
        intArrayOf(1, 3, 5, 10, 15, 30).forEach { min ->
            dialogBinding.addPresets.addView(
                Chip(requireContext()).apply {
                    text = "${min}m"
                    isCheckable = false
                    setOnClickListener {
                        dialogBinding.addHours.value = min / 60
                        dialogBinding.addMinutes.value = min % 60
                        dialogBinding.addSeconds.value = 0
                    }
                }
            )
        }
        // Optional "when it ends, open an app" action (prefilled above for edits).
        dialogBinding.addFinishAction.setOnClickListener {
            pickFinishApp { pkg, lbl ->
                actionPackage = pkg
                actionLabel = lbl
                dialogBinding.addFinishAction.text = if (pkg == null) {
                    getString(R.string.timer_on_finish_none)
                } else {
                    getString(R.string.timer_on_finish_fmt, lbl)
                }
            }
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (existing == null) R.string.timer_new_title else R.string.timer_edit_title)
            .setView(dialogBinding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (existing == null) R.string.timer_start else R.string.save) { _, _ ->
                val durationMs = (dialogBinding.addHours.value * 3600L +
                    dialogBinding.addMinutes.value * 60L +
                    dialogBinding.addSeconds.value) * 1000L
                val label = dialogBinding.addLabelInput.text?.toString()?.trim().orEmpty()
                if (durationMs > 0L) {
                    if (existing == null) {
                        TimerController.add(requireContext(), label, durationMs, actionPackage, actionLabel)
                    } else {
                        TimerController.edit(requireContext(), existing.id, label, durationMs, actionPackage, actionLabel)
                    }
                    refresh()
                }
            }
            .show()
    }

    /** Pick an installed app to open when the timer ends (or "Just alert" to clear it). */
    private fun pickFinishApp(onPicked: (String?, String?) -> Unit) {
        val pm = requireContext().packageManager
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == requireContext().packageName) return@mapNotNull null
                pkg to ri.loadLabel(pm).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) {
            android.widget.Toast.makeText(requireContext(), R.string.timer_no_apps, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // "Just alert" first, then the apps.
        val labels = (listOf(getString(R.string.timer_finish_alert_only)) + apps.map { it.second }).toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.timer_pick_app)
            .setItems(labels) { _, which ->
                if (which == 0) onPicked(null, null)
                else { val (pkg, lbl) = apps[which - 1]; onPicked(pkg, lbl) }
            }
            .show()
    }

    private fun configurePicker(picker: NumberPicker, max: Int) {
        picker.minValue = 0
        picker.maxValue = max
        picker.setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        picker.wrapSelectorWheel = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
