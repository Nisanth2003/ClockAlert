package com.example.alarmtracker.ui.alarms

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.NumberPicker
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.example.alarmtracker.AlarmTrackerApp
import com.example.alarmtracker.R
import com.example.alarmtracker.data.Alarm
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.data.EventTrigger
import com.example.alarmtracker.data.NotificationMatchRule
import com.example.alarmtracker.databinding.ItemTrackableNotificationBinding
import com.example.alarmtracker.databinding.SheetAlarmEditBinding
import com.example.alarmtracker.notif.AlarmNotificationListener
import com.example.alarmtracker.notif.CooldownPresets
import com.example.alarmtracker.notif.NotificationAccess
import com.example.alarmtracker.notif.TrackablePresets
import com.example.alarmtracker.ring.CaptureActivity
import com.example.alarmtracker.ui.map.MapPickerActivity
import com.example.alarmtracker.scheduling.AlarmScheduler
import com.example.alarmtracker.scheduling.EventAlarmCoordinator
import com.example.alarmtracker.scheduling.GeofenceManager
import com.example.alarmtracker.util.AlarmTimes
import com.example.alarmtracker.util.CalendarAlarm
import com.example.alarmtracker.util.Format
import com.example.alarmtracker.util.GeoResolver
import com.example.alarmtracker.util.LocationState
import com.example.alarmtracker.util.NetworkState
import com.example.alarmtracker.util.NextTrigger
import com.example.alarmtracker.util.PlaceSearch
import com.example.alarmtracker.util.Prefs
import com.example.alarmtracker.util.RouteService
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Add/Edit alarm bottom sheet. Committing is explicit: a pinned header holds Save, Cancel and
 * (when editing) Delete. Cancel / back / swipe discards, prompting first if there are unsaved
 * edits. Delete is delegated to the parent fragment so it can offer UNDO.
 */
class AlarmEditSheet : BottomSheetDialogFragment() {

    private var _binding: SheetAlarmEditBinding? = null
    private val binding get() = _binding!!

    private var original: Alarm? = null
    private var loaded = false
    // Snapshot of every editable field taken right after load; used to detect unsaved edits.
    private var baselineSignature: String? = null

    // Editable state (source of truth for auto-save).
    private var hour = 8
    private var minute = 0
    private var snoozeMinutes = 10
    private var missionType = Alarm.MISSION_NONE
    private var missionDifficulty = 1
    private var missionBarcode: String? = null
    private var missionPhotoHash: String? = null
    private var gentleWakeMinutes = 0
    private var snoozeCoaching = false
    private var scheduleType = Alarm.SCHEDULE_WEEKLY
    private var soundUri: String? = null
    private var pausedFrom: Long? = null
    private var pausedUntil: Long? = null
    private var shiftWorkDays = 4
    private var shiftRestDays = 4
    private var shiftAnchorDate = LocalDate.now().toEpochDay()
    private var prepBufferMinutes = 30
    private var calendarSkipIfNoEvent = false

    // ---- Track-an-event state (Off / Arrive at a place / When a notification appears) ----
    private var trackingMode = TRACK_OFF
    private var arrivalRadiusM = 200
    private var destLat: Double? = null
    private var destLng: Double? = null
    private var placeName: String? = null
    private var notificationRule: NotificationMatchRule? = null

    /**
     * Notification mode's backstop, in minutes from when the alarm is saved. Null means the user asked
     * for a wall-clock deadline instead, which reveals the time wheels again.
     *
     * Defaults to [DEFAULT_WAIT_MINUTES]: a tracked notification is a short wait — a build finishing,
     * food arriving, a train pulling in — so the sensible backstop is half an hour from now, not a
     * time of day the user has to work out.
     */
    private var waitMinutes: Int? = DEFAULT_WAIT_MINUTES

    /** Estimated arrival from the last distance measurement; backs "Set the alarm to …". */
    private var estimatedArrivalMillis: Long? = null

    /** Travel speed the arrival ring's lead time is worked out from; refined by a real route. */
    private var estimateSpeedKmh = ASSUMED_SPEED_KMH

    /** Set once the user has chosen to save a place alarm without location anyway. */
    private var locationWarningAcknowledged = false

    // "When it rings, open this app" — an installed package + its label, or null for just ringing.
    private var actionPackage: String? = null
    private var actionLabel: String? = null

    /** Off / With sound / Only vibrate — see [Alarm.vibrationMode]. */
    private var vibrationMode = Alarm.VIBRATE_OFF

    // ---- Cooldown (When a limit resets) state ----
    private var cooldownServiceName: String? = null
    private var cooldownResetMillis: Long? = null

