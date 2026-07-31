package com.example.alarmtracker.ui.worldclock

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.alarmtracker.R
import com.example.alarmtracker.databinding.DialogCityPickerBinding
import com.example.alarmtracker.databinding.DialogTimeConverterBinding
import com.example.alarmtracker.databinding.FragmentWorldClockBinding
import com.example.alarmtracker.databinding.ItemCityChoiceBinding
import com.example.alarmtracker.util.Format
import com.example.alarmtracker.util.Prefs
import com.example.alarmtracker.util.WorldClocks
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialFadeThrough
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * World clock: the cities you care about, each with its current time and how far ahead or behind
 * you it is. Tapping one opens a converter — "if it's 9am there, what time is that here?" — which
 * is the actual question you have when you're trying to work out when to call someone.
 */
class WorldClockFragment : Fragment() {

    private var _binding: FragmentWorldClockBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: WorldClockAdapter

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
        _binding = FragmentWorldClockBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = WorldClockAdapter(
            is24h = { Prefs.is24Hour(requireContext()) },
            onClick = { zoneId -> showConverter(zoneId) },
            onLongClick = { zoneId -> confirmRemove(zoneId) }
        )
        binding.wcList.layoutManager = LinearLayoutManager(requireContext())
        binding.wcList.adapter = adapter
        binding.wcFab.setOnClickListener { showCityPicker() }

        refresh()
        // RESUMED, not STARTED: the pager keeps all five tabs alive, and STARTED would leave this
        // ticking every second while the world clock is off-screen. FragmentStateAdapter only
        // resumes the visible page, so this now runs exactly when it's being looked at.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    tick()
                    delay(1_000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val zones = WorldClocks.all(requireContext())
        adapter.submitZones(zones)
        binding.wcEmpty.visibility = if (zones.isEmpty()) View.VISIBLE else View.GONE
        binding.wcHeader.visibility = if (zones.isEmpty()) View.GONE else View.VISIBLE
        tick()
    }

    private fun tick() {
        val b = _binding ?: return
        val now = ZonedDateTime.now()
        b.wcLocalLabel.text = getString(
            R.string.wc_here_fmt, WorldClocks.cityOf(ZoneId.systemDefault().id)
        )
        b.wcLocalTime.text = formatTime(now)
        adapter.tick(now)
    }

    private fun formatTime(time: ZonedDateTime): String =
        Format.timeText(requireContext(), time.hour, time.minute)

    // ---- Add / remove cities ----

    private fun showCityPicker() {
        val view = DialogCityPickerBinding.inflate(layoutInflater)
        val all = WorldClocks.selectableZones()
        val already = WorldClocks.all(requireContext()).toSet()
        val choices = all.filterNot { it.id in already }

        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val pickerAdapter = CityChoiceAdapter(
            is24h = { Prefs.is24Hour(requireContext()) }
        ) { zone ->
            WorldClocks.add(requireContext(), zone.id)
            refresh()
            dialog.dismiss()
        }
        view.cityList.layoutManager = LinearLayoutManager(requireContext())
        view.cityList.adapter = pickerAdapter
        pickerAdapter.submit(choices)
        view.citySearch.doAfterTextChanged { text ->
            val q = text?.toString()?.trim()?.lowercase().orEmpty()
            pickerAdapter.submit(
                if (q.isEmpty()) choices
                else choices.filter {
                    it.city.lowercase().contains(q) || it.region.lowercase().contains(q)
                }
            )
        }

        dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.wc_add)
            .setView(view.root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun confirmRemove(zoneId: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.wc_remove_title, WorldClocks.cityOf(zoneId)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.wc_remove) { _, _ ->
                WorldClocks.remove(requireContext(), zoneId)
                refresh()
            }
            .show()
    }

    // ---- Converter ----

    /**
     * "If it's this time here, what time is it there?" — with a swap so it answers the other
     * direction too, because sometimes you know their working hours, not yours.
     */
    private fun showConverter(zoneId: String) {
        val view = DialogTimeConverterBinding.inflate(layoutInflater)
        val here = ZoneId.systemDefault()
        val there = ZoneId.of(zoneId)
        val is24 = Prefs.is24Hour(requireContext())
        // false = editing OUR time and reading theirs; true = the reverse.
        var reversed = false
        val now = ZonedDateTime.now()
        var hour = now.hour
        var minute = now.minute

        fun sourceZone() = if (reversed) there else here
        fun targetZone() = if (reversed) here else there

        fun render() {
            view.convFromLabel.text = getString(
                R.string.wc_conv_from_fmt, WorldClocks.cityOf(sourceZone().id)
            )
            view.convToLabel.text = getString(
                R.string.wc_conv_to_fmt, WorldClocks.cityOf(targetZone().id)
            )
            val source = ZonedDateTime.now(sourceZone())
                .withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            val target = source.withZoneSameInstant(targetZone())
            view.convResult.text = Format.timeText(requireContext(), target.hour, target.minute)
            val dayName = target.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
            val dayShift = target.toLocalDate().toEpochDay() - source.toLocalDate().toEpochDay()
            view.convResultDay.text = when {
                dayShift > 0 -> getString(R.string.wc_conv_next_day, dayName)
                dayShift < 0 -> getString(R.string.wc_conv_prev_day, dayName)
                else -> getString(R.string.wc_conv_same_day, dayName)
            }
        }

        setupWheels(view, is24, hour, minute) { h, m ->
            hour = h
            minute = m
            render()
        }
        view.convSwap.setOnClickListener {
            reversed = !reversed
            render()
        }
        render()

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(WorldClocks.cityOf(zoneId))
            .setView(view.root)
            .setPositiveButton(R.string.wc_done, null)
            .show()
    }

