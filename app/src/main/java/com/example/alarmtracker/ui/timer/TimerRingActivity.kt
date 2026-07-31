package com.example.alarmtracker.ui.timer

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.alarmtracker.R
import com.example.alarmtracker.databinding.ActivityTimerRingBinding
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Full-screen "time's up" screen shown over the lock screen when a timer finishes. Offers Stop and,
 * if the timer has a finish-action, an "Open <app>" button. Finishes when the ring service stops.
 */
class TimerRingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimerRingBinding

    private var titlePulse: ObjectAnimator? = null
    private var hintBob: ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityTimerRingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // No accidental escape.
        onBackPressedDispatcher.addCallback(this) { }

        bind()
        binding.timerRingSnooze.text =
            getString(R.string.timer_snooze_fmt, TimerRingService.snoozeMinutes(this))
        binding.timerRingSnooze.setOnClickListener {
            TimerRingService.snooze(this)
            finish()
        }
        binding.timerRingStop.setOnClickListener {
            TimerRingService.stop(this)
            finish()
        }
        setupRingGestures()
        startRingAnimations()

        // Close automatically if the timer is stopped elsewhere (notification / another surface).
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                TimerRingService.active.collect { active -> if (!active) finish() }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind()
    }

    /** Volume (and headset) buttons snooze the timer — same convenience as alarms, same setting. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.example.alarmtracker.util.Prefs.volumeSnoozeEnabled(this) && isSnoozeKey(keyCode)) {
            TimerRingService.snooze(this)
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Swallow the key-up too so the system volume UI never flashes. */
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (com.example.alarmtracker.util.Prefs.volumeSnoozeEnabled(this) && isSnoozeKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isSnoozeKey(keyCode: Int): Boolean =
        keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK

    /**
     * Swipe up anywhere to stop, single tap to snooze — the same gestures as the alarm ring, because a
     * timer that rings like an alarm has to be dismissable like one. The reported gap was that the alarm
     * screen's swipe-up (with the card following your finger) simply wasn't here, so the timer's only
     * exit was the button.
     */
    @Suppress("ClickableViewAccessibility")
    private fun setupRingGestures() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downY = 0f
        var downTime = 0L
        var dragging = false
        binding.timerRingContent.setOnTouchListener { _, ev ->
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
                        // The content follows the finger up and fades, so the gesture is visibly working
                        // before it completes.
                        val ty = dy.coerceAtMost(0f)
                        binding.timerRingContent.translationY = ty
                        binding.timerRingContent.alpha = (1f + ty / SWIPE_FADE_PX).coerceIn(0.2f, 1f)
                    }
                    dragging
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        if (-binding.timerRingContent.translationY > SWIPE_DISMISS_PX) {
                            animateSwipeDismiss()
                        } else {
                            springBack()
                        }
                    } else if (abs(ev.rawY - downY) < slop &&
                        System.currentTimeMillis() - downTime < TAP_MAX_MS
                    ) {
                        binding.timerRingContent.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                        TimerRingService.snooze(this)
                        finish()
                    }
                    dragging = false
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    springBack()
                    dragging = false
                    true
                }

                else -> false
            }
        }
    }

    private fun animateSwipeDismiss() {
        binding.timerRingContent.animate()
            .translationY(-binding.timerRingContent.height.toFloat())
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                binding.timerRingContent.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                TimerRingService.stop(this)
                finish()
            }
            .start()
    }

    private fun springBack() {
        binding.timerRingContent.animate().translationY(0f).alpha(1f).setDuration(200).start()
    }

    /**
     * Content fades and rises in, "Time's up" breathes, and the swipe hint bobs to invite the gesture.
     * Skipped when the system animation scale is off (accessibility / battery saver).
     */
    private fun startRingAnimations() {
        if (Settings.Global.getFloat(
                contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) == 0f
        ) {
            return
        }
        binding.timerRingContent.apply {
            alpha = 0f
            translationY = 64f
            animate().alpha(1f).translationY(0f).setDuration(450).start()
        }
        titlePulse = ObjectAnimator.ofPropertyValuesHolder(
            binding.timerRingTitle,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.05f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.05f)
        ).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
        hintBob = ObjectAnimator.ofFloat(binding.timerRingHint, View.TRANSLATION_Y, 0f, -16f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    override fun onDestroy() {
        // Infinite animators outlive the activity otherwise.
        titlePulse?.cancel()
        hintBob?.cancel()
        titlePulse = null
        hintBob = null
        super.onDestroy()
    }

    private companion object {
        // Deliberately the same numbers as AlarmActivity: the two ring screens must feel identical in
        // the hand, and a timer that needs a longer or shorter swipe than an alarm would be a bug.
        const val SWIPE_DISMISS_PX = 160f
        const val SWIPE_FADE_PX = 520f
        const val TAP_MAX_MS = 250L
    }

    private fun bind() {
        val label = intent.getStringExtra(TimerRingService.EXTRA_LABEL).orEmpty()
        val actionPackage = intent.getStringExtra(TimerRingService.EXTRA_ACTION_PACKAGE)
        val actionLabel = intent.getStringExtra(TimerRingService.EXTRA_ACTION_LABEL)

        binding.timerRingLabel.text = label.ifBlank { getString(R.string.timer_done_text) }
        val launchable = actionPackage != null && actionLabel != null &&
            packageManager.getLaunchIntentForPackage(actionPackage) != null
        if (launchable) {
            binding.timerRingOpen.visibility = android.view.View.VISIBLE
            binding.timerRingOpen.text = getString(R.string.ring_open_fmt, actionLabel)
            binding.timerRingOpen.setOnClickListener {
                TimerRingService.open(this, actionPackage)
                finish()
            }
        } else {
            binding.timerRingOpen.visibility = android.view.View.GONE
        }
    }
}