    private val fineLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) maybeRequestBackground() else updateLocationStatus()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { updateLocationStatus() }

    private val dayButtonIds = intArrayOf(
        R.id.day_mon, R.id.day_tue, R.id.day_wed, R.id.day_thu,
        R.id.day_fri, R.id.day_sat, R.id.day_sun
    )

    private val ringtonePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = IntentCompat.getParcelableExtra(
                result.data ?: Intent(),
                RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                Uri::class.java
            )
            soundUri = uri?.toString()
            onRingtoneChosen()
        }
    }

    private val barcodeCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            missionBarcode = result.data?.getStringExtra(CaptureActivity.EXTRA_RESULT_VALUE)
            updateMissionText()
            updateMissionOptions()
        }
    }

    private val photoCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            missionPhotoHash = result.data?.getStringExtra(CaptureActivity.EXTRA_RESULT_VALUE)
            updateMissionText()
            updateMissionOptions()
        }
    }

    private val mapPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            val lat = data.getDoubleExtra(MapPickerActivity.EXTRA_LAT, Double.NaN)
            val lng = data.getDoubleExtra(MapPickerActivity.EXTRA_LNG, Double.NaN)
            if (!lat.isNaN() && !lng.isNaN()) {
                // Set the coords directly (don't touch the text field — its watcher clears them).
                destLat = lat
                destLng = lng
                // The picker reverse-geocodes the pin, so prefer its readable name over raw coords.
                placeName = data.getStringExtra(MapPickerActivity.EXTRA_NAME)
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.map_pinned_fmt, lat, lng)
                binding.destinationStatus.visibility = View.VISIBLE
                binding.destinationStatus.text = placeName
                // The map is where the ring is actually visible, so its size comes back with the pin.
                data.getIntExtra(MapPickerActivity.EXTRA_RADIUS, 0)
                    .takeIf { it > 0 }
                    ?.let {
                        arrivalRadiusM = it.coerceIn(
                            MapPickerActivity.MIN_RADIUS_M,
                            MapPickerActivity.MAX_RADIUS_M
                        )
                        binding.arrivalRadiusSlider.value = arrivalRadiusM.toFloat()
                        updateArrivalRadiusText()
                    }
                updateDestinationEstimate()
            }
        }
    }

    private val calendarPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(requireContext(), R.string.calendar_permission_denied, Toast.LENGTH_LONG).show()
        }
        updateCalendarStatus()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetAlarmEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        // Open at about half-screen (time wheels visible), draggable up to full for the rest.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            isFitToContents = true
            skipCollapsed = false
            peekHeight = (resources.displayMetrics.heightPixels * 0.55f).toInt()
            state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupInlineTimePicker()
        setupCollapsibleSection(binding.sectionSoundHeader, binding.sectionSoundContent)
        setupCollapsibleSection(binding.sectionWakeupHeader, binding.sectionWakeupContent)
        setupCollapsibleSection(binding.sectionMoreHeader, binding.sectionMoreContent)
        binding.snoozeRow.setOnClickListener { showSnoozeDialog() }
        binding.missionRow.setOnClickListener { showMissionDialog() }
        binding.missionDifficultyRow.setOnClickListener { showMathDifficultyDialog() }
        binding.missionStepsRow.setOnClickListener { showStepsGoalDialog() }
        binding.missionQrScan.setOnClickListener {
            barcodeCapture.launch(CaptureActivity.intent(requireContext(), CaptureActivity.MODE_BARCODE))
        }
        binding.missionPhotoCapture.setOnClickListener {
            photoCapture.launch(CaptureActivity.intent(requireContext(), CaptureActivity.MODE_PHOTO))
        }
        binding.gentleWakeRow.setOnClickListener { showGentleWakeDialog() }
        binding.snoozeCoachingSwitch.setOnCheckedChangeListener { v, checked ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            snoozeCoaching = checked
        }
        binding.soundRow.setOnClickListener { showRingtonePicker() }
        binding.vibrationRow.setOnClickListener { showVibrationDialog() }

        binding.scheduleToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || !loaded) return@addOnButtonCheckedListener
            onScheduleTypeSelected(
                when (checkedId) {
                    R.id.schedule_once -> Alarm.SCHEDULE_ONCE
                    R.id.schedule_shift -> Alarm.SCHEDULE_SHIFT
                    R.id.schedule_calendar -> Alarm.SCHEDULE_CALENDAR
                    else -> Alarm.SCHEDULE_WEEKLY
                }
            )
        }

        // ---- Track-an-event dropdown + its option blocks ----
        binding.trackingDropdown.setOnItemClickListener { _, _, position, _ ->
            onTrackingModeSelected(position)
        }
        // Destination (Arrive at a place).
        binding.findButton.setOnClickListener { resolveDestination() }
        binding.pickOnMapButton.setOnClickListener {
            mapPicker.launch(
                MapPickerActivity.intent(requireContext(), destLat, destLng, arrivalRadiusM)
            )
        }
        binding.destinationInput.doAfterTextChanged {
            if (loaded) {
                destLat = null
                destLng = null
                clearDestinationEstimate()
            }
        }
        // Typing an address and hitting the keyboard's search key should just work — having to
        // notice and tap "Find" was why typed destinations felt like they weren't picked up.
        binding.destinationInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                resolveDestination()
                true
            } else {
                false
            }
        }
        binding.useEstimateButton.setOnClickListener { adoptEstimatedTime() }
        binding.arrivalRadiusSlider.addOnChangeListener { _, value, _ ->
            arrivalRadiusM = value.toInt()
            updateArrivalRadiusText()
        }
        binding.locationGrant.setOnClickListener { startLocationPermissionFlow() }
        // Notification (When a notification appears).
        binding.notifGrantButton.setOnClickListener { startNotificationAccessFlow() }
        binding.claudePresetButton.setOnClickListener {
            selectRule(TrackablePresets.claudeRule(getString(R.string.notif_preset_claude_label)))
        }
        binding.deliveryPresetButton.setOnClickListener {
            selectRule(TrackablePresets.deliveryPresetRule(getString(R.string.notif_preset_delivery_label)))
        }
        binding.transitPresetButton.setOnClickListener {
            selectRule(TrackablePresets.transitPresetRule(getString(R.string.notif_preset_transit_label)))
        }
        binding.trackManualButton.setOnClickListener { showManualAppPicker() }
        binding.waitWindowButton.setOnClickListener { showWaitWindowPicker() }
        binding.notifDiagnoseButton.setOnClickListener { showTrackingDiagnostics() }
        // Cooldown (When a limit resets).
        binding.cooldownServiceButton.setOnClickListener { showCooldownPicker() }
        binding.cooldownGrantButton.setOnClickListener { startNotificationAccessFlow() }

        // Shift editor
        binding.shiftWorkRow.setOnClickListener { showShiftCountDialog(isWork = true) }
        binding.shiftRestRow.setOnClickListener { showShiftCountDialog(isWork = false) }
        binding.shiftAnchorRow.setOnClickListener { showAnchorDatePicker() }

        // Calendar options
        binding.prepBufferRow.setOnClickListener { showPrepBufferDialog() }
        binding.calendarSkipSwitch.setOnCheckedChangeListener { v, checked ->
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            calendarSkipIfNoEvent = checked
            updateCalendarStatus()
        }
        binding.calendarRecompute.setOnClickListener { recomputeCalendarNow() }

        // "When it rings, open this app"
        binding.ringAppRow.setOnClickListener { showRingAppPicker() }
        binding.ringAppClear.setOnClickListener {
            actionPackage = null
            actionLabel = null
            updateRingAppText()
        }

        // Pause-for-date-range
        binding.pauseRow.setOnClickListener { showPauseRangePicker() }
        binding.pauseClear.setOnClickListener {
            pausedFrom = null
            pausedUntil = null
            updatePauseText()
        }

        // ---- Header action bar: Save / Cancel / Delete ----
        binding.saveButton.setOnClickListener {
            if (!loaded) return@setOnClickListener
            if (commitSave()) dismiss()
        }
        binding.cancelButton.setOnClickListener { attemptDiscard() }
        binding.headerDelete.setOnClickListener { confirmDelete() }
        // System back mirrors Cancel (discard, with a prompt if there are unsaved edits).
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_UP
            ) {
                attemptDiscard()
                true
            } else {
                false
            }
        }

        val alarmId = requireArguments().getLong(ARG_ALARM_ID, 0L)
        viewLifecycleOwner.lifecycleScope.launch {
            val repo = AlarmRepository.get(requireContext())
            val existing = if (alarmId > 0L) repo.getAlarm(alarmId) else null
            val trigger = if (alarmId > 0L) repo.getEventTrigger(alarmId) else null
            original = existing
            bindAlarm(existing, trigger)
            loaded = true
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have just returned from the notification-access settings screen.
        if (loaded && trackingMode == TRACK_NOTIF) refreshNotificationSection()
        if (loaded && trackingMode == TRACK_COOLDOWN) refreshCooldownSection()
        // The user may have just come back from the OS location settings page.
        if (loaded && trackingMode == TRACK_PLACE) updateLocationStatus()
    }

    private fun bindAlarm(alarm: Alarm?, trigger: EventTrigger?) {
        val defaults = alarm ?: run {
            val cal = Calendar.getInstance()
            Alarm(
                hour = (cal.get(Calendar.HOUR_OF_DAY) + 1) % 24,
                minute = 0,
                snoozeMinutes = Prefs.defaultSnoozeMinutes(requireContext())
            )
        }
        hour = defaults.hour
        minute = defaults.minute
        snoozeMinutes = defaults.snoozeMinutes
        missionType = defaults.missionType
        missionDifficulty = defaults.missionDifficulty
        missionBarcode = defaults.missionBarcode
        missionPhotoHash = defaults.missionPhotoHash
        gentleWakeMinutes = defaults.gentleWakeMinutes
        snoozeCoaching = defaults.snoozeCoaching
        // An event alarm has no weekly/shift/calendar schedule; give the schedule toggle a
        // valid default so converting it back to Off produces a normal alarm, not another EVENT.
        scheduleType = if (defaults.scheduleType == Alarm.SCHEDULE_EVENT) {
            Alarm.SCHEDULE_WEEKLY
        } else {
            defaults.scheduleType
        }
        soundUri = defaults.soundUri
        pausedFrom = defaults.pausedFrom
        pausedUntil = defaults.pausedUntil
        shiftWorkDays = if (defaults.shiftWorkDays > 0) defaults.shiftWorkDays else 4
        shiftRestDays = if (defaults.shiftRestDays > 0) defaults.shiftRestDays else 4
        shiftAnchorDate = if (defaults.shiftAnchorDate > 0) defaults.shiftAnchorDate else LocalDate.now().toEpochDay()
        prepBufferMinutes = defaults.prepBufferMinutes
        calendarSkipIfNoEvent = defaults.calendarSkipIfNoEvent
        actionPackage = defaults.actionPackage
        actionLabel = defaults.actionLabel

        binding.labelInput.setText(defaults.label)
        vibrationMode = defaults.vibrationMode
        binding.snoozeCoachingSwitch.isChecked = snoozeCoaching
        binding.calendarSkipSwitch.isChecked = calendarSkipIfNoEvent
        for (i in 0..6) {
            val checked = defaults.daysOfWeek and (1 shl i) != 0
            if (checked) binding.dayToggleGroup.check(dayButtonIds[i])
            else binding.dayToggleGroup.uncheck(dayButtonIds[i])
        }
        checkScheduleButton(scheduleType)
        binding.editorTitle.setText(if (alarm != null) R.string.edit_alarm else R.string.add_alarm)
        binding.headerDelete.visibility = if (alarm != null) View.VISIBLE else View.GONE
        updateTimeText()
        updateSnoozeText()
        updateMissionText()
        updateMissionOptions()
        updateGentleWakeText()
        updateVibrationText()
        updateSoundText()
        updatePauseText()
        updateRingAppText()
        updateShiftText()
        binding.prepBufferValue.text = getString(R.string.snooze_minutes_fmt, prepBufferMinutes)

        // ---- Track-an-event: derive the mode + config from the alarm's trigger ----
        trackingMode = when {
            alarm?.scheduleType != Alarm.SCHEDULE_EVENT -> TRACK_OFF
            trigger?.sourceType == EventTrigger.SOURCE_COOLDOWN -> TRACK_COOLDOWN
            trigger?.sourceType == EventTrigger.SOURCE_NOTIFICATION -> TRACK_NOTIF
            else -> TRACK_PLACE
        }
        arrivalRadiusM = (trigger?.arrivalRadiusM ?: MapPickerActivity.DEFAULT_RADIUS_M)
            .coerceIn(MapPickerActivity.MIN_RADIUS_M, MapPickerActivity.MAX_RADIUS_M)
        binding.arrivalRadiusSlider.value = arrivalRadiusM.toFloat()
        destLat = trigger?.destLat
        destLng = trigger?.destLng
        placeName = trigger?.placeName
        if (!placeName.isNullOrBlank() && trackingMode == TRACK_PLACE) {
            binding.destinationInput.setText(placeName)
            binding.destinationStatus.visibility = View.VISIBLE
            binding.destinationStatus.text = placeName
        }
        notificationRule = if (trackingMode == TRACK_NOTIF || trackingMode == TRACK_COOLDOWN) {
            NotificationMatchRule.fromJson(trigger?.configJson)
        } else {
            null
        }
        if (trackingMode == TRACK_COOLDOWN) {
            cooldownServiceName = trigger?.placeName ?: notificationRule?.label
            cooldownResetMillis = trigger?.fallbackEtaMillis
        }
        // An existing notification alarm keeps however its owner expressed the backstop: a stored
        // window, or a clock time (which is what every alarm saved before this row existed used).
        waitMinutes = if (trackingMode == TRACK_NOTIF) {
            if (alarm == null) DEFAULT_WAIT_MINUTES else notificationRule?.waitMinutes
        } else {
            DEFAULT_WAIT_MINUTES
        }
        setTrackingDropdownText(trackingMode)
        updateArrivalRadiusText()
        updateLocationStatus()
        updateRuleStatus()
        updateCooldownStatus()

        updateScheduleVisibility()
        updateTrackingVisibility()
        updateCalendarStatus()
        if (trackingMode == TRACK_NOTIF) refreshNotificationSection()
        if (trackingMode == TRACK_COOLDOWN) refreshCooldownSection()

        // Auto-expand advanced sections that already hold configured values so nothing looks lost.
        if (missionType != Alarm.MISSION_NONE || gentleWakeMinutes > 0 || snoozeCoaching) {
            setSectionExpanded(binding.sectionWakeupHeader, binding.sectionWakeupContent, true)
        }
        if (pausedFrom != null || actionPackage != null) {
            setSectionExpanded(binding.sectionMoreHeader, binding.sectionMoreContent, true)
        }

        baselineSignature = currentSignature()
    }

    /** Section header taps expand/collapse the rows beneath, swapping the chevron. */
    private fun setupCollapsibleSection(header: android.widget.TextView, content: View) {
        header.setOnClickListener {
            setSectionExpanded(header, content, content.visibility != View.VISIBLE)
        }
    }

    private fun setSectionExpanded(header: android.widget.TextView, content: View, expanded: Boolean) {
        content.visibility = if (expanded) View.VISIBLE else View.GONE
        header.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0, if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more, 0
        )
    }

    private fun setTrackingDropdownText(mode: Int) {
        val entries = resources.getStringArray(R.array.tracking_modes)
        binding.trackingDropdown.setText(entries.getOrElse(mode) { entries[0] }, false)
    }

    private fun onTrackingModeSelected(mode: Int) {
        trackingMode = mode
        // Leaving a tracking mode: make sure the schedule toggle holds a real (non-EVENT) type.
        if (mode == TRACK_OFF && scheduleType == Alarm.SCHEDULE_EVENT) {
            scheduleType = Alarm.SCHEDULE_WEEKLY
            checkScheduleButton(scheduleType)
        }
        if (mode == TRACK_NOTIF) refreshNotificationSection()
        if (mode == TRACK_COOLDOWN) refreshCooldownSection()
        if (mode == TRACK_PLACE) {
            updateLocationStatus()
            // Ask up front. Without location this mode can only ever ring at the guessed fallback
            // time, and burying the grant behind a small button meant it was simply never tapped.
            if (!GeofenceManager.hasLocationPermission(requireContext())) startLocationPermissionFlow()
        }
        updateScheduleVisibility()
        updateTrackingVisibility()
        updateCalendarStatus()
    }

    /**
     * Off shows the normal schedule/repeat controls and hides the tracking blocks. A tracking mode
     * hides all repeat controls (an event alarm is a one-shot fallback) and reveals its own block;
     * the set time then doubles as the guaranteed fallback ETA (captioned by mode).
     */
    private fun updateTrackingVisibility() {
        val tracking = trackingMode != TRACK_OFF
        // Hide every repeat/schedule control while tracking.
        binding.scheduleToggleGroup.visibility = if (tracking) View.GONE else View.VISIBLE
        if (tracking) {
            binding.calendarFloorCaption.visibility = View.GONE
            binding.dayToggleGroup.visibility = View.GONE
            binding.shiftOptions.visibility = View.GONE
            binding.calendarOptions.visibility = View.GONE
        }
        binding.destinationSection.visibility =
            if (trackingMode == TRACK_PLACE) View.VISIBLE else View.GONE
        binding.notificationSection.visibility =
            if (trackingMode == TRACK_NOTIF) View.VISIBLE else View.GONE
        binding.cooldownSection.visibility =
            if (trackingMode == TRACK_COOLDOWN) View.VISIBLE else View.GONE
        binding.fallbackCaption.visibility = if (tracking) View.VISIBLE else View.GONE
        // Notification mode expresses its backstop as a wait from now, so it needs its own row — and
        // while that row is in use the clock wheels are meaningless and would contradict it.
        val waiting = trackingMode == TRACK_NOTIF && waitMinutes != null
        binding.waitWindowRow.visibility = if (trackingMode == TRACK_NOTIF) View.VISIBLE else View.GONE
        binding.timePickerRow.visibility = if (waiting) View.GONE else View.VISIBLE
        updateWaitWindowText()
        if (waiting) {
            binding.fallbackCaption.text =
                getString(R.string.event_wait_caption_fmt, formatWait(waitMinutes ?: 0))
        } else {
            binding.fallbackCaption.setText(
                when (trackingMode) {
                    TRACK_NOTIF -> R.string.event_fallback_caption_notification
                    TRACK_COOLDOWN -> R.string.event_fallback_caption_cooldown
                    else -> R.string.event_fallback_caption
                }
            )
        }
        // Pause makes no sense for a one-shot event alarm.
        binding.pauseRow.visibility = if (tracking) View.GONE else View.VISIBLE
    }

    private fun updateWaitWindowText() {
        // With no window the clock wheels are showing, and the row is the way back to a wait.
        binding.waitWindowButton.text = waitMinutes?.let { formatWait(it) }
            ?: getString(R.string.event_wait_at_clock)
    }

    /** "20 min", "1 h", "1 h 30 min" — short enough to sit on a button. */
    private fun formatWait(minutes: Int): String = when {
        minutes < 60 -> getString(R.string.event_wait_minutes_fmt, minutes)
        minutes % 60 == 0 -> getString(R.string.event_wait_hours_fmt, minutes / 60)
        else -> getString(R.string.event_wait_hours_minutes_fmt, minutes / 60, minutes % 60)
    }

    /**
     * Offers the wait windows plus an escape hatch back to a wall-clock deadline. The options stop at
     * four hours because past that a clock time is the clearer way to say it, and that is exactly what
     * the last item switches to.
     */
    private fun showWaitWindowPicker() {
        val labels = (WAIT_CHOICES.map { formatWait(it) } +
            getString(R.string.event_wait_window_clock)).toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.event_wait_window_title)
            .setItems(labels) { _, which ->
                waitMinutes = WAIT_CHOICES.getOrNull(which) // past the last choice = null = use the clock
                updateTrackingVisibility()
            }
            .show()
    }

    private fun checkScheduleButton(type: String) {
        val id = when (type) {
            Alarm.SCHEDULE_ONCE -> R.id.schedule_once
            Alarm.SCHEDULE_SHIFT -> R.id.schedule_shift
            Alarm.SCHEDULE_CALENDAR -> R.id.schedule_calendar
            else -> R.id.schedule_weekly
        }
        binding.scheduleToggleGroup.check(id)
    }

    private fun onScheduleTypeSelected(type: String) {
        scheduleType = type
        if (type == Alarm.SCHEDULE_SHIFT && shiftWorkDays <= 0) {
            shiftWorkDays = 4
            shiftRestDays = 4
            shiftAnchorDate = LocalDate.now().toEpochDay()
        }
        if (type == Alarm.SCHEDULE_CALENDAR && !CalendarAlarm.hasPermission(requireContext())) {
            requestCalendarPermission()
        }
        updateShiftText()
        updateScheduleVisibility()
        updateCalendarStatus()
    }

    private fun updateScheduleVisibility() {
        val showDays = scheduleType == Alarm.SCHEDULE_WEEKLY || scheduleType == Alarm.SCHEDULE_CALENDAR
        binding.dayToggleGroup.visibility = if (showDays) View.VISIBLE else View.GONE
        binding.shiftOptions.visibility = if (scheduleType == Alarm.SCHEDULE_SHIFT) View.VISIBLE else View.GONE
        val calendar = scheduleType == Alarm.SCHEDULE_CALENDAR
        binding.calendarOptions.visibility = if (calendar) View.VISIBLE else View.GONE
        binding.calendarFloorCaption.visibility = if (calendar) View.VISIBLE else View.GONE
    }

    private fun currentDaysMask(): Int {
        var mask = 0
        for (i in 0..6) {
            if (binding.dayToggleGroup.checkedButtonIds.contains(dayButtonIds[i])) {
                mask = mask or (1 shl i)
            }
        }
        return mask
    }

    /** Kept name for all callers; now reflects [hour]/[minute] onto the inline wheels. */
    private fun updateTimeText() = pushTimeToPickers()

    private var timePickerReady = false

    /** Configure the inline hour/minute(/AM-PM) wheels and keep [hour]/[minute] in sync as they scroll. */
    private fun setupInlineTimePicker() {
        val is24 = Prefs.is24Hour(requireContext())
        val hp = binding.editHourPicker
        val mp = binding.editMinutePicker
        val ap = binding.editAmpmPicker

        mp.minValue = 0
        mp.maxValue = 59
        mp.setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        mp.wrapSelectorWheel = true

        if (is24) {
            ap.visibility = View.GONE
            hp.minValue = 0
            hp.maxValue = 23
            hp.setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        } else {
            hp.minValue = 1
            hp.maxValue = 12
            ap.visibility = View.VISIBLE
            ap.minValue = 0
            ap.maxValue = 1
            ap.displayedValues = arrayOf(getString(R.string.am_label), getString(R.string.pm_label))
            ap.wrapSelectorWheel = false
        }
        hp.wrapSelectorWheel = true
        timePickerReady = true
        pushTimeToPickers()

        val listener = NumberPicker.OnValueChangeListener { _, _, _ -> readTimeFromPickers() }
        hp.setOnValueChangedListener(listener)
        mp.setOnValueChangedListener(listener)
        ap.setOnValueChangedListener(listener)
    }

    /** Push the current [hour]/[minute] onto the wheels (no listener feedback — setValue is silent). */
    private fun pushTimeToPickers() {
        if (!timePickerReady) return
        binding.editMinutePicker.value = minute
        if (Prefs.is24Hour(requireContext())) {
            binding.editHourPicker.value = hour
        } else {
            binding.editHourPicker.value = if (hour % 12 == 0) 12 else hour % 12
            binding.editAmpmPicker.value = if (hour < 12) 0 else 1
        }
    }

    /** Fold the wheels back into [hour]/[minute] and run the same post-change updates the picker did. */
    private fun readTimeFromPickers() {
        hour = if (Prefs.is24Hour(requireContext())) {
            binding.editHourPicker.value
        } else {
            val h12 = binding.editHourPicker.value % 12
            if (binding.editAmpmPicker.value == 1) h12 + 12 else h12
        }
        minute = binding.editMinutePicker.value
        updateShiftText()
        updateCalendarStatus()
        if (trackingMode == TRACK_COOLDOWN) {
            cooldownResetMillis = AlarmTimes.nextTrigger(hour, minute, 0)
            updateCooldownStatus()
        }
    }

    private fun updateSnoozeText() {
        binding.snoozeValue.text = if (snoozeMinutes <= 0) {
            getString(R.string.snooze_off)
        } else {
            getString(R.string.snooze_minutes_fmt, snoozeMinutes)
        }
    }

    private fun updateMissionText() {
        binding.missionValue.text = when (missionType) {
            Alarm.MISSION_MATH -> getString(R.string.mission_math_fmt, difficultyLabel(missionDifficulty))
            Alarm.MISSION_PUZZLE -> getString(R.string.mission_puzzle_fmt, difficultyLabel(missionDifficulty))
            Alarm.MISSION_QR -> getString(R.string.mission_qr)
            Alarm.MISSION_STEPS -> getString(R.string.mission_steps_fmt, stepsGoalFor(missionDifficulty))
            Alarm.MISSION_PHOTO -> getString(R.string.mission_photo)
            else -> getString(R.string.mission_off)
        }
    }

    private fun difficultyLabel(difficulty: Int): String = when {
        difficulty <= 1 -> getString(R.string.difficulty_easy)
        difficulty == 2 -> getString(R.string.difficulty_medium)
        else -> getString(R.string.difficulty_hard)
    }

    private fun stepsGoalFor(difficulty: Int): Int = when (difficulty) {
        1 -> 10
        2 -> 20
        else -> 30
    }

    /** Shows the inline options block for the selected mission type (visibility-block pattern). */
    private fun updateMissionOptions() {
        binding.missionMathOptions.visibility =
            if (missionType == Alarm.MISSION_MATH || missionType == Alarm.MISSION_PUZZLE) View.VISIBLE else View.GONE
        binding.missionQrOptions.visibility =
            if (missionType == Alarm.MISSION_QR) View.VISIBLE else View.GONE
        binding.missionStepsOptions.visibility =
            if (missionType == Alarm.MISSION_STEPS) View.VISIBLE else View.GONE
        binding.missionPhotoOptions.visibility =
            if (missionType == Alarm.MISSION_PHOTO) View.VISIBLE else View.GONE

        binding.missionDifficultyValue.text = difficultyLabel(missionDifficulty)
        binding.missionStepsValue.text = getString(R.string.mission_steps_fmt, stepsGoalFor(missionDifficulty))
        binding.missionQrStatus.setText(
            if (missionBarcode.isNullOrBlank()) R.string.mission_qr_none else R.string.mission_qr_registered
        )
        binding.missionPhotoStatus.setText(
            if (missionPhotoHash.isNullOrBlank()) R.string.mission_photo_none else R.string.mission_photo_registered
        )
    }

    private fun updateGentleWakeText() {
        binding.gentleWakeValue.text = if (gentleWakeMinutes <= 0) {
            getString(R.string.gentle_wake_off)
        } else {
            getString(R.string.snooze_minutes_fmt, gentleWakeMinutes)
        }
    }

    private fun updateSoundText() {
        val uri = soundUri
        // "Only vibrate" silences the ring, so the tone row says so instead of naming a tone that
        // will never play. It stays tappable — picking one implies you want it back.
        binding.soundValue.text = when {
            vibrationMode == Alarm.VIBRATE_ONLY -> getString(R.string.sound_off)
            uri == null -> getString(R.string.sound_default)
            else -> RingtoneManager.getRingtone(requireContext(), Uri.parse(uri))
                ?.getTitle(requireContext())
                ?: getString(R.string.sound_default)
        }
    }

    private fun updatePauseText() {
        val from = pausedFrom
        val until = pausedUntil
        if (from == null || until == null) {
            binding.pauseValue.text = getString(R.string.pause_none)
            binding.pauseClear.visibility = View.GONE
        } else {
            // pausedUntil is the start of the day AFTER the last paused day.
            val lastDay = until - 1
            binding.pauseValue.text =
                getString(R.string.pause_range_fmt, Format.dateMedium(requireContext(), from), Format.dateMedium(requireContext(), lastDay))
            binding.pauseClear.visibility = View.VISIBLE
        }
    }

    private fun updateShiftText() {
        binding.shiftWorkValue.text = getString(R.string.shift_days_fmt, shiftWorkDays)
        binding.shiftRestValue.text = getString(R.string.shift_days_fmt, shiftRestDays)
        val anchorMillis = LocalDate.ofEpochDay(shiftAnchorDate)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        binding.shiftAnchorValue.text = Format.dateMedium(requireContext(), anchorMillis)
        binding.shiftPreview.text = getString(R.string.shift_preview_fmt, shiftPreviewText())
    }

    /** First few shift fire dates for the preview line. */
    private fun shiftPreviewText(): String {
        val temp = Alarm(
            hour = hour, minute = minute,
            scheduleType = Alarm.SCHEDULE_SHIFT,
            shiftWorkDays = shiftWorkDays,
            shiftRestDays = shiftRestDays,
            shiftAnchorDate = shiftAnchorDate
        )
        return AlarmTimes.nextShiftTriggers(temp, 3)
            .joinToString("  ·  ") { Format.dateTimeLine(requireContext(), it) }
    }

    private fun updateCalendarStatus() {
        if (scheduleType != Alarm.SCHEDULE_CALENDAR) return
        if (!CalendarAlarm.hasPermission(requireContext())) {
            binding.calendarStatus.text = getString(R.string.calendar_status_no_permission)
            return
        }
        val temp = Alarm(
            hour = hour, minute = minute,
            scheduleType = Alarm.SCHEDULE_CALENDAR,
            daysOfWeek = currentDaysMask(),
            prepBufferMinutes = prepBufferMinutes,
            calendarSkipIfNoEvent = calendarSkipIfNoEvent
        )
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val next = withContext(Dispatchers.IO) { NextTrigger.compute(ctx, temp) }
            val b = _binding ?: return@launch
            b.calendarStatus.text = if (next == null) {
                getString(R.string.calendar_status_none)
            } else {
                getString(R.string.calendar_status_next_fmt, Format.dateTimeLine(requireContext(), next))
            }
        }
    }

    private fun requestCalendarPermission() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.calendar_permission_title)
            .setMessage(R.string.calendar_permission_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.calendar_permission_grant) { _, _ ->
                calendarPermission.launch(android.Manifest.permission.READ_CALENDAR)
            }
            .show()
    }

    private fun recomputeCalendarNow() {
        updateCalendarStatus()
        val appContext = requireContext().applicationContext
        (appContext as AlarmTrackerApp).applicationScope.launch {
            AlarmScheduler.recomputeCalendarAndReschedule(appContext)
        }
        Toast.makeText(requireContext(), R.string.calendar_recomputed, Toast.LENGTH_SHORT).show()
    }

    private fun showTimePicker() {
        val is24 = Prefs.is24Hour(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_time_spinner, null)
        val hourPicker = view.findViewById<NumberPicker>(R.id.picker_hour)
        val minutePicker = view.findViewById<NumberPicker>(R.id.picker_minute)
        val ampmPicker = view.findViewById<NumberPicker>(R.id.picker_ampm)

        // Minute wheel is the same in both formats (00–59, zero-padded).
        minutePicker.minValue = 0
        minutePicker.maxValue = 59
        minutePicker.value = minute
        minutePicker.setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        minutePicker.wrapSelectorWheel = true

        if (is24) {
            ampmPicker.visibility = View.GONE
            hourPicker.minValue = 0
            hourPicker.maxValue = 23
            hourPicker.value = hour
            hourPicker.setFormatter { String.format(Locale.getDefault(), "%02d", it) }
        } else {
            // 12-hour wheels: hour 1–12 + an AM/PM wheel derived from the 24h `hour`.
            hourPicker.minValue = 1
            hourPicker.maxValue = 12
            hourPicker.value = if (hour % 12 == 0) 12 else hour % 12
            ampmPicker.visibility = View.VISIBLE
            ampmPicker.minValue = 0
            ampmPicker.maxValue = 1
            ampmPicker.displayedValues =
                arrayOf(getString(R.string.am_label), getString(R.string.pm_label))
            ampmPicker.value = if (hour < 12) 0 else 1
            ampmPicker.wrapSelectorWheel = false
        }
        hourPicker.wrapSelectorWheel = true

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.select_time)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                hour = if (is24) {
                    hourPicker.value
                } else {
                    // Fold 12-hour wheel + AM/PM back into a 24-hour value.
                    val h12 = hourPicker.value % 12          // 12 -> 0
                    if (ampmPicker.value == 1) h12 + 12 else h12
                }
                minute = minutePicker.value
                updateTimeText()
                updateShiftText()
                updateCalendarStatus()
                // In cooldown mode the set time IS the guaranteed reset time — keep them in sync.
                if (trackingMode == TRACK_COOLDOWN) {
                    cooldownResetMillis = AlarmTimes.nextTrigger(hour, minute, 0)
                    updateCooldownStatus()
                }
            }
            .show()
    }

    private fun showSnoozeDialog() {
        val values = resources.getStringArray(R.array.snooze_values)
        val entries = resources.getStringArray(R.array.snooze_entries)
        val checked = values.indexOf(snoozeMinutes.toString()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.snooze_dialog_title)
            .setSingleChoiceItems(entries, checked) { dialog, which ->
                snoozeMinutes = values[which].toInt()
                updateSnoozeText()
                dialog.dismiss()
            }
            .show()
    }

    private fun showMissionDialog() {
        val types = listOf(
            Alarm.MISSION_NONE, Alarm.MISSION_MATH, Alarm.MISSION_PUZZLE, Alarm.MISSION_QR,
            Alarm.MISSION_STEPS, Alarm.MISSION_PHOTO
        )
        val entries = arrayOf(
            getString(R.string.mission_off),
            getString(R.string.mission_math),
            getString(R.string.mission_puzzle),
            getString(R.string.mission_qr),
            getString(R.string.mission_steps),
            getString(R.string.mission_photo)
        )
        val checked = types.indexOf(missionType).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mission_dialog_title)
            .setSingleChoiceItems(entries, checked) { dialog, which ->
                onMissionTypeSelected(types[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun onMissionTypeSelected(type: String) {
        missionType = type
        // Give math/steps/puzzle a sensible default difficulty when switching in.
        if ((type == Alarm.MISSION_MATH || type == Alarm.MISSION_STEPS ||
                type == Alarm.MISSION_PUZZLE) && missionDifficulty < 1
        ) {
            missionDifficulty = 2
        }
        updateMissionText()
        updateMissionOptions()
        // Prompt the target capture right away so a QR/Photo mission isn't left without one.
        when (type) {
            Alarm.MISSION_QR -> if (missionBarcode.isNullOrBlank()) {
                barcodeCapture.launch(CaptureActivity.intent(requireContext(), CaptureActivity.MODE_BARCODE))
            }
            Alarm.MISSION_PHOTO -> if (missionPhotoHash.isNullOrBlank()) {
                photoCapture.launch(CaptureActivity.intent(requireContext(), CaptureActivity.MODE_PHOTO))
            }
        }
    }

    private fun showMathDifficultyDialog() {
        val entries = arrayOf(
            getString(R.string.difficulty_easy),
            getString(R.string.difficulty_medium),
            getString(R.string.difficulty_hard)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mission_difficulty)
            .setSingleChoiceItems(entries, (missionDifficulty - 1).coerceIn(0, 2)) { dialog, which ->
                missionDifficulty = which + 1
                updateMissionText()
                updateMissionOptions()
                dialog.dismiss()
            }
            .show()
    }

    private fun showStepsGoalDialog() {
        val entries = (1..3).map { getString(R.string.mission_steps_fmt, stepsGoalFor(it)) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.mission_steps_goal)
            .setSingleChoiceItems(entries, (missionDifficulty - 1).coerceIn(0, 2)) { dialog, which ->
                missionDifficulty = which + 1
                updateMissionText()
                updateMissionOptions()
                dialog.dismiss()
            }
            .show()
    }

    private fun showGentleWakeDialog() {
        val values = intArrayOf(0, 5, 10, 15, 20, 30, 45)
        val entries = values.map {
            if (it == 0) getString(R.string.gentle_wake_off) else getString(R.string.snooze_minutes_fmt, it)
        }.toTypedArray()
        val checked = values.indexOf(gentleWakeMinutes).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.gentle_wake_title)
            .setSingleChoiceItems(entries, checked) { dialog, which ->
                gentleWakeMinutes = values[which]
                updateGentleWakeText()
                dialog.dismiss()
            }
            .show()
    }

    /** Choosing a tone while in "only vibrate" means you want sound back — switch modes with them. */
    private fun onRingtoneChosen() {
        if (vibrationMode == Alarm.VIBRATE_ONLY) {
            vibrationMode = Alarm.VIBRATE_WITH_SOUND
            updateVibrationText()
        }
        updateSoundText()
    }

    private fun showRingtonePicker() {
        val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, getString(R.string.ringtone_picker_title))
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            )
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                soundUri?.let { Uri.parse(it) }
            )
        }
        try {
            ringtonePicker.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.sound_default, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showShiftCountDialog(isWork: Boolean) {
        val min = if (isWork) 1 else 0
        val max = 14
        val values = (min..max).toList()
        val entries = values.map { getString(R.string.shift_days_fmt, it) }.toTypedArray()
        val current = if (isWork) shiftWorkDays else shiftRestDays
        val checked = values.indexOf(current).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isWork) R.string.shift_work_days_title else R.string.shift_rest_days_title)
            .setSingleChoiceItems(entries, checked) { dialog, which ->
                if (isWork) shiftWorkDays = values[which] else shiftRestDays = values[which]
                updateShiftText()
                dialog.dismiss()
            }
            .show()
    }

    private fun showAnchorDatePicker() {
        val selection = LocalDate.ofEpochDay(shiftAnchorDate)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.shift_anchor_title)
            .setSelection(selection)
            .build()
        picker.addOnPositiveButtonClickListener { utcMillis ->
            shiftAnchorDate = Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
            updateShiftText()
        }
        picker.show(childFragmentManager, "anchor_picker")
    }

    private fun showPrepBufferDialog() {
        val values = intArrayOf(0, 5, 10, 15, 20, 30, 45, 60, 90)
        val entries = values.map { getString(R.string.snooze_minutes_fmt, it) }.toTypedArray()
        val checked = values.indexOf(prepBufferMinutes).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.prep_buffer_title)
            .setSingleChoiceItems(entries, checked) { dialog, which ->
                prepBufferMinutes = values[which]
                binding.prepBufferValue.text = getString(R.string.snooze_minutes_fmt, prepBufferMinutes)
                updateCalendarStatus()
                dialog.dismiss()
            }
            .show()
        binding.prepBufferValue.text = getString(R.string.snooze_minutes_fmt, prepBufferMinutes)
    }

    private fun showPauseRangePicker() {
        val builder = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText(R.string.pause_picker_title)
        val from = pausedFrom
        val until = pausedUntil
        if (from != null && until != null) {
            val startUtc = localMillisToUtcDay(from)
            val endUtc = localMillisToUtcDay(until - 1)
            builder.setSelection(androidx.core.util.Pair(startUtc, endUtc))
        }
        val picker = builder.build()
        picker.addOnPositiveButtonClickListener { selection ->
            val startDate = Instant.ofEpochMilli(selection.first).atZone(ZoneOffset.UTC).toLocalDate()
            val endDate = Instant.ofEpochMilli(selection.second).atZone(ZoneOffset.UTC).toLocalDate()
            val zone = ZoneId.systemDefault()
            pausedFrom = startDate.atStartOfDay(zone).toInstant().toEpochMilli()
            pausedUntil = endDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            updatePauseText()
        }
        picker.show(childFragmentManager, "pause_picker")
    }

    /** Local epoch millis -> the UTC-midnight millis MaterialDatePicker expects for that calendar day. */
    private fun localMillisToUtcDay(localMillis: Long): Long {
        val date = Instant.ofEpochMilli(localMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    /**
     * Commits the current state. Returns true if the sheet may close, false if it should stay
     * open because a tracking source still needs configuring (a hint toast was shown).
     */
    private fun commitSave(): Boolean {
        val b = _binding ?: return true
        return if (trackingMode == TRACK_OFF) {
            saveNormal(b)
            true
        } else {
            saveTracking(b)
        }
    }

    /** Cancel / back / swipe: discard edits, prompting first if there are any unsaved. */
    private fun attemptDiscard() {
        if (loaded && baselineSignature != null && currentSignature() != baselineSignature) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.discard_changes_title)
                .setMessage(R.string.discard_changes_body)
                .setNegativeButton(R.string.keep_editing, null)
                .setPositiveButton(R.string.discard) { _, _ -> dismiss() }
                .show()
        } else {
            dismiss()
        }
    }

    /** Delete is delegated to the parent fragment so it can offer UNDO. */
    private fun confirmDelete() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_alarm_confirm_title)
            .setNegativeButton(R.string.keep_editing, null)
            .setPositiveButton(R.string.delete_alarm) { _, _ ->
                val id = original?.id ?: 0L
                setFragmentResult(
                    REQUEST_KEY,
                    bundleOf(KEY_ACTION to ACTION_DELETE_REQUESTED, KEY_ALARM_ID to id)
                )
                dismiss()
            }
            .show()
    }

    /**
     * A stable string of every editable field. Two snapshots differing means the user changed
     * something; used to decide whether to prompt before discarding.
     */
    private fun currentSignature(): String = listOf(
        hour, minute, binding.labelInput.text?.toString()?.trim().orEmpty(),
        currentDaysMask(), scheduleType,
        vibrationMode, soundUri,
        snoozeMinutes, missionType, missionDifficulty, missionBarcode, missionPhotoHash,
        gentleWakeMinutes, snoozeCoaching, pausedFrom, pausedUntil,
        shiftWorkDays, shiftRestDays, shiftAnchorDate, prepBufferMinutes, calendarSkipIfNoEvent,
        trackingMode, destLat, destLng, placeName,
        cooldownServiceName, cooldownResetMillis, notificationRule?.toJson(),
        waitMinutes,
        actionPackage, actionLabel
    ).joinToString("|")

    /** Off: an ordinary time alarm. Converts a former event alarm back and tears its trigger down. */
    private fun saveNormal(b: SheetAlarmEditBinding) {
        val base = original ?: Alarm(hour = hour, minute = minute)
        val mask = currentDaysMask()
        // Derive the persisted schedule type: a Weekly alarm with no days chosen is a one-shot.
        val effectiveType = when (scheduleType) {
            Alarm.SCHEDULE_WEEKLY -> if (mask == 0) Alarm.SCHEDULE_ONCE else Alarm.SCHEDULE_WEEKLY
            else -> scheduleType
        }
        // daysOfWeek is meaningful only for Weekly/Calendar; Once and Shift store 0.
        val persistedDays = when (effectiveType) {
            Alarm.SCHEDULE_WEEKLY, Alarm.SCHEDULE_CALENDAR -> mask
            else -> 0
        }
        // Re-enable on a schedule change of a disabled alarm, as before.
        val scheduleChanged = original != null &&
            (hour != original!!.hour || minute != original!!.minute ||
                persistedDays != original!!.daysOfWeek || effectiveType != original!!.scheduleType)
        val edited = base.copy(
            hour = hour,
            minute = minute,
            label = b.labelInput.text?.toString()?.trim().orEmpty(),
            enabled = original == null || original!!.enabled || scheduleChanged,
            scheduleType = effectiveType,
            daysOfWeek = persistedDays,
            soundEnabled = Alarm.soundAndVibrateFor(vibrationMode).first,
            soundUri = soundUri,
            vibrate = Alarm.soundAndVibrateFor(vibrationMode).second,
            snoozeMinutes = snoozeMinutes,
            missionType = missionType,
            missionDifficulty = missionDifficulty,
            missionBarcode = missionBarcode,
            missionPhotoHash = missionPhotoHash,
            gentleWakeMinutes = gentleWakeMinutes,
            snoozeCoaching = snoozeCoaching,
            pausedFrom = pausedFrom,
            pausedUntil = pausedUntil,
            shiftWorkDays = shiftWorkDays,
            shiftRestDays = shiftRestDays,
            shiftAnchorDate = shiftAnchorDate,
            prepBufferMinutes = prepBufferMinutes,
            calendarSkipIfNoEvent = calendarSkipIfNoEvent,
            actionPackage = actionPackage,
            actionLabel = actionLabel
        )
        // Converting a saved event alarm back to Off: tear its trigger down even if nothing else changed.
        val wasEvent = original?.scheduleType == Alarm.SCHEDULE_EVENT
        if (edited == original && !wasEvent) return // nothing changed — no save, no snackbar

        setFragmentResult(REQUEST_KEY, bundleOf(KEY_ACTION to ACTION_SAVED))
        val appContext = requireContext().applicationContext
        (appContext as AlarmTrackerApp).applicationScope.launch {
            val repo = AlarmRepository.get(appContext)
            val id = repo.save(edited)
            if (wasEvent) {
                EventAlarmCoordinator.onTriggerDisabled(appContext, id)
                repo.deleteEventTrigger(id)
            }
            AlarmScheduler.rescheduleNext(appContext)
        }
    }

    /**
     * A tracking alarm (Arrive at a place / When a notification appears). Saved as an EVENT alarm
     * whose set time is mirrored into the guaranteed fallback ETA, plus an [EventTrigger] describing
     * the source; the [EventAlarmCoordinator] then arms it. Skips saving (with a hint) if the
     * chosen source isn't configured yet.
     */
    private fun saveTracking(b: SheetAlarmEditBinding): Boolean {
        val place = trackingMode == TRACK_PLACE
        val cooldown = trackingMode == TRACK_COOLDOWN
        if (place && (destLat == null || destLng == null)) {
            Toast.makeText(requireContext(), R.string.event_needs_destination, Toast.LENGTH_LONG).show()
            return false
        }
        // Saving a place alarm with no location grant produces an alarm that can only ever ring at
        // the typed time — it will never detect arrival or correct itself. Say so before it happens
        // rather than letting the user find out when it rings at the wrong moment.
        if (place && !locationWarningAcknowledged &&
            !GeofenceManager.hasLocationPermission(requireContext())
        ) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.event_location_missing_title)
                .setMessage(R.string.event_location_missing_body)
                .setNegativeButton(R.string.event_save_anyway) { _, _ ->
                    locationWarningAcknowledged = true
                    if (commitSave()) dismiss()
                }
                .setPositiveButton(R.string.event_location_grant) { _, _ -> startLocationPermissionFlow() }
                .show()
            return false
        }
        if (cooldown && cooldownServiceName.isNullOrBlank()) {
            Toast.makeText(requireContext(), R.string.cooldown_needs_service, Toast.LENGTH_LONG).show()
            return false
        }
        if (!place && !cooldown && notificationRule == null) {
            Toast.makeText(requireContext(), R.string.notif_needs_rule, Toast.LENGTH_LONG).show()
            return false
        }
        // Cooldown's fallback is the precise reset instant. A notification alarm with a wait window
        // counts from now. Everything else derives it from the set time.
        val notifWait = if (!place && !cooldown) waitMinutes else null
        val fallbackEta = when {
            cooldown -> cooldownResetMillis ?: AlarmTimes.nextTrigger(hour, minute, 0)
            notifWait != null -> System.currentTimeMillis() + notifWait * 60_000L
            else -> AlarmTimes.nextTrigger(hour, minute, 0)
        }
        // The alarm's own hour/minute must agree with that instant, since the list, the widget and the
        // ring notification all read the alarm rather than the trigger.
        if (notifWait != null) {
            val at = Calendar.getInstance().apply { timeInMillis = fallbackEta }
            hour = at.get(Calendar.HOUR_OF_DAY)
            minute = at.get(Calendar.MINUTE)
        }
        val sourceType = when {
            place -> EventTrigger.SOURCE_GEOFENCE
            cooldown -> EventTrigger.SOURCE_COOLDOWN
            else -> EventTrigger.SOURCE_NOTIFICATION
        }
        val base = original ?: Alarm(hour = hour, minute = minute, scheduleType = Alarm.SCHEDULE_EVENT)
        val edited = base.copy(
            hour = hour,
            minute = minute,
            label = b.labelInput.text?.toString()?.trim().orEmpty(),
            enabled = true,
            scheduleType = Alarm.SCHEDULE_EVENT,
            daysOfWeek = 0,
            soundEnabled = Alarm.soundAndVibrateFor(vibrationMode).first,
            soundUri = soundUri,
            vibrate = Alarm.soundAndVibrateFor(vibrationMode).second,
            snoozeMinutes = snoozeMinutes,
            missionType = missionType,
            missionDifficulty = missionDifficulty,
            missionBarcode = missionBarcode,
            missionPhotoHash = missionPhotoHash,
            gentleWakeMinutes = gentleWakeMinutes,
            snoozeCoaching = snoozeCoaching,
            actionPackage = actionPackage,
            actionLabel = actionLabel,
            // A one-shot event alarm never carries a pause window.
            pausedFrom = null,
            pausedUntil = null
        )
        // Store the window alongside the rule so re-opening the editor shows "30 min" again rather
        // than the clock time it happened to work out to.
        val ruleJson = notificationRule?.copy(waitMinutes = notifWait)?.toJson()
        setFragmentResult(REQUEST_KEY, bundleOf(KEY_ACTION to ACTION_SAVED))
        val appContext = requireContext().applicationContext
        (appContext as AlarmTrackerApp).applicationScope.launch {
            val repo = AlarmRepository.get(appContext)
            val id = repo.save(edited)
            val existingTrigger = repo.getEventTrigger(id)
            val trigger = (existingTrigger ?: EventTrigger(alarmId = id)).copy(
                alarmId = id,
                sourceType = sourceType,
                enabled = true,
                // placeName doubles as the tracked thing's display name (a place, or a service).
                placeName = when {
                    place -> placeName
                    cooldown -> cooldownServiceName
                    else -> null
                },
                destLat = if (place) destLat else null,
                destLng = if (place) destLng else null,
                arrivalRadiusM = arrivalRadiusM,
                configJson = if (place) null else ruleJson,
                currentEtaMillis = null,
                fallbackEtaMillis = fallbackEta,
                lastSignalAt = null,
                lastDistanceM = null
            )
            repo.saveEventTrigger(trigger)
            EventAlarmCoordinator.onTriggerConfigured(appContext, id)
            AlarmScheduler.rescheduleNext(appContext)
        }
        return true
    }

    // ---- Track: Arrive at a place (geofence) ----

    /**
     * Turns whatever the user typed into a destination. Runs both providers behind
     * [PlaceSearch] (framework geocoder, then the keyless Photon endpoint), and when several
     * places match it asks which one rather than silently taking the first.
     *
     * The search is biased to the user's own position when we have permission, because an unbiased
     * lookup is worldwide — that is why typing a local street used to come back with matches in
     * other countries.
     */
    private fun resolveDestination() {
        val query = binding.destinationInput.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) return
        hideKeyboard()
        // Both place providers are online services. Offline, the old code came back empty and blamed
        // the query ("Couldn't find that place. Try a more specific address.") — so check first.
        if (NetworkState.blocked(requireContext())) {
            binding.destinationStatus.visibility = View.VISIBLE
            binding.destinationStatus.text =
                NetworkState.explain(requireContext(), R.string.net_service_place_search)
            NetworkState.promptToConnect(requireContext(), R.string.net_feature_place_search)
            return
        }
        clearDestinationEstimate()
        binding.destinationStatus.visibility = View.VISIBLE
        binding.destinationStatus.setText(R.string.event_resolving)
        val ctx = requireContext().applicationContext
        val fineGranted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        viewLifecycleOwner.lifecycleScope.launch {
            val here = if (fineGranted) currentLocation(ctx) else null
            here?.let { LocationState.remember(ctx, it) }
            // With Location switched off there is no fix at all, and an unbiased search goes global —
            // so fall back to the last position we ever managed to record.
            val bias = here?.let { it.latitude to it.longitude } ?: LocationState.lastKnown(ctx)
            val results = PlaceSearch.search(ctx, query, 6, bias?.first, bias?.second)
            val b = _binding ?: return@launch
            when {
                results.isEmpty() -> {
                    destLat = null
                    destLng = null
                    // Empty means "no such place" only if the search actually reached a provider.
                    b.destinationStatus.text =
                        if (NetworkState.status(ctx) == NetworkState.Status.ONLINE) {
                            getString(R.string.event_not_found)
                        } else {
                            NetworkState.explain(ctx, R.string.net_service_place_search)
                        }
                }
                results.size == 1 -> applyResolvedPlace(results.first())
                else -> showPlaceChoices(results, bias)
            }
        }
    }

    /**
     * Lets the user choose between matches — with the distance on every row, because the label alone
     * does not tell you that "Subhash Chowk, Jaipur" is 230 km away. Results arrive most-relevant
     * first, so the top row is normally the one they meant.
     */
    private fun showPlaceChoices(
        results: List<GeoResolver.Place>,
        bias: Pair<Double, Double>?
    ) {
        val ctx = requireContext()
        fun metres(place: GeoResolver.Place): Double? = bias?.let {
            GeoResolver.distanceMeters(it.first, it.second, place.lat, place.lng)
        }
        val labels = results.mapIndexed { index, place ->
            val distance = metres(place)?.let { EventAlarmCoordinator.formatKm(ctx, it.toInt()) }
            when {
                distance == null -> place.label
                index == 0 -> getString(R.string.event_pick_item_closest_fmt, place.label, distance)
                else -> getString(R.string.event_pick_item_fmt, place.label, distance)
            }
        }.toTypedArray()
        // If everything on offer is far away, say so rather than letting them assume it's local.
        val allFar = results.all { (metres(it) ?: 0.0) > FAR_MATCH_M }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (allFar) R.string.event_pick_match_far else R.string.event_pick_match)
            .setItems(labels) { _, which -> applyResolvedPlace(results[which]) }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                _binding?.destinationStatus?.setText(R.string.event_not_found)
            }
            .show()
    }

    private fun applyResolvedPlace(place: GeoResolver.Place) {
        destLat = place.lat
        destLng = place.lng
        placeName = place.label
        val b = _binding ?: return
        b.destinationStatus.visibility = View.VISIBLE
        b.destinationStatus.text = place.label
        updateDestinationEstimate()
    }

    private fun hideKeyboard() {
        val b = _binding ?: return
        b.destinationInput.clearFocus()
        requireContext().getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(b.destinationInput.windowToken, 0)
    }

    // ---- Rough "how far away is this?" estimate ----

    private fun clearDestinationEstimate() {
        estimatedArrivalMillis = null
        _binding?.destinationEstimate?.visibility = View.GONE
        _binding?.useEstimateButton?.visibility = View.GONE
    }

    /**
     * Takes ONE location fix and shows how far the chosen destination is and roughly how long getting
     * there takes, plus a one-tap way to adopt that arrival time as the alarm's time. Without this
     * the editor gave no feedback at all after picking a place, and a guessed time silently became
     * the alarm.
     *
     * The time comes from the real road route when [RouteService] can supply one, and only falls back
     * to straight-line distance at an assumed speed when it can't — a crow-flies estimate is badly
     * optimistic anywhere with a river, a bay or a one-way system in the way.
     */
    @SuppressLint("MissingPermission") // guarded by the permission check below
    private fun updateDestinationEstimate() {
        val lat = destLat ?: return
        val lng = destLng ?: return
        val b = _binding ?: return
        val fineGranted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted) {
            b.destinationEstimate.visibility = View.VISIBLE
            b.destinationEstimate.setText(R.string.event_estimate_needs_location)
            b.useEstimateButton.visibility = View.GONE
            return
        }
        b.destinationEstimate.visibility = View.VISIBLE
        b.destinationEstimate.setText(R.string.event_estimate_measuring)
        b.useEstimateButton.visibility = View.GONE
        val ctx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val here = currentLocation(ctx)
            val binding = _binding ?: return@launch
            if (here == null) {
                binding.destinationEstimate.setText(
                    if (LocationState.servicesEnabled(ctx)) R.string.event_estimate_unavailable
                    else R.string.event_estimate_services_off
                )
                // Tapping the line opens Location settings, since that is the whole fix.
                if (!LocationState.servicesEnabled(ctx)) {
                    binding.destinationEstimate.setOnClickListener { openLocationSettings() }
                }
                return@launch
            }
            binding.destinationEstimate.setOnClickListener(null)
            LocationState.remember(ctx, here)
            val route = RouteService.driving(here.latitude, here.longitude, lat, lng)
            val straight = GeoResolver.distanceMeters(here.latitude, here.longitude, lat, lng)
            if (route != null && route.durationSeconds > 0) {
                estimateSpeedKmh = (route.distanceMeters / route.durationSeconds * 3.6)
                    .coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH)
                updateArrivalRadiusText()
            }
            val meters = route?.distanceMeters ?: straight
            val minutes = route?.minutes
                ?: (straight / (ASSUMED_SPEED_KMH * 1000.0 / 3600.0) / 60.0).toInt().coerceAtLeast(1)
            binding.destinationEstimate.text = getString(
                if (route != null) R.string.event_estimate_road_fmt else R.string.event_estimate_fmt,
                EventAlarmCoordinator.formatKm(ctx, meters.toInt()),
                minutes
            )
            val arrival = System.currentTimeMillis() + minutes * 60_000L
            estimatedArrivalMillis = arrival
            val cal = Calendar.getInstance().apply { timeInMillis = arrival }
            binding.useEstimateButton.visibility = View.VISIBLE
            binding.useEstimateButton.text = getString(
                R.string.event_estimate_use_fmt,
                Format.timeText(
                    ctx,
                    cal.get(Calendar.HOUR_OF_DAY),
                    cal.get(Calendar.MINUTE)
                )
            )
        }
    }

    /** Moves the alarm's time to the estimated arrival, so the fallback stops being a guess. */
    private fun adoptEstimatedTime() {
        val arrival = estimatedArrivalMillis ?: return
        val cal = Calendar.getInstance().apply { timeInMillis = arrival }
        hour = cal.get(Calendar.HOUR_OF_DAY)
        minute = cal.get(Calendar.MINUTE)
        updateTimeText()
        _binding?.useEstimateButton?.visibility = View.GONE
        Toast.makeText(requireContext(), R.string.event_estimate_applied, Toast.LENGTH_SHORT).show()
    }

    /**
     * One balanced-power fix, falling back to the last known one. The fallback matters: on a phone
     * with location services off or a flaky vendor provider, getCurrentLocation just returns null and
     * the editor used to say "couldn't get a location fix" and offer no estimate at all — a stale
     * fix is a far better basis for "how far is this" than nothing.
     */
    @SuppressLint("MissingPermission") // caller checked ACCESS_FINE_LOCATION
    private suspend fun currentLocation(context: android.content.Context): Location? {
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setMaxUpdateAgeMillis(2 * 60_000)
            .setDurationMillis(20_000)
            .build()
        val fresh = suspendCancellableCoroutine { cont ->
            try {
                client.getCurrentLocation(request, null)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            } catch (_: SecurityException) {
                cont.resume(null)
            }
        }
        if (fresh != null) return fresh
        return suspendCancellableCoroutine { cont ->
            try {
                client.lastLocation
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            } catch (_: SecurityException) {
                cont.resume(null)
            }
        }
    }

    /**
     * States the ring in both forms the user cares about: how close, and how long before arrival that
     * works out to. The minutes use the real speed of the last routed estimate when there is one.
     */
    private fun updateArrivalRadiusText() {
        val ctx = requireContext()
        val pretty = EventAlarmCoordinator.formatKm(ctx, arrivalRadiusM)
        val leadMinutes = (arrivalRadiusM / (estimateSpeedKmh * 1000.0 / 3600.0) / 60.0).toInt()
        binding.arrivalRadiusValue.text = if (leadMinutes >= 1) {
            getString(R.string.event_arrival_radius_lead_fmt, pretty, leadMinutes)
        } else {
            getString(R.string.event_arrival_radius_fmt, pretty)
        }
    }

    // Location permission (two-step: foreground, then background w/ rationale).

    private fun startLocationPermissionFlow() {
        val fineGranted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        // Nothing to grant if the problem is the device-wide toggle — send them where it lives.
        if (fineGranted && !LocationState.servicesEnabled(requireContext())) {
            openLocationSettings()
            return
        }
        if (!fineGranted) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.event_location_rationale_title)
                .setMessage(R.string.event_location_rationale_body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.event_continue) { _, _ ->
                    fineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                .show()
        } else {
            maybeRequestBackground()
        }
    }

    private fun maybeRequestBackground() {
        if (Build.VERSION.SDK_INT < 29) {
            updateLocationStatus()
            return
        }
        val bgGranted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (bgGranted) {
            updateLocationStatus()
            return
        }
        // API 30+ refuses to show a background-location prompt at all: requesting it just returns
        // "denied" with no UI, which is exactly why nothing appeared to happen. From Android 11 on,
        // "Allow all the time" can only be chosen on the app's own settings page.
        val viaSettings = Build.VERSION.SDK_INT >= 30
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.event_location_background_title)
            .setMessage(
                if (viaSettings) R.string.event_location_background_settings_body
                else R.string.event_location_background_body
            )
            .setNegativeButton(android.R.string.cancel) { _, _ -> updateLocationStatus() }
            .setPositiveButton(
                if (viaSettings) R.string.event_location_open_settings else R.string.event_continue
            ) { _, _ ->
                if (viaSettings) openAppLocationSettings()
                else backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            .show()
    }

    /** App details page — the only place "Allow all the time" can be granted on Android 11+. */
    private fun openAppLocationSettings() {
        try {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().packageName, null)
                )
            )
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.event_location_status_denied, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * The grant state AND whether the phone can actually locate anything. Reporting only the grant was
     * misleading: with the system Location toggle off this row said "Granted — arrival will refine the
     * alarm" while nothing could possibly refine it.
     */
    private fun openLocationSettings() {
        try {
            startActivity(LocationState.settingsIntent())
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.event_estimate_services_off, Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun updateLocationStatus() {
        val b = _binding ?: return
        val fineGranted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val full = GeofenceManager.hasLocationPermission(requireContext())
        val servicesOff = !LocationState.servicesEnabled(requireContext())
        when {
            fineGranted && servicesOff -> {
                b.locationStatus.setText(R.string.event_location_status_services_off)
                b.locationGrant.visibility = View.VISIBLE
            }
            full -> {
                b.locationStatus.setText(R.string.event_location_status_granted)
                b.locationGrant.visibility = View.GONE
            }
            fineGranted -> {
                b.locationStatus.setText(R.string.event_location_status_foreground)
                b.locationGrant.visibility = View.VISIBLE
            }
            else -> {
                b.locationStatus.setText(R.string.event_location_status_denied)
                b.locationGrant.visibility = View.VISIBLE
            }
        }
    }

    // ---- Track: When a notification appears ----

    private fun startNotificationAccessFlow() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.notif_access_rationale_title)
            .setMessage(R.string.notif_access_rationale_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.event_continue) { _, _ ->
                try {
                    startActivity(NotificationAccess.settingsIntent())
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), R.string.notif_access_status_denied, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    /** Refreshes the grant status + the "Trackable right now" list from the live listener. */
    private fun refreshNotificationSection() {
        val b = _binding ?: return
        val granted = NotificationAccess.isGranted(requireContext())
        // Granted but the OS may not have bound the listener yet — distinguish "connecting".
        val connecting = granted && !AlarmNotificationListener.isConnected()
        b.notifGrantStatus.setText(
            when {
                connecting -> R.string.notif_access_status_connecting
                granted -> R.string.notif_access_status_granted
                else -> R.string.notif_access_status_denied
            }
        )
        b.notifGrantButton.visibility = if (granted) View.GONE else View.VISIBLE

        b.trackableContainer.removeAllViews()
        val actives = if (granted) AlarmNotificationListener.activeNotifications(requireContext()) else emptyList()
        b.trackableEmpty.visibility = if (actives.isEmpty() && !connecting) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(requireContext())
        for (active in actives) {
            val item = ItemTrackableNotificationBinding.inflate(inflater, b.trackableContainer, false)
            item.trackableApp.text = active.appLabel
            item.trackableSnippet.text = active.snippet
            val rule = TrackablePresets.forActive(active)
            item.trackableSuggestion.setText(TrackablePresets.suggestionRes(active))
            val pick = { selectRule(rule) }
            item.root.setOnClickListener { pick() }
            item.trackablePick.setOnClickListener { pick() }
            b.trackableContainer.addView(item.root)
        }
    }

    /**
     * "Is this tracking actually alive?" — answered with facts rather than reassurance.
     *
     * Notification tracking is invisible by nature: it costs nothing, does nothing until the tracked
     * app posts something, and fires exactly once. There was no way to tell a working setup from a
     * silently broken one (access revoked, listener unbound after an OEM kill, watching an app that
     * isn't installed) short of waiting for a real delivery to not wake you. This shows the four things
     * that decide it.
     */
    private fun showTrackingDiagnostics() {
        val ctx = requireContext()
        val granted = NotificationAccess.isGranted(ctx)
        val connected = AlarmNotificationListener.isConnected()
        val seen = AlarmNotificationListener.postedCount
        val lastAt = AlarmNotificationListener.lastPostedAt
        val rule = notificationRule

        val lines = mutableListOf<String>()
        lines += getString(
            if (granted) R.string.diag_access_on else R.string.diag_access_off
        )
        lines += getString(
            if (connected) R.string.diag_listener_on else R.string.diag_listener_off
        )
        // The count is the real proof: if notifications are arriving, the pipe works, and any failure
        // is in the rule rather than in the plumbing.
        lines += if (seen > 0) {
            val at = Calendar.getInstance().apply { timeInMillis = lastAt }
            val clock = Format.timeText(ctx, at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE))
            getString(R.string.diag_seen_fmt, seen, clock)
        } else {
            getString(R.string.diag_seen_none)
        }
        if (rule == null) {
            lines += getString(R.string.diag_no_rule)
        } else {
            val installed = rule.packages.filter { pkg ->
                ctx.packageManager.getLaunchIntentForPackage(pkg) != null
            }
            lines += when {
                installed.isEmpty() -> getString(
                    R.string.diag_rule_not_installed_fmt, rule.packages.joinToString(", ")
                )
                else -> getString(R.string.diag_rule_watching_fmt, installed.size, rule.packages.size)
            }
            val onScreen = AlarmNotificationListener.matchingActive(rule)
            lines += if (onScreen.isEmpty()) {
                getString(R.string.diag_no_match_now)
            } else {
                getString(R.string.diag_match_now_fmt, onScreen.first().snippet)
            }
        }
        lines += getString(R.string.diag_battery_note)

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.diag_title)
            .setMessage(lines.joinToString("\n\n"))
            .setNegativeButton(android.R.string.ok, null)
            .apply {
                if (!granted) {
                    setPositiveButton(R.string.notif_access_grant) { _, _ -> startNotificationAccessFlow() }
                }
            }
            .show()
    }

    /** Prominent "track anything" path: pick an installed app, then the keywords that mean "done". */
    private fun showManualAppPicker() {
        pickInstalledApp(R.string.notif_manual_pick_app) { pkg, appLabel ->
            showKeywordDialog(pkg, appLabel)
        }
    }

    /** Every launchable app on the device, minus ourselves, alphabetically. */
    private fun pickInstalledApp(titleRes: Int, onPicked: (String, String) -> Unit) {
        val pm = requireContext().packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .mapNotNull { ri ->
                val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == requireContext().packageName) return@mapNotNull null
                pkg to ri.loadLabel(pm).toString()
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) {
            Toast.makeText(requireContext(), R.string.notif_trackable_empty, Toast.LENGTH_LONG).show()
            return
        }
        val labels = apps.map { it.second }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setItems(labels) { _, which -> onPicked(apps[which].first, apps[which].second) }
            .show()
    }

    // ---- "When it rings, open this app" ----

    private fun showRingAppPicker() {
        pickInstalledApp(R.string.ring_app_pick_title) { pkg, label ->
            actionPackage = pkg
            actionLabel = label
            updateRingAppText()
        }
    }

    // ---- Vibration mode ----

    private fun showVibrationDialog() {
        val entries = resources.getStringArray(R.array.vibration_modes)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.row_vibrate)
            .setSingleChoiceItems(entries, vibrationMode.coerceIn(0, entries.lastIndex)) { dialog, which ->
                vibrationMode = which
                updateVibrationText()
                updateSoundText()
                // Warn only when the user actively picks a vibrating mode — a device with no motor
                // can't honour it, and "only vibrate" there would be an alarm that does nothing.
                if (which != Alarm.VIBRATE_OFF && !deviceCanVibrate()) {
                    Toast.makeText(requireContext(), R.string.vibrate_unsupported, Toast.LENGTH_LONG).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateVibrationText() {
        val b = _binding ?: return
        val entries = resources.getStringArray(R.array.vibration_modes)
        b.vibrationValue.text = entries.getOrElse(vibrationMode) { entries[0] }
    }

    private fun deviceCanVibrate(): Boolean =
        requireContext().getSystemService(android.os.Vibrator::class.java)?.hasVibrator() == true

    private fun updateRingAppText() {
        val b = _binding ?: return
        val label = actionLabel
        if (actionPackage == null || label == null) {
            b.ringAppValue.setText(R.string.ring_app_none)
            b.ringAppClear.visibility = View.GONE
        } else {
            b.ringAppValue.text = getString(R.string.ring_open_fmt, label)
            b.ringAppClear.visibility = View.VISIBLE
        }
    }

    private fun showKeywordDialog(pkg: String, appLabel: String) {
        val dp = resources.displayMetrics.density
        val inputLayout = TextInputLayout(requireContext()).apply {
            setPadding((24 * dp).toInt(), (4 * dp).toInt(), (24 * dp).toInt(), 0)
            hint = getString(R.string.notif_manual_keywords_hint)
        }
        val input = TextInputEditText(inputLayout.context)
        inputLayout.addView(input)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.notif_manual_keywords_title)
            .setView(inputLayout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.event_continue) { _, _ ->
                val keywords = input.text?.toString().orEmpty()
                    .split(',', ';', '\n')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                selectRule(TrackablePresets.keywordRule(pkg, appLabel, keywords))
            }
            .show()
    }

    private fun selectRule(rule: NotificationMatchRule) {
        notificationRule = rule
        updateRuleStatus()
    }

    private fun updateRuleStatus() {
        val b = _binding ?: return
        val rule = notificationRule
        if (rule == null) {
            b.notificationRuleStatus.setText(R.string.notif_rule_none)
            return
        }
        val name = rule.label ?: rule.packages.firstOrNull().orEmpty()
        val res = when (rule.condition) {
            NotificationMatchRule.CONDITION_ETA -> R.string.notif_rule_arrival_fmt
            NotificationMatchRule.CONDITION_REMOVED -> R.string.notif_rule_done_fmt
            else -> R.string.notif_rule_keyword_fmt
        }
        b.notificationRuleStatus.text = getString(res, name)
    }

    // ---- Track: When a limit resets (cooldown) ----

    /** Grouped picker of well-known services whose limit/energy/quota replenishes on a timer. */
    private fun showCooldownPicker() {
        val presets = CooldownPresets.ALL
        val labels = presets.map {
            getString(R.string.cooldown_entry_fmt, getString(it.labelRes), getString(it.packRes))
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.cooldown_pick_title)
            .setItems(labels) { _, which -> onCooldownPresetSelected(presets[which]) }
            .show()
    }

    private fun onCooldownPresetSelected(preset: CooldownPresets.Preset) {
        val name = getString(preset.labelRes)
        cooldownServiceName = name
        cooldownResetMillis = CooldownPresets.resetMillisFor(preset)
        notificationRule = CooldownPresets.ruleFor(preset, name)
        // The reset time doubles as the alarm's set time so the picker + list read correctly.
        val cal = Calendar.getInstance().apply { timeInMillis = cooldownResetMillis!! }
        hour = cal.get(Calendar.HOUR_OF_DAY)
        minute = cal.get(Calendar.MINUTE)
        updateTimeText()
        updateCooldownStatus()
    }

    private fun updateCooldownStatus() {
        val b = _binding ?: return
        val name = cooldownServiceName
        b.cooldownServiceStatus.text = if (name.isNullOrBlank()) {
            getString(R.string.cooldown_service_none)
        } else {
            getString(R.string.cooldown_service_selected_fmt, name)
        }
    }

    /** Reuses the notification-access grant state (optional here — the timer works without it). */
    private fun refreshCooldownSection() {
        val b = _binding ?: return
        val granted = NotificationAccess.isGranted(requireContext())
        val connecting = granted && !AlarmNotificationListener.isConnected()
        b.cooldownGrantStatus.setText(
            when {
                connecting -> R.string.cooldown_access_status_connecting
                granted -> R.string.cooldown_access_status_on
                else -> R.string.cooldown_access_status_off
            }
        )
        b.cooldownGrantButton.visibility = if (granted) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "AlarmEditSheet"
        const val REQUEST_KEY = "alarm_edit"
        const val KEY_ACTION = "action"
        const val KEY_ALARM_ID = "alarm_id"
        const val ACTION_SAVED = "saved"
        const val ACTION_DELETE_REQUESTED = "delete_requested"
        private const val ARG_ALARM_ID = "arg_alarm_id"

        /** Mirrors [EventTrigger.assumedSpeedKmh]'s default so the preview matches the real ETA. */
        private const val ASSUMED_SPEED_KMH = 40.0
        /** Past this, a match is called out as "nothing near you" rather than presented plainly. */
        private const val FAR_MATCH_M = 100_000.0
        private const val MIN_SPEED_KMH = 4.0
        private const val MAX_SPEED_KMH = 120.0

        /**
         * How long a new notification-tracked alarm waits before ringing anyway. Half an hour covers
         * the things this source is actually used for — a task finishing, a delivery arriving, a train
         * pulling in — none of which are open-ended journeys.
         */
        private const val DEFAULT_WAIT_MINUTES = 30

        /** Offered wait windows. Beyond four hours a clock time says it better; that's the last item. */
        private val WAIT_CHOICES = listOf(5, 10, 15, 30, 45, 60, 120, 240)

        // Track-an-event dropdown positions (must match R.array.tracking_modes order).
        private const val TRACK_OFF = 0
        private const val TRACK_PLACE = 1
        private const val TRACK_NOTIF = 2
        private const val TRACK_COOLDOWN = 3

        fun newInstance(alarmId: Long): AlarmEditSheet = AlarmEditSheet().apply {
            arguments = bundleOf(ARG_ALARM_ID to alarmId)
        }
    }
}
