package com.example.alarmtracker.ring

import android.Manifest
import android.animation.Animator
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.alarmtracker.R
import com.example.alarmtracker.data.Alarm
import com.example.alarmtracker.databinding.ActivityAlarmRingBinding
import com.example.alarmtracker.ui.report.MorningReportActivity
import com.example.alarmtracker.util.CameraImage
import com.example.alarmtracker.util.Format
import com.example.alarmtracker.util.PerceptualHash
import com.example.alarmtracker.util.Prefs
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Full-screen ringing UI shown over the lockscreen. Dismiss routes to the alarm's
 * mission (math / QR / steps / photo) as an in-activity view swap; the alarm keeps
 * ringing until the mission completes. When a sensor or permission needed by a
 * mission is unavailable the mission degrades to math so the user is never trapped.
 *
 * The same activity also hosts "glow" mode: a silent sunrise pre-alarm opens it
 * ahead of the real alarm and animates a dark->warm screen sunrise, then swaps into
 * the normal ring when the real alarm fires.
 */
class AlarmActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmRingBinding

    private var missionType: String = Alarm.MISSION_NONE
    private var missionDifficulty: Int = 1
    private var missionBarcode: String? = null
    private var missionPhotoHash: String? = null
    private var missionStartedElapsed = 0L
    private var snoozeEnabled = true
    private var actionPackage: String? = null
    private var actionLabel: String? = null

    // Math mission
    private var solvedCount = 0
    private var currentAnswer = 0

    // Camera (QR + photo missions)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }
    private var cameraController: LifecycleCameraController? = null
    private var missionCompleted = false

    // Steps mission
    private var sensorManager: SensorManager? = null
    private var stepListener: SensorEventListener? = null
    private var stepGoal = 0
    private var stepsTaken = 0
    private var stepCounterBaseline = -1f
    // Dynamic step volume: ring is loud while stationary, quiet while actively stepping.
    private var lastStepElapsed = 0L
    private var duckScale = 1f

    // Mission volume swell (puzzle / math / QR / photo): starts quiet, grows while you solve.
    private var missionRampJob: Job? = null

    // Puzzle mission
    private var puzzleExpected = 0
    private var puzzleRound = 0
    private var puzzleRounds = 0

    // Glow (sunrise pre-alarm) mode
    private var glowMode = false
    private var glowRealAt = 0L
    private var ringActive = false

    // Ring-screen animations (breathing clock + bobbing swipe hint), cancelled on destroy.
    private var clockPulse: Animator? = null
    private var hintBob: Animator? = null
    private var ringAnimStarted = false

    // Morning report (feature 2): only shown after a real user dismiss, never after
    // a snooze or ring-timeout. Captured from the live ring state at dismiss time.
    private var userDismissing = false
    private var lastAlarmId = -1L
    private var lastSnoozeCount = 0

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when (missionType) {
                Alarm.MISSION_QR -> showQrMission()
                Alarm.MISSION_PHOTO -> showPhotoMission()
                else -> showMathMission()
            }
        } else {
            // Never trap the user: fall back to the math mission.
            Toast.makeText(this, R.string.mission_fallback_camera, Toast.LENGTH_LONG).show()
            showMathMission()
        }
    }

    private val activityRecognitionPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startStepsMission() else {
            Toast.makeText(this, R.string.mission_fallback_steps, Toast.LENGTH_LONG).show()
            showMathMission()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityAlarmRingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // No accidental escape from a ringing alarm.
        onBackPressedDispatcher.addCallback(this) { /* consume */ }

        binding.ringSnooze.setOnClickListener { snooze(it) }
        binding.missionSnooze.setOnClickListener { snooze(it) }
        binding.qrSnooze.setOnClickListener { snooze(it) }
        binding.photoSnooze.setOnClickListener { snooze(it) }
        binding.stepsSnooze.setOnClickListener { snooze(it) }
        // "Solve math instead" escape on the activity missions, so a stuck sensor/camera never traps.
        binding.stepsMath.setOnClickListener { switchToMath() }
        binding.qrMath.setOnClickListener { switchToMath() }
        binding.photoMath.setOnClickListener { switchToMath() }
        binding.ringPlaySound.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            AlarmRingService.playSound(this)
        }
        binding.puzzleSnooze.setOnClickListener { snooze(it) }
        binding.puzzleMath.setOnClickListener { switchToMath() }
        binding.ringAction.setOnClickListener { openActionAndDismiss() }
        binding.ringDismiss.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            startMissionOrDismiss()
        }
        binding.missionSubmit.setOnClickListener { checkAnswer() }
        binding.missionAnswer.setOnEditorActionListener { _, _, _ ->
            checkAnswer()
            true
        }
        binding.photoCapture.setOnClickListener { capturePhotoForDismiss() }
        binding.glowSkip.setOnClickListener {
            // Skip the glow only; the real alarm still rings normally.
            if (!ringActive) finish()
        }

        setupRingGestures()
        maybeEnterGlow()
        observeRingState()
        startClock()
    }

    /**
     * Classic-alarm gestures on the ring page (in addition to the buttons and volume keys):
     * swipe up anywhere to dismiss, single-tap to snooze. Only active while actually ringing, and
     * only on the ring view (mission views hide it), so scrolling/typing in a mission is unaffected.
     */
    @Suppress("ClickableViewAccessibility")
    private fun setupRingGestures() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downY = 0f
        var downTime = 0L
        var dragging = false
        binding.ringContent.setOnTouchListener { _, ev ->
            if (!ringActive) return@setOnTouchListener false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = ev.rawY
                    downTime = System.currentTimeMillis()
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    if (!dragging && dy < -slop) dragging = true
                    if (dragging) {
                        // The whole ring card follows the finger up and fades — visible feedback.
                        val ty = dy.coerceAtMost(0f)
                        binding.ringContent.translationY = ty
                        binding.ringContent.alpha = (1f + ty / SWIPE_FADE_PX).coerceIn(0.2f, 1f)
                    }
                    dragging
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        if (-binding.ringContent.translationY > SWIPE_DISMISS_PX) {
                            animateSwipeDismiss()
                        } else {
                            springRingBack()
                        }
                    } else if (snoozeEnabled &&
                        kotlin.math.abs(ev.rawY - downY) < slop &&
                        System.currentTimeMillis() - downTime < TAP_MAX_MS
                    ) {
                        snooze(binding.ringContent)
                    }
                    dragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    springRingBack()
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    /** Fling the ring card off-screen, then run the normal dismiss (mission-or-dismiss). */
    private fun animateSwipeDismiss() {
        binding.ringContent.animate()
            .translationY(-binding.ringContent.height.toFloat())
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                binding.ringContent.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                binding.ringDismiss.performClick()
            }
            .start()
    }

    private fun springRingBack() {
        binding.ringContent.animate().translationY(0f).alpha(1f).setDuration(200).start()
    }

    private fun snooze(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        AlarmRingService.snooze(this)
    }

    /** Hides every snooze affordance (and swaps the gesture hint) for a "No snooze" alarm. */
    private fun applySnoozeVisibility() {
        val vis = if (snoozeEnabled) View.VISIBLE else View.GONE
        binding.ringSnooze.visibility = vis
        binding.missionSnooze.visibility = vis
        binding.qrSnooze.visibility = vis
        binding.photoSnooze.visibility = vis
        binding.stepsSnooze.visibility = vis
        binding.puzzleSnooze.visibility = vis
        binding.ringGestureHint.setText(
            if (snoozeEnabled) R.string.ring_gesture_hint else R.string.ring_gesture_hint_no_snooze
        )
    }

    /** Shows the "Open <app>" ring action when the alarm carries one (e.g. a limit-reset alarm). */
    private fun applyActionButton() {
        val label = actionLabel
        if (actionPackage != null && label != null) {
            binding.ringAction.text = getString(R.string.ring_open_fmt, label)
            binding.ringAction.visibility = View.VISIBLE
        } else {
            binding.ringAction.visibility = View.GONE
        }
    }

    /** Opens the tracked app (so the user can start their session) and stops the alarm. */
    private fun openActionAndDismiss() {
        val pkg = actionPackage ?: return
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            binding.ringAction.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            try {
                startActivity(intent)
            } catch (_: Exception) {
                // Fall through to just dismissing if the app can't be launched.
            }
        }
        AlarmRingService.dismiss(this)
    }

    /**
     * When the ring has been muted because a call is live, say so on the ring screen and offer to
     * play it anyway — silence with no explanation would just read as a broken alarm.
     */
    private fun applyMeetingNotice(suppressed: Boolean) {
        val vis = if (suppressed) View.VISIBLE else View.GONE
        binding.ringMeetingNotice.visibility = vis
        binding.ringPlaySound.visibility = vis
    }

    /**
     * "This should have made a noise and didn't." Shown when there was no playable tone or the alarm
     * stream is at zero — a different problem from being muted for a call, and one the user can only fix
     * if we say it out loud. Vibration is already running by this point.
     */
    private fun applySoundUnavailableNotice(unavailable: Boolean) {
        if (!unavailable) return
        binding.ringMeetingNotice.visibility = View.VISIBLE
        binding.ringMeetingNotice.setText(R.string.ring_sound_unavailable)
        // No "play sound anyway" here: there is nothing to play, or nothing would be heard.
        binding.ringPlaySound.visibility = View.GONE
    }

    /** Manual escape from a sensor/camera mission → the always-available math mission. */
    private fun switchToMath() {
        stepListener?.let { sensorManager?.unregisterListener(it) }
        stepListener = null
        releaseCamera()
        restoreRingVolume()
        showMathMission()
    }

    /** Restores full ring volume if a mission had ducked it, and stops any running swell. */
    private fun restoreRingVolume() {
        missionRampJob?.cancel()
        missionRampJob = null
        if (duckScale != 1f) {
            duckScale = 1f
            AlarmRingService.setVolumeScale(this, 1f)
        }
    }

    /**
     * Mission volume policy: drop the ring back to a murmur when a dismiss mission opens, then
     * swell it back to full over [MISSION_RAMP_MS]. Solving a puzzle at full blast is what made
     * the ring feel like a punishment; this gives a calm window that still escalates if you stall.
     *
     * Respects the "gradual volume" setting — with it off the ring stays at full, as before. The
     * steps mission is excluded: it has its own move-to-quieten policy in [startStepVolumeLoop].
     */
    private fun startMissionVolumeRamp() {
        if (!Prefs.volumeRampEnabled(this)) return
        missionRampJob?.cancel()
        missionRampJob = lifecycleScope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            while (!missionCompleted) {
                val elapsed = (SystemClock.elapsedRealtime() - startedAt).toFloat()
                val progress = (elapsed / MISSION_RAMP_MS).coerceIn(0f, 1f)
                val target = MISSION_RAMP_START + (1f - MISSION_RAMP_START) * progress
                if (target != duckScale) {
                    duckScale = target
                    AlarmRingService.setVolumeScale(this@AlarmActivity, target)
                }
                if (progress >= 1f) break
                delay(MISSION_RAMP_TICK_MS)
            }
        }
    }

    /**
     * Steps mission volume policy: while the sensor keeps reporting steps the ring is ducked
     * (quiet reward for moving); once movement stalls it swells back to full so a stalled user is
     * nagged again. Loops only while the steps view is showing.
     */
    private fun startStepVolumeLoop() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (binding.stepsContent.visibility == View.VISIBLE && !missionCompleted) {
                    val now = SystemClock.elapsedRealtime()
                    val moving = lastStepElapsed > 0L && (now - lastStepElapsed) < STEP_MOVING_WINDOW_MS
                    val target = if (moving) STEP_DUCK_SCALE else 1f
                    if (target != duckScale) {
                        duckScale = target
                        AlarmRingService.setVolumeScale(this@AlarmActivity, target)
                    }
                    delay(STEP_VOLUME_TICK_MS)
                }
                restoreRingVolume()
            }
        }
    }

    /**
     * Brings the ring screen to life once: the content fades and rises in, the big clock
     * "breathes" with a slow pulse, and the swipe hint gently bobs upward to invite the gesture.
     * Skipped entirely when the system's animation scale is off (accessibility / battery saver).
     */
    private fun startRingAnimations() {
        if (ringAnimStarted) return
        ringAnimStarted = true
        if (animationsDisabled()) return

        binding.ringContent.apply {
            alpha = 0f
            translationY = 64f
            animate().alpha(1f).translationY(0f).setDuration(450).start()
        }

        clockPulse = ObjectAnimator.ofPropertyValuesHolder(
            binding.ringClock,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f)
        ).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }

        hintBob = ObjectAnimator.ofFloat(binding.ringGestureHint, View.TRANSLATION_Y, 0f, -16f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun animationsDisabled(): Boolean =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

    /**
     * Volume (and headset) buttons snooze a ringing alarm — a traditional-alarm convenience,
     * toggleable in settings. Only while actually ringing (not during the sunrise-glow pre-alarm),
     * so glow-mode volume changes still work normally.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (ringActive && snoozeEnabled && Prefs.volumeSnoozeEnabled(this) && isSnoozeKey(keyCode)) {
            binding.root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            AlarmRingService.snooze(this)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Swallow the key-up too so the system volume UI never flashes for a snooze press. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (ringActive && snoozeEnabled && Prefs.volumeSnoozeEnabled(this) && isSnoozeKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isSnoozeKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_HEADSETHOOK

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep the current (glow or ring) state; the ring is driven by the service flow.
    }

    private fun observeRingState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AlarmRingService.ringingState.collect { state ->
                    if (state == null) {
                        // In glow mode we wait for the real ring; otherwise the ring ended.
                        if (!glowMode || ringActive) {
                            maybeAutoOpenApp()
                            maybeLaunchMorningReport()
                            finish()
                        }
                    } else {
                        if (glowMode && !ringActive) endGlowIntoRing()
                        ringActive = true
                        startRingAnimations()
                        binding.ringLabel.text = state.label
                        missionType = state.missionType
                        missionDifficulty = state.missionDifficulty
                        missionBarcode = state.missionBarcode
                        missionPhotoHash = state.missionPhotoHash
                        lastAlarmId = state.alarmId
                        lastSnoozeCount = state.snoozeCount
                        snoozeEnabled = state.snoozeEnabled
                        actionPackage = state.actionPackage
                        actionLabel = state.actionLabel
                        applySnoozeVisibility()
                        applyActionButton()
                        applyMeetingNotice(state.soundSuppressed)
                        applySoundUnavailableNotice(state.soundUnavailable)
                    }
                }
            }
        }
    }

    private fun startClock() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val cal = Calendar.getInstance()
                    val text = Format.timeText(
                        this@AlarmActivity,
                        cal.get(Calendar.HOUR_OF_DAY),
                        cal.get(Calendar.MINUTE)
                    )
                    binding.ringClock.text = text
                    binding.glowClock.text = text
                    delay(1_000)
                }
            }
        }
    }

    // ---- Mission dispatch ----

    private fun startMissionOrDismiss() {
        when (missionType) {
            Alarm.MISSION_MATH -> showMathMission()
            Alarm.MISSION_PUZZLE -> showPuzzleMission()
            Alarm.MISSION_QR ->
                if (missionBarcode.isNullOrBlank()) showMathMission() else requestCameraFor()
            Alarm.MISSION_PHOTO ->
                if (missionPhotoHash.isNullOrBlank()) showMathMission() else requestCameraFor()
            Alarm.MISSION_STEPS -> startStepsMissionWithPermission()
            else -> {
                userDismissing = true
                AlarmRingService.dismiss(this)
            }
        }
    }

    /**
     * Launches the post-dismiss Morning Report Card when the user actually dismissed
     * the alarm (not on snooze or ring-timeout) and the setting is enabled. Non-blocking:
     * the dismiss has already completed and this activity finishes right after.
     */
    /**
     * With "open the app automatically" on, launch the alarm's chosen app once the alarm has actually been
     * dismissed — not before. Deliberately after: opening it while the alarm rings would cover the ring
     * screen and leave no way to stop the noise.
     */
    private fun maybeAutoOpenApp() {
        if (!userDismissing || glowMode) return
        if (!Prefs.autoOpenAppEnabled(this)) return
        val pkg = actionPackage ?: return
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // Best-effort; the button is still there if this fails.
        }
    }

    private fun maybeLaunchMorningReport() {
        if (!userDismissing || glowMode) return
        if (!Prefs.morningReportEnabled(this)) return
        try {
            startActivity(
                Intent(this, MorningReportActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MorningReportActivity.EXTRA_ALARM_ID, lastAlarmId)
                    .putExtra(MorningReportActivity.EXTRA_WAKE_AT, System.currentTimeMillis())
                    .putExtra(MorningReportActivity.EXTRA_SNOOZE_COUNT, lastSnoozeCount)
            )
        } catch (_: Exception) {
            // Report is best-effort; never block finishing the ring screen.
        }
    }

    private fun requestCameraFor() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            if (missionType == Alarm.MISSION_QR) showQrMission() else showPhotoMission()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hideAllMissions() {
        binding.ringContent.visibility = View.GONE
        binding.missionContent.visibility = View.GONE
        binding.qrContent.visibility = View.GONE
        binding.photoContent.visibility = View.GONE
        binding.stepsContent.visibility = View.GONE
        binding.puzzleContent.visibility = View.GONE
    }

    private fun markMissionStart() {
        if (missionStartedElapsed == 0L) missionStartedElapsed = SystemClock.elapsedRealtime()
    }

    private fun completeMission() {
        if (missionCompleted) return
        missionCompleted = true
        userDismissing = true
        val duration = if (missionStartedElapsed > 0L) {
            SystemClock.elapsedRealtime() - missionStartedElapsed
        } else -1L
        AlarmRingService.dismiss(this, duration)
    }

    // ---- Math mission ----

    private fun showMathMission() {
        if (binding.missionContent.visibility == View.VISIBLE) return
        // A math fallback replaces whatever the intended mission type was.
        missionType = Alarm.MISSION_MATH
        markMissionStart()
        startMissionVolumeRamp()
        solvedCount = 0
        hideAllMissions()
        binding.missionContent.visibility = View.VISIBLE
        binding.missionProgress.max = PROBLEM_COUNT
        updateMissionProgress()
        nextProblem()
    }

    private fun nextProblem() {
        val (text, answer) = generateProblem(missionDifficulty)
        currentAnswer = answer
        binding.missionProblem.text = text
        binding.missionAnswer.setText("")
        binding.missionAnswerLayout.error = null
        binding.missionAnswer.requestFocus()
    }

    private fun checkAnswer() {
        val given = binding.missionAnswer.text?.toString()?.trim()?.toIntOrNull()
        if (given != null && given == currentAnswer) {
            binding.missionAnswer.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            solvedCount++
            updateMissionProgress()
            if (solvedCount >= PROBLEM_COUNT) {
                completeMission()
            } else {
                nextProblem()
            }
        } else {
            binding.missionAnswer.performHapticFeedback(HapticFeedbackConstants.REJECT)
            binding.missionAnswerLayout.error = getString(R.string.ring_mission_wrong)
            binding.missionAnswer.setText("")
        }
    }

    private fun updateMissionProgress() {
        binding.missionProgress.setProgressCompat(solvedCount, true)
        binding.missionProgressText.text =
            getString(R.string.ring_mission_progress, solvedCount, PROBLEM_COUNT)
    }

    /** Difficulty 1: a+b · 2: a×b+c · 3: a×b−c with larger operands. */
    private fun generateProblem(difficulty: Int): Pair<String, Int> = when (difficulty) {
        1 -> {
            val a = Random.nextInt(11, 60)
            val b = Random.nextInt(11, 60)
            "$a + $b" to (a + b)
        }
        2 -> {
            val a = Random.nextInt(3, 13)
            val b = Random.nextInt(4, 10)
            val c = Random.nextInt(5, 40)
            "$a × $b + $c" to (a * b + c)
        }
        else -> {
            val a = Random.nextInt(12, 26)
            val b = Random.nextInt(7, 19)
            val c = Random.nextInt(10, 99)
            "$a × $b − $c" to (a * b - c)
        }
    }

    // ---- Puzzle mission (in-house; number-order or odd-one-out by difficulty) ----

    private fun showPuzzleMission() {
        markMissionStart()
        startMissionVolumeRamp()
        hideAllMissions()
        binding.puzzleContent.visibility = View.VISIBLE
        if (missionDifficulty >= 3) startOddOneOut() else startNumberOrder()
    }

    /** Easy/Medium: tap the shuffled tiles 1→N in ascending order. */
    private fun startNumberOrder() {
        val n = if (missionDifficulty >= 2) 9 else 6
        binding.puzzleTitle.text = getString(R.string.ring_puzzle_order_title, n)
        binding.puzzleProgress.text = getString(R.string.ring_puzzle_progress, 0, n)
        puzzleExpected = 1
        buildPuzzleGrid((1..n).shuffled()) { _, value, button ->
            if (value == puzzleExpected) {
                button.isEnabled = false
                button.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                puzzleExpected++
                binding.puzzleProgress.text =
                    getString(R.string.ring_puzzle_progress, puzzleExpected - 1, n)
                if (puzzleExpected > n) completeMission()
            } else {
                button.performHapticFeedback(HapticFeedbackConstants.REJECT)
            }
        }
    }

    /** Hard: three rounds of "tap the tile whose parity differs from the rest". */
    private fun startOddOneOut() {
        puzzleRounds = 3
        puzzleRound = 0
        binding.puzzleTitle.setText(R.string.ring_puzzle_odd_title)
        nextOddRound()
    }

    private fun nextOddRound() {
        binding.puzzleProgress.text =
            getString(R.string.ring_puzzle_progress, puzzleRound, puzzleRounds)
        val majorityEven = Random.nextBoolean()
        val targetIndex = Random.nextInt(9)
        val values = IntArray(9) {
            var v: Int
            do { v = Random.nextInt(10, 100) } while ((v % 2 == 0) != majorityEven)
            v
        }
        do {
            values[targetIndex] = Random.nextInt(10, 100)
        } while ((values[targetIndex] % 2 == 0) == majorityEven)
        buildPuzzleGrid(values.toList()) { index, _, button ->
            if (index == targetIndex) {
                button.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                puzzleRound++
                if (puzzleRound >= puzzleRounds) completeMission() else nextOddRound()
            } else {
                button.performHapticFeedback(HapticFeedbackConstants.REJECT)
            }
        }
    }

    private fun buildPuzzleGrid(
        values: List<Int>,
        onTap: (Int, Int, com.google.android.material.button.MaterialButton) -> Unit
    ) {
        val grid = binding.puzzleGrid
        grid.removeAllViews()
        grid.columnCount = 3
        val density = resources.displayMetrics.density
        val sizePx = (72 * density).toInt()
        val marginPx = (6 * density).toInt()
        values.forEachIndexed { index, value ->
            val button = com.google.android.material.button.MaterialButton(this).apply {
                // Locale-formatted so the tiles use the user's own numerals, not always ASCII.
                text = String.format(java.util.Locale.getDefault(), "%d", value)
                textSize = 22f
                insetTop = 0
                insetBottom = 0
                layoutParams = android.widget.GridLayout.LayoutParams().apply {
                    width = sizePx
                    height = sizePx
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                setOnClickListener { onTap(index, value, this) }
            }
            grid.addView(button)
        }
    }

    // ---- QR / barcode mission ----

    private fun showQrMission() {
        markMissionStart()
        startMissionVolumeRamp()
        hideAllMissions()
        binding.qrContent.visibility = View.VISIBLE
        val ctrl = LifecycleCameraController(this).apply { bindToLifecycle(this@AlarmActivity) }
        cameraController = ctrl
        binding.qrPreview.controller = ctrl
        ctrl.setImageAnalysisAnalyzer(cameraExecutor, ::analyzeForBarcode)
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeForBarcode(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        barcodeScanner.process(input)
            .addOnSuccessListener { barcodes ->
                val target = missionBarcode
                if (target != null && barcodes.any { it.rawValue == target }) {
                    runOnUiThread { onQrMatched() }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun onQrMatched() {
        if (missionCompleted) return
        binding.qrPreview.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        cameraController?.clearImageAnalysisAnalyzer()
        releaseCamera()
        completeMission()
    }

    // ---- Photo mission ----

    private fun showPhotoMission() {
        markMissionStart()
        startMissionVolumeRamp()
        hideAllMissions()
        binding.photoContent.visibility = View.VISIBLE
        val ctrl = LifecycleCameraController(this).apply { bindToLifecycle(this@AlarmActivity) }
        cameraController = ctrl
        binding.photoPreview.controller = ctrl
    }

    private fun capturePhotoForDismiss() {
        val ctrl = cameraController ?: return
        binding.photoCapture.isEnabled = false
        ctrl.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bmp = try {
                        CameraImage.toBitmap(image)
                    } finally {
                        image.close()
                    }
                    val hash = bmp?.let { PerceptualHash.compute(it) }
                    val distance = PerceptualHash.hammingDistance(missionPhotoHash, hash)
                    runOnUiThread {
                        binding.photoCapture.isEnabled = true
                        if (distance <= PerceptualHash.MATCH_THRESHOLD) {
                            binding.photoCapture.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            releaseCamera()
                            completeMission()
                        } else {
                            binding.photoCapture.performHapticFeedback(HapticFeedbackConstants.REJECT)
                            Toast.makeText(
                                this@AlarmActivity,
                                R.string.ring_photo_no_match,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    runOnUiThread {
                        binding.photoCapture.isEnabled = true
                        Toast.makeText(
                            this@AlarmActivity,
                            R.string.capture_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    // ---- Steps mission ----

    private fun startStepsMissionWithPermission() {
        val sm = getSystemService(SensorManager::class.java)
        val hasSensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) != null ||
            sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null
        if (sm == null || !hasSensor) {
            Toast.makeText(this, R.string.mission_fallback_steps, Toast.LENGTH_LONG).show()
            showMathMission()
            return
        }
        if (Build.VERSION.SDK_INT >= 29 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            activityRecognitionPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        } else {
            startStepsMission()
        }
    }

    private fun startStepsMission() {
        val sm = getSystemService(SensorManager::class.java) ?: run { showMathMission(); return }
        val detector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val counter = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val sensor = detector ?: counter ?: run {
            showMathMission()
            return
        }
        markMissionStart()
        hideAllMissions()
        binding.stepsContent.visibility = View.VISIBLE
        stepGoal = stepGoalFor(missionDifficulty)
        stepsTaken = 0
        stepCounterBaseline = -1f
        binding.stepsProgress.max = stepGoal
        updateStepsProgress()

        sensorManager = sm
        val useCounter = detector == null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (missionCompleted) return
                lastStepElapsed = SystemClock.elapsedRealtime()
                if (useCounter) {
                    val total = event.values.firstOrNull() ?: return
                    if (stepCounterBaseline < 0f) stepCounterBaseline = total
                    stepsTaken = (total - stepCounterBaseline).toInt().coerceAtLeast(0)
                } else {
                    stepsTaken += event.values.firstOrNull()?.toInt()?.coerceAtLeast(1) ?: 1
                }
                updateStepsProgress()
                if (stepsTaken >= stepGoal) {
                    binding.stepsProgress.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    completeMission()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        stepListener = listener
        lastStepElapsed = 0L
        duckScale = 1f
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        startStepVolumeLoop()
    }

    private fun updateStepsProgress() {
        val shown = stepsTaken.coerceAtMost(stepGoal)
        binding.stepsProgress.setProgressCompat(shown, true)
        binding.stepsCount.text = getString(R.string.ring_steps_count, shown, stepGoal)
    }

    private fun stepGoalFor(difficulty: Int): Int = when (difficulty) {
        1 -> 10
        2 -> 20
        else -> 30
    }

    // ---- Sunrise glow ----

    private fun maybeEnterGlow() {
        val realAt = intent.getLongExtra(EXTRA_GLOW_REAL_AT, 0L)
        if (!intent.getBooleanExtra(EXTRA_GLOW, false) || realAt <= 0L) return
        // If the real alarm is already ringing, skip straight to the ring UI.
        if (AlarmRingService.ringingState.value != null) return
        glowMode = true
        glowRealAt = realAt
        hideAllMissions()
        binding.glowContent.visibility = View.VISIBLE
        binding.ringContent.visibility = View.GONE
        startGlowAnimation()
        scheduleGlowSafetyTimeout()
    }

    private fun startGlowAnimation() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val evaluator = ArgbEvaluator()
                val start = ContextCompat.getColor(this@AlarmActivity, R.color.glow_start)
                val end = ContextCompat.getColor(this@AlarmActivity, R.color.glow_end)
                val startMs = System.currentTimeMillis()
                val span = (glowRealAt - startMs).coerceIn(1_000L, GLOW_MAX_SPAN_MS)
                while (!ringActive) {
                    val f = ((System.currentTimeMillis() - startMs).toFloat() / span).coerceIn(0f, 1f)
                    val color = evaluator.evaluate(f, start, end) as Int
                    binding.glowContent.setBackgroundColor(color)
                    setScreenBrightness(
                        GLOW_MIN_BRIGHTNESS + (GLOW_MAX_BRIGHTNESS - GLOW_MIN_BRIGHTNESS) * f
                    )
                    if (f >= 1f) break
                    delay(GLOW_FRAME_MS)
                }
            }
        }
    }

    private fun scheduleGlowSafetyTimeout() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val wait = (glowRealAt - System.currentTimeMillis()).coerceAtLeast(0L) +
                    GLOW_SAFETY_GRACE_MS
                delay(wait)
                // If the real ring never arrived, don't leave a stuck glow screen.
                if (!ringActive) finish()
            }
        }
    }

    private fun endGlowIntoRing() {
        glowMode = false
        binding.glowContent.visibility = View.GONE
        binding.ringContent.visibility = View.VISIBLE
        // Hand brightness back to the system for the readable ring screen.
        setScreenBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    private fun setScreenBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    private fun releaseCamera() {
        cameraController?.clearImageAnalysisAnalyzer()
        binding.qrPreview.controller = null
        binding.photoPreview.controller = null
        cameraController = null
    }

    override fun onDestroy() {
        clockPulse?.cancel()
        hintBob?.cancel()
        releaseCamera()
        stepListener?.let { sensorManager?.unregisterListener(it) }
        stepListener = null
        restoreRingVolume()
        cameraExecutor.shutdown()
        barcodeScanner.close()
        super.onDestroy()
    }

    companion object {
        private const val PROBLEM_COUNT = 3
        private const val SWIPE_DISMISS_PX = 160f
        private const val SWIPE_FADE_PX = 520f
        private const val TAP_MAX_MS = 250L

        // Steps-mission dynamic volume.
        private const val STEP_MOVING_WINDOW_MS = 2_500L
        private const val STEP_VOLUME_TICK_MS = 700L
        private const val STEP_DUCK_SCALE = 0.12f

        // Mission volume swell: from a murmur back to full over the ramp window.
        private const val MISSION_RAMP_MS = 75_000f
        private const val MISSION_RAMP_TICK_MS = 500L
        private const val MISSION_RAMP_START = 0.15f

        const val EXTRA_GLOW = "extra_glow"
        const val EXTRA_GLOW_REAL_AT = "extra_glow_real_at"

        private const val GLOW_FRAME_MS = 500L
        private const val GLOW_MAX_SPAN_MS = 60 * 60_000L
        private const val GLOW_SAFETY_GRACE_MS = 2 * 60_000L
        private const val GLOW_MIN_BRIGHTNESS = 0.02f
        private const val GLOW_MAX_BRIGHTNESS = 0.9f
    }
}
