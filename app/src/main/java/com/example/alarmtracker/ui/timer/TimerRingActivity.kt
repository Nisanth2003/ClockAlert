package com.example.alarmtracker.ui.timer

import android.os.Bundle
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
import kotlinx.coroutines.launch

/**
 * Full-screen "time's up" screen shown over the lock screen when a timer finishes. Offers Stop and,
 * if the timer has a finish-action, an "Open <app>" button. Finishes when the ring service stops.
 */
class TimerRingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTimerRingBinding

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