    /** Hour/minute (+ AM/PM in 12h mode) wheels, matching the alarm editor's inline picker. */
    private fun setupWheels(
        view: DialogTimeConverterBinding,
        is24: Boolean,
        initialHour: Int,
        initialMinute: Int,
        onChanged: (Int, Int) -> Unit
    ) {
        val hourPicker = view.convHour
        val minutePicker = view.convMinute
        val ampmPicker = view.convAmpm

        minutePicker.minValue = 0
        minutePicker.maxValue = 59
        minutePicker.value = initialMinute
        minutePicker.setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        minutePicker.wrapSelectorWheel = true

        if (is24) {
            ampmPicker.visibility = View.GONE
            hourPicker.minValue = 0
            hourPicker.maxValue = 23
            hourPicker.value = initialHour
            hourPicker.setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        } else {
            ampmPicker.visibility = View.VISIBLE
            ampmPicker.minValue = 0
            ampmPicker.maxValue = 1
            ampmPicker.displayedValues = arrayOf(
                getString(R.string.am_label), getString(R.string.pm_label)
            )
            ampmPicker.value = if (initialHour >= 12) 1 else 0
            hourPicker.minValue = 1
            hourPicker.maxValue = 12
            hourPicker.value = when {
                initialHour % 12 == 0 -> 12
                else -> initialHour % 12
            }
        }
        hourPicker.wrapSelectorWheel = true

        fun read(): Pair<Int, Int> {
            val h = if (is24) {
                hourPicker.value
            } else {
                val base = hourPicker.value % 12
                if (ampmPicker.value == 1) base + 12 else base
            }
            return h to minutePicker.value
        }

        val listener = android.widget.NumberPicker.OnValueChangeListener { _, _, _ ->
            val (h, m) = read()
            onChanged(h, m)
        }
        hourPicker.setOnValueChangedListener(listener)
        minutePicker.setOnValueChangedListener(listener)
        ampmPicker.setOnValueChangedListener(listener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---- Adapters ----

    private class WorldClockAdapter(
        private val is24h: () -> Boolean,
        private val onClick: (String) -> Unit,
        private val onLongClick: (String) -> Unit
    ) : RecyclerView.Adapter<WorldClockAdapter.Holder>() {

        private var zones: List<String> = emptyList()
        private var now: ZonedDateTime = ZonedDateTime.now()

        fun submitZones(newZones: List<String>) {
            zones = newZones
            notifyDataSetChanged()
        }

        /** Refreshes only the times, so the per-second tick doesn't rebuild rows. */
        fun tick(at: ZonedDateTime) {
            now = at
            notifyItemRangeChanged(0, zones.size, PAYLOAD_TICK)
        }

        override fun getItemCount() = zones.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            com.example.alarmtracker.databinding.ItemWorldClockBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

        override fun onBindViewHolder(holder: Holder, position: Int) =
            holder.bind(zones[position], now, is24h(), onClick, onLongClick)

        override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(PAYLOAD_TICK)) holder.bindTime(zones[position], now)
            else onBindViewHolder(holder, position)
        }

        class Holder(
            private val binding: com.example.alarmtracker.databinding.ItemWorldClockBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(
                zoneId: String,
                now: ZonedDateTime,
                is24h: Boolean,
                onClick: (String) -> Unit,
                onLongClick: (String) -> Unit
            ) {
                binding.wcCity.text = WorldClocks.cityOf(zoneId)
                binding.wcRow.setOnClickListener { onClick(zoneId) }
                binding.wcRow.setOnLongClickListener { onLongClick(zoneId); true }
                bindTime(zoneId, now)
            }

            fun bindTime(zoneId: String, now: ZonedDateTime) {
                val ctx = binding.root.context
                val there = now.withZoneSameInstant(ZoneId.of(zoneId))
                binding.wcTime.text = Format.timeText(ctx, there.hour, there.minute)
                binding.wcDiff.text = WorldClocks.offsetLabel(zoneId, now)
                binding.wcDay.text = ctx.getString(
                    R.string.wc_row_sub_fmt,
                    WorldClocks.dayLabel(zoneId, now),
                    WorldClocks.regionOf(zoneId)
                )
            }
        }

        companion object {
            private const val PAYLOAD_TICK = "tick"
        }
    }

    private class CityChoiceAdapter(
        private val is24h: () -> Boolean,
        private val onPick: (WorldClocks.Zone) -> Unit
    ) : RecyclerView.Adapter<CityChoiceAdapter.Holder>() {

        private var items: List<WorldClocks.Zone> = emptyList()

        fun submit(list: List<WorldClocks.Zone>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemCityChoiceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) =
            holder.bind(items[position], onPick)

        class Holder(private val binding: ItemCityChoiceBinding) :
            RecyclerView.ViewHolder(binding.root) {
            fun bind(zone: WorldClocks.Zone, onPick: (WorldClocks.Zone) -> Unit) {
                val ctx = binding.root.context
                binding.cityName.text = zone.city
                binding.cityRegion.text = zone.region
                val there = ZonedDateTime.now(ZoneId.of(zone.id))
                binding.cityTime.text = Format.timeText(ctx, there.hour, there.minute)
                binding.cityRow.setOnClickListener { onPick(zone) }
            }
        }
    }
}
