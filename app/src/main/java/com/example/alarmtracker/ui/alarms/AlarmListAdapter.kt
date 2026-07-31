package com.example.alarmtracker.ui.alarms

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.alarmtracker.R
import com.example.alarmtracker.data.Alarm
import com.example.alarmtracker.data.EventTrigger
import com.example.alarmtracker.data.NotificationMatchRule
import com.example.alarmtracker.databinding.ItemAlarmBinding
import com.example.alarmtracker.scheduling.EventAlarmCoordinator
import com.example.alarmtracker.util.Format
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors

class AlarmListAdapter(
    private val onClick: (Alarm) -> Unit,
    private val onToggle: (Alarm, Boolean) -> Unit,
    private val onResume: (Alarm) -> Unit,
    private val onLongClick: (Alarm, android.view.View) -> Unit
) : ListAdapter<Alarm, AlarmListAdapter.AlarmViewHolder>(Diff) {

    /** Live event-trigger state keyed by alarmId, refreshed by [updateEventTriggers]. */
    private var eventTriggers: Map<Long, EventTrigger> = emptyMap()

    /** Push refreshed trigger state; rebinds only the rows whose trigger actually changed. */
    fun updateEventTriggers(triggers: Map<Long, EventTrigger>) {
        val old = eventTriggers
        eventTriggers = triggers
        val changed = (old.keys + triggers.keys).filterTo(HashSet()) { old[it] != triggers[it] }
        if (changed.isEmpty()) return
        currentList.forEachIndexed { index, alarm ->
            if (alarm.id in changed) notifyItemChanged(index)
        }
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AlarmViewHolder =
        AlarmViewHolder(
            ItemAlarmBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: AlarmViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun alarmAt(position: Int): Alarm = getItem(position)

    inner class AlarmViewHolder(
        private val binding: ItemAlarmBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundKey: Pair<Boolean, Boolean>? = null

        fun bind(alarm: Alarm) {
            val context = binding.root.context
            val now = System.currentTimeMillis()
            val paused = alarm.enabled && alarm.isPausedAt(now)
            // A snoozed alarm is still live even though a ONCE/EVENT row was flipped to disabled
            // when it rang — show it as active and say when it comes back.
            val snoozed = alarm.isSnoozedAt(now)
            val live = alarm.enabled || snoozed
            val (time, amPm) = Format.timeParts(context, alarm.hour, alarm.minute)
            binding.alarmTime.text = time
            binding.alarmAmpm.text = amPm ?: ""

            // Paused alarms announce their resume date instead of the repeat summary.
            binding.alarmSubtitle.text = when {
                snoozed ->
                    context.getString(R.string.snoozed_until_fmt, clockText(context, alarm.snoozedUntil))
                paused ->
                    context.getString(R.string.paused_until_fmt, Format.dateMedium(context, alarm.pausedUntil!!))
                alarm.scheduleType == Alarm.SCHEDULE_EVENT ->
                    eventStatus(context, alarm, eventTriggers[alarm.id])
                else -> {
                    val summary = Format.repeatSummary(context, alarm)
                    val base = if (alarm.label.isBlank()) summary else "${alarm.label} · $summary"
                    if (alarm.isSkippingNextAt(System.currentTimeMillis())) {
                        "$base · ${context.getString(R.string.skip_next_indicator)}"
                    } else {
                        base
                    }
                }
            }

            binding.alarmResume.visibility = if (paused) android.view.View.VISIBLE else android.view.View.GONE
            binding.alarmResume.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onResume(alarm)
            }

            binding.alarmSwitch.contentDescription = context.getString(
                R.string.alarm_switch_description,
                Format.timeText(context, alarm.hour, alarm.minute)
            )
            binding.alarmSwitch.setOnCheckedChangeListener(null)
            binding.alarmSwitch.isChecked = live
            binding.alarmSwitch.setOnCheckedChangeListener { view, checked ->
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onToggle(alarm, checked)
            }

            binding.alarmCard.setOnClickListener { onClick(alarm) }
            binding.alarmCard.setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongClick(alarm, it)
                true
            }
            binding.alarmCard.contentDescription =
                "${Format.timeText(context, alarm.hour, alarm.minute)}, ${binding.alarmSubtitle.text}"

            val key = live to paused
            applyColors(live, paused, animate = boundKey != null && boundKey != key)
            boundKey = key
        }

        /** Wall-clock "7:12 am" for an epoch instant, in the user's chosen 12/24h format. */
        private fun clockText(context: android.content.Context, millis: Long): String {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = millis }
            return Format.timeText(
                context,
                c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE)
            )
        }

        /**
         * Live status line for an event alarm. Notification-source and destination-source alarms
         * each show a live line refined by their signals, always falling back to the guaranteed time.
         */
        private fun eventStatus(
            context: android.content.Context,
            alarm: Alarm,
            trigger: EventTrigger?
        ): String {
            if (trigger?.sourceType == EventTrigger.SOURCE_COOLDOWN) {
                return cooldownStatus(context, alarm, trigger)
            }
            if (trigger?.sourceType == EventTrigger.SOURCE_NOTIFICATION) {
                return notificationStatus(context, alarm, trigger)
            }
            return destinationStatus(context, alarm, trigger)
        }

        /**
         * Cooldown status, e.g. "ChatGPT back ~3:30" — the guaranteed reset time (refined by a
         * parsed "resets at…" notification when one is seen).
         */
        private fun cooldownStatus(
            context: android.content.Context,
            alarm: Alarm,
            trigger: EventTrigger
        ): String {
            val name = alarm.label.ifBlank {
                trigger.placeName
                    ?: NotificationMatchRule.fromJson(trigger.configJson)?.label
                    ?: context.getString(R.string.tracking_cooldown)
            }
            // Show the effective reset time: a parsed "resets at…" refinement, else the timer.
            val resetMillis = trigger.effectiveEtaMillis
            val resetTime = if (resetMillis != null) {
                val c = java.util.Calendar.getInstance().apply { timeInMillis = resetMillis }
                Format.timeText(context, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
            } else {
                fallbackTimeText(context, alarm, trigger)
            }
            return context.getString(R.string.event_status_cooldown_waiting_fmt, name, resetTime)
        }

        /**
         * Destination (geofence) status:
         *  - a refined ETA with a known distance → "Arriving · ≈X km"
         *  - otherwise → "{place} · fallback {time} if no signal" (the guaranteed fallback).
         */
        private fun destinationStatus(
            context: android.content.Context,
            alarm: Alarm,
            trigger: EventTrigger?
        ): String {
            val place = (alarm.label.ifBlank { trigger?.placeName }).orEmpty()
            val fallbackTime = fallbackTimeText(context, alarm, trigger)
            val distance = trigger?.lastDistanceM
            return if (trigger?.currentEtaMillis != null && distance != null) {
                context.getString(
                    R.string.event_status_arriving_fmt,
                    EventAlarmCoordinator.formatKm(context, distance)
                )
            } else if (place.isNotBlank()) {
                context.getString(R.string.event_status_fallback_fmt, place, fallbackTime)
            } else {
                context.getString(R.string.event_status_fallback_generic_fmt, fallbackTime)
            }
        }

        /**
         * Notification status (e.g. "Waiting for: Claude · done" / "Maps · ≈12 km"):
         *  - a refined ETA with a known distance → "{app} · ≈X km"
         *  - a refined ETA with no distance      → "{app} · ≈N min"
         *  - otherwise                           → "Waiting for: {app}" (guaranteed at the fallback).
         */
        private fun notificationStatus(
            context: android.content.Context,
            alarm: Alarm,
            trigger: EventTrigger
        ): String {
            val rule = NotificationMatchRule.fromJson(trigger.configJson)
            val name = alarm.label.ifBlank {
                rule?.label ?: rule?.packages?.firstOrNull().orEmpty()
            }.ifBlank { context.getString(R.string.event_source_notification) }

            val eta = trigger.currentEtaMillis
            val distance = trigger.lastDistanceM
            return when {
                eta != null && distance != null ->
                    context.getString(
                        R.string.event_status_notif_distance_fmt,
                        name, EventAlarmCoordinator.formatKm(context, distance)
                    )
                eta != null -> {
                    val minutes = ((eta - System.currentTimeMillis()) / 60_000L)
                        .coerceAtLeast(0).toInt()
                    context.getString(R.string.event_status_notif_eta_fmt, name, minutes)
                }
                else -> context.getString(R.string.event_status_notif_waiting_fmt, name)
            }
        }

        private fun fallbackTimeText(
            context: android.content.Context,
            alarm: Alarm,
            trigger: EventTrigger?
        ): String = trigger?.fallbackEtaMillis?.let {
            val c = java.util.Calendar.getInstance().apply { timeInMillis = it }
            Format.timeText(context, c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
        } ?: Format.timeText(context, alarm.hour, alarm.minute)

        private fun applyColors(enabled: Boolean, paused: Boolean, animate: Boolean) {
            val card = binding.alarmCard
            val containerAttr = when {
                paused -> MaterialR.attr.colorTertiaryContainer
                enabled -> MaterialR.attr.colorPrimaryContainer
                else -> MaterialR.attr.colorSurfaceContainerLow
            }
            val onContainerAttr = when {
                paused -> MaterialR.attr.colorOnTertiaryContainer
                enabled -> MaterialR.attr.colorOnPrimaryContainer
                else -> MaterialR.attr.colorOnSurfaceVariant
            }
            val container = MaterialColors.getColor(card, containerAttr)
            val onContainer = MaterialColors.getColor(card, onContainerAttr)
            // Keep the time bright even when disabled so it stays readable in dark mode; the muted
            // card colour + off-toggle are enough to signal "disabled".
            val timeColor = if (enabled || paused) onContainer else MaterialColors.getColor(card, MaterialR.attr.colorOnSurface)
            val subtitleColor = onContainer
            if (animate) {
                val from = card.cardBackgroundColor.defaultColor
                ValueAnimator.ofObject(ArgbEvaluator(), from, container).apply {
                    duration = 250
                    addUpdateListener { card.setCardBackgroundColor(it.animatedValue as Int) }
                    start()
                }
            } else {
                card.setCardBackgroundColor(container)
            }
            binding.alarmTime.setTextColor(timeColor)
            binding.alarmAmpm.setTextColor(timeColor)
            binding.alarmSubtitle.setTextColor(subtitleColor)
            // Full opacity — the muted colour already reads as secondary; 0.75 made it too faint on dark.
            binding.alarmSubtitle.alpha = 1f
        }
    }

    private object Diff : DiffUtil.ItemCallback<Alarm>() {
        override fun areItemsTheSame(oldItem: Alarm, newItem: Alarm): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Alarm, newItem: Alarm): Boolean =
            oldItem == newItem
    }
}
