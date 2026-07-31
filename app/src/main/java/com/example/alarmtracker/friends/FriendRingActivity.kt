package com.example.alarmtracker.friends

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.alarmtracker.databinding.ActivityFriendRingBinding
import kotlinx.coroutines.launch

/**
 * The full-screen alert for a watch marked [com.example.alarmtracker.data.FriendWatch.alertAsAlarm] —
 * shown over the lock screen, with the two things you actually want next: call them, or ask them where
 * they are. Finishes as soon as [FriendRingService] stops, wherever it was stopped from.
 *
 * Reads its content from the service rather than from intent extras, so a notification tap, a direct
 * launch and a re-delivery all show the same thing.
 */
class FriendRingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFriendRingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityFriendRingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        // Back must not silently leave an alert ringing behind it.
        onBackPressedDispatcher.addCallback(this) { }

        bind()
        binding.friendRingCall.setOnClickListener {
            FriendRingService.call(this)
            finish()
        }
        binding.friendRingNudge.setOnClickListener {
            FriendRingService.nudge(this)
            finish()
        }
        binding.friendRingDismiss.setOnClickListener {
            FriendRingService.stop(this)
            finish()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                FriendRingService.active.collect { active -> if (!active) finish() }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bind()
    }

    private fun bind() {
        val info = FriendRingService.current
        if (info == null) {
            finish()
            return
        }
        binding.friendRingName.text = info.friendName
        binding.friendRingText.text = info.text
        binding.friendRingCall.visibility =
            if (info.phoneNumber.isNullOrBlank()) View.GONE else View.VISIBLE
    }
}
