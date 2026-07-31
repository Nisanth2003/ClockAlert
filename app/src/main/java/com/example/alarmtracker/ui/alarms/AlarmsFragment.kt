package com.example.alarmtracker.ui.alarms

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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.alarmtracker.R
import com.example.alarmtracker.data.Alarm
import com.example.alarmtracker.databinding.FragmentAlarmsBinding
import com.example.alarmtracker.ui.health.HealthCheckActivity
import com.example.alarmtracker.util.Format
import com.example.alarmtracker.util.Reliability
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.transition.MaterialFadeThrough
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AlarmsFragment : Fragment(), com.example.alarmtracker.ui.common.TabMenuHost {

    private var _binding: FragmentAlarmsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AlarmsViewModel by viewModels()
    private lateinit var adapter: AlarmListAdapter

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
        _binding = FragmentAlarmsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = AlarmListAdapter(
            onClick = { alarm -> openEditSheet(alarm.id) },
            onToggle = { alarm, enabled -> viewModel.setEnabled(alarm, enabled) },
            onResume = { alarm ->
                viewModel.resume(alarm)
                Snackbar.make(binding.root, R.string.alarm_resumed, Snackbar.LENGTH_SHORT)
                    .setAnchorView(binding.fabAdd)
                    .show()
            },
            onLongClick = { alarm, anchor -> showItemMenu(alarm, anchor) }
        )
        binding.alarmList.layoutManager = LinearLayoutManager(requireContext())
        binding.alarmList.adapter = adapter
        attachSwipeToDelete()
        com.example.alarmtracker.ui.common.PagerSwipeCoexist.attach(binding.alarmList)

        // One unified editor: the FAB opens a normal alarm; the "Track an event" dropdown
        // inside it turns it into an arrival/notification alarm.
        binding.fabAdd.setOnClickListener { openEditSheet(0L) }

        childFragmentManager.setFragmentResultListener(
            AlarmEditSheet.REQUEST_KEY, viewLifecycleOwner
        ) { _, result ->
            when (result.getString(AlarmEditSheet.KEY_ACTION)) {
                AlarmEditSheet.ACTION_SAVED -> {
                    Snackbar.make(binding.root, R.string.alarm_saved, Snackbar.LENGTH_SHORT)
                        .setAnchorView(binding.fabAdd)
                        .show()
                    maybePromptFullScreen()
                }

                AlarmEditSheet.ACTION_DELETE_REQUESTED -> {
                    val id = result.getLong(AlarmEditSheet.KEY_ALARM_ID)
                    viewModel.alarms.value?.firstOrNull { it.id == id }?.let { deleteWithUndo(it) }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.alarms.collect { list ->
                        if (list != null) {
                            adapter.submitList(list)
                            binding.emptyState.visibility =
                                if (list.isEmpty()) View.VISIBLE else View.GONE
                        }
                    }
                }
                launch {
                    viewModel.nextAlarm.collect { updateNextAlarmHeader(it) }
                }
                launch {
                    viewModel.eventTriggers.collect { adapter.updateEventTriggers(it) }
                }
                launch {
                    while (true) {
                        delay(30_000)
                        updateNextAlarmHeader(viewModel.nextAlarm.value)
                    }
                }
            }
        }
    }

    // Action items live in the activity's shared top bar; see TabMenuHost.
    override val tabMenuRes: Int get() = R.menu.menu_recycle

    override fun onTabMenuItemSelected(itemId: Int): Boolean {
        if (itemId != R.id.action_recycle_bin) return false
        startActivity(
            Intent(requireContext(), com.example.alarmtracker.ui.recycle.RecycleBinActivity::class.java)
        )
        return true
    }

    override fun onResume() {
        super.onResume()
        updatePermissionBanner()
        updateGreeting()
        maybeShowCoach()
    }

    /** First-run (post-onboarding) guided walkthrough of the FAB, tabs and editing gestures. */
    private var coachShown = false
    private fun maybeShowCoach() {
        if (coachShown) return
        val ctx = requireContext()
        if (!com.example.alarmtracker.util.Prefs.onboardingDone(ctx)) return
        if (com.example.alarmtracker.util.Prefs.coachDone(ctx)) return
        coachShown = true
        binding.root.post {
            if (!isAdded) return@post
            val nav = requireActivity().findViewById<View>(R.id.bottom_nav)
            com.example.alarmtracker.ui.coach.CoachMark.show(
                requireActivity(),
                listOf(
                    com.example.alarmtracker.ui.coach.CoachStep(
                        binding.fabAdd, getString(R.string.coach_fab_title), getString(R.string.coach_fab_body)
                    ),
                    com.example.alarmtracker.ui.coach.CoachStep(
                        nav, getString(R.string.coach_nav_title), getString(R.string.coach_nav_body)
                    ),
                    com.example.alarmtracker.ui.coach.CoachStep(
                        null, getString(R.string.coach_edit_title), getString(R.string.coach_edit_body)
                    )
                )
            ) { com.example.alarmtracker.util.Prefs.setCoachDone(ctx) }
        }
    }

    /**
     * A warm, time-of-day greeting above the next-alarm line — and the colour behind it.
     *
     * The wash is drawn from the theme's own accent (harmonized towards a dawn/day/dusk/night hue)
     * rather than fixed colours, so it still belongs whichever accent or wallpaper palette is
     * active, and it stays legible in both light and dark.
     */
    private fun updateGreeting() {
        val b = _binding ?: return
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val (greeting, hue) = when (hour) {
            in 5..11 -> R.string.greeting_morning to 0xFFFFB74D.toInt()   // sunrise amber
            in 12..16 -> R.string.greeting_afternoon to 0xFF4FC3F7.toInt() // daylight blue
            in 17..20 -> R.string.greeting_evening to 0xFFFF8A65.toInt()   // dusk coral
            else -> R.string.greeting_night to 0xFF5C6BC0.toInt()          // night indigo
        }
        b.greetingHeader.setText(greeting)

        val surface = com.google.android.material.color.MaterialColors.getColor(
            b.greetingBlock, com.google.android.material.R.attr.colorSurface
        )
        // Harmonize pulls the hue towards the active palette so it never clashes with the accent.
        val tinted = com.google.android.material.color.MaterialColors.harmonizeWithPrimary(
            requireContext(), hue
        )
        // A gentle wash: mostly surface, just enough hue to read as colour rather than grey.
        val top = com.google.android.material.color.MaterialColors.layer(surface, tinted, 0.38f)
        b.greetingBlock.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, surface)
        )
    }

    private fun openEditSheet(alarmId: Long) {
        if (childFragmentManager.findFragmentByTag(AlarmEditSheet.TAG) == null) {
            AlarmEditSheet.newInstance(alarmId).show(childFragmentManager, AlarmEditSheet.TAG)
        }
    }

    private fun updateNextAlarmHeader(next: Alarm?) {
        val b = _binding ?: return
        val triggerAt = next?.nextTriggerAt
        b.nextAlarmHeader.text = if (triggerAt == null) {
            getString(R.string.next_alarm_none)
        } else {
            getString(
                R.string.next_alarm_in,
                Format.untilText(requireContext(), triggerAt - System.currentTimeMillis())
            )
        }
    }

    private fun attachSwipeToDelete() {
        val density = resources.displayMetrics.density
        val marginH = resources.getDimensionPixelSize(R.dimen.screen_margin).toFloat()
        val marginV = 5f * density
        val radius = resources.getDimension(R.dimen.card_corner_radius)
        val iconPad = 24f * density
        val icon = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.ic_delete)
        val onError = com.google.android.material.color.MaterialColors.getColor(
            binding.alarmList, com.google.android.material.R.attr.colorOnErrorContainer
        )
        icon?.setTint(onError)
        val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = com.google.android.material.color.MaterialColors.getColor(
                binding.alarmList, com.google.android.material.R.attr.colorErrorContainer
            )
        }

        val callback = object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            // Deliberate delete: the row must be dragged ~60% across (harder to trigger by accident
            // while trying to swipe between tabs).
            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.6f

            override fun getSwipeEscapeVelocity(defaultValue: Float): Float = defaultValue * 3f

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    deleteWithUndo(adapter.alarmAt(position))
                }
            }

            override fun onChildDraw(
                c: android.graphics.Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                    val v = viewHolder.itemView
                    val left = v.left + marginH
                    val right = v.right - marginH
                    val top = v.top + marginV
                    val bottom = v.bottom - marginV
                    c.drawRoundRect(left, top, right, bottom, radius, radius, bgPaint)
                    icon?.let {
                        val size = it.intrinsicHeight
                        val iconTop = ((top + bottom) / 2f - size / 2f).toInt()
                        if (dX > 0) {
                            it.setBounds((left + iconPad).toInt(), iconTop, (left + iconPad).toInt() + size, iconTop + size)
                        } else {
                            it.setBounds((right - iconPad).toInt() - size, iconTop, (right - iconPad).toInt(), iconTop + size)
                        }
                        it.draw(c)
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.alarmList)
    }

    private fun deleteWithUndo(alarm: Alarm) {
        viewModel.delete(alarm)
        Snackbar.make(binding.root, R.string.alarm_deleted, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.fabAdd)
            .setAction(R.string.undo) { viewModel.restore(alarm) }
            .show()
    }

    /** Long-press on an alarm: quick Skip-next / Duplicate / Delete menu. */
    private fun showItemMenu(alarm: Alarm, anchor: View) {
        val now = System.currentTimeMillis()
        val canSkip = alarm.enabled && alarm.scheduleType in REPEATING_TYPES
        val skipping = alarm.isSkippingNextAt(now)
        androidx.appcompat.widget.PopupMenu(requireContext(), anchor).apply {
            if (canSkip) {
                menu.add(0, MENU_SKIP, 0, if (skipping) R.string.skip_next_cancel else R.string.skip_next)
            }
            menu.add(0, MENU_DUPLICATE, 1, R.string.duplicate_alarm)
            menu.add(0, MENU_DELETE, 2, R.string.delete_alarm)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SKIP -> {
                        if (skipping) {
                            viewModel.clearSkip(alarm)
                            snack(R.string.alarm_skip_cleared)
                        } else {
                            viewModel.skipNext(alarm)
                            snack(R.string.alarm_skipped)
                        }
                        true
                    }
                    MENU_DUPLICATE -> {
                        viewModel.duplicate(alarm)
                        snack(R.string.alarm_duplicated)
                        true
                    }
                    MENU_DELETE -> { deleteWithUndo(alarm); true }
                    else -> false
                }
            }
            show()
        }
    }

    private fun snack(res: Int) {
        Snackbar.make(binding.root, res, Snackbar.LENGTH_SHORT)
            .setAnchorView(binding.fabAdd)
            .show()
    }

    /**
     * The moment a user creates an alarm, make sure it can actually take over the lock screen.
     * Without full-screen-intent access (denied by default on Android 14+) the alarm still sounds
     * but shows no dismiss screen over the lockscreen — so we prompt straight to the toggle. Shown
     * only while it's missing, so it stops appearing once granted.
     */
    private fun maybePromptFullScreen() {
        if (Reliability.fullScreenIntentOk(requireContext())) return
        val intent = Reliability.fullScreenIntentSettings(requireContext()) ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.fsi_prompt_title)
            .setMessage(R.string.fsi_prompt_body)
            .setNegativeButton(R.string.fsi_prompt_later, null)
            .setPositiveButton(R.string.fsi_prompt_allow) { _, _ ->
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(Intent(requireContext(), HealthCheckActivity::class.java))
                }
            }
            .show()
    }

    // ---- Permission warning banner ----

    private fun updatePermissionBanner() {
        val b = _binding ?: return
        val problem = Reliability.firstProblem(requireContext())
        if (problem == null) {
            b.permissionBanner.visibility = View.GONE
            return
        }
        b.permissionBanner.visibility = View.VISIBLE
        b.bannerTitle.setText(problem.titleRes)
        b.bannerBody.setText(problem.summaryRes)
        b.bannerAction.setText(problem.actionLabelRes)
        b.bannerAction.setOnClickListener {
            // Route into the full reliability checklist so the user can see and fix
            // every condition, not just the first one.
            startActivity(Intent(requireContext(), HealthCheckActivity::class.java))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val MENU_SKIP = 0
        private const val MENU_DUPLICATE = 1
        private const val MENU_DELETE = 2
        private val REPEATING_TYPES = setOf(
            Alarm.SCHEDULE_WEEKLY, Alarm.SCHEDULE_SHIFT, Alarm.SCHEDULE_CALENDAR
        )
    }
}
