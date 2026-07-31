package com.example.alarmtracker.ui.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import com.example.alarmtracker.R
import com.example.alarmtracker.databinding.ActivitySettingsBinding

/**
 * Settings, opened from the sidebar.
 *
 * It used to be a bottom-nav tab, but with the world clock taking that slot — and the sidebar
 * already listing every set-up-once screen — it fits better as a normal destination alongside
 * Connections, Health check and Help.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.settingsActivityToolbar.setNavigationOnClickListener { finish() }
        // Hosts the same SettingsFragment the tab used to, so nothing about the settings screen
        // itself changed — only where it's reached from.
        if (supportFragmentManager.findFragmentById(R.id.settings_host) == null) {
            supportFragmentManager.commit {
                replace(R.id.settings_host, SettingsFragment())
            }
        }
    }
}
