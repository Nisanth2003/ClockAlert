package com.example.alarmtracker.ui.help

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.alarmtracker.databinding.ActivityHelpBinding

/**
 * Static in-app feature guide — explains what every part of AlarmTracker does. Reached from
 * Settings → Help. Content lives in the layout (string resources); this class only wires the
 * toolbar and window insets, mirroring [com.example.alarmtracker.ui.health.HealthCheckActivity].
 */
class HelpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHelpBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.helpRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, right = bars.right)
            binding.helpScroll.updatePadding(bottom = bars.bottom + v.paddingBottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
    }
}
