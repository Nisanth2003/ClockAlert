package com.example.alarmtracker.ui.settings

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceFragmentCompat
import com.example.alarmtracker.R
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.databinding.FragmentSettingsBinding
import com.example.alarmtracker.scheduling.AlarmScheduler
import com.example.alarmtracker.scheduling.PreflightScheduler
import com.example.alarmtracker.ui.connections.ConnectionsActivity
import com.example.alarmtracker.ui.health.HealthCheckActivity
import com.example.alarmtracker.ui.help.HelpActivity
import com.example.alarmtracker.ui.postmortem.PostmortemActivity
import com.example.alarmtracker.ui.privacy.PrivacyActivity
import com.example.alarmtracker.util.DataExport
import com.example.alarmtracker.util.Prefs
import com.example.alarmtracker.util.Reliability
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialFadeThrough
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

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
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (childFragmentManager.findFragmentById(R.id.settings_container) == null) {
            childFragmentManager.commit {
                replace(R.id.settings_container, SettingsPreferenceFragment())
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    class SettingsPreferenceFragment : PreferenceFragmentCompat(),
        SharedPreferences.OnSharedPreferenceChangeListener {

        // SAF document creation for CSV export (feature 7).
        private val createCsv = registerForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv")
        ) { uri -> if (uri != null) exportTo(uri) }

        // Health Connect sleep-read permission request.
        private val healthPermission = registerForActivityResult(
            androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            if (granted.containsAll(com.example.alarmtracker.util.HealthSleep.PERMISSIONS)) {
                toast(R.string.health_granted)
                syncHealthSleep()
            } else {
                toast(R.string.health_denied)
            }
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<androidx.preference.Preference>("pref_reliability_check")
                ?.setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), HealthCheckActivity::class.java)); true
                }
            findPreference<androidx.preference.Preference>("pref_postmortem")
                ?.setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), PostmortemActivity::class.java)); true
                }
            findPreference<androidx.preference.Preference>("pref_privacy")
                ?.setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), PrivacyActivity::class.java)); true
                }
            findPreference<androidx.preference.Preference>("pref_connections")
                ?.setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), ConnectionsActivity::class.java)); true
                }
            findPreference<androidx.preference.Preference>("pref_guide")
                ?.setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), HelpActivity::class.java)); true
                }
            findPreference<androidx.preference.Preference>("pref_show_tips")
                ?.setOnPreferenceClickListener {
                    Prefs.resetCoach(requireContext())
                    Toast.makeText(requireContext(), R.string.tips_reset_toast, Toast.LENGTH_SHORT).show()
                    true
                }
            findPreference<androidx.preference.Preference>("pref_export")
                ?.setOnPreferenceClickListener { createCsv.launch(defaultExportName()); true }
            findPreference<androidx.preference.Preference>("pref_wipe")
                ?.setOnPreferenceClickListener { confirmWipe(); true }
            findPreference<androidx.preference.Preference>("pref_battery_opt")
                ?.setOnPreferenceClickListener { onBatteryClick(); true }
            findPreference<androidx.preference.Preference>("pref_health_sleep")
                ?.setOnPreferenceClickListener { onHealthSleepClick(); true }
            updateBatterySummary()
        }

        /**
         * Tap the visible battery control: if we're still being optimized, ask the system to exempt
         * us directly (with a fallback to the settings list); if we're already free, open the list so
         * the user can put the limit back if they'd rather.
         */
        private fun onBatteryClick() {
            val ctx = requireContext()
            val intent = if (Reliability.isBatteryOptimized(ctx)) {
                Reliability.directBatteryExemptionIntent(ctx)
            } else {
                toast(R.string.battery_already_free)
                Reliability.batteryListIntent()
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                try {
                    startActivity(Reliability.batteryListIntent())
                } catch (_: Exception) {
                }
            }
        }

        /** Connect Health Connect (request read-sleep), or guide the user to install it. */
        private fun onHealthSleepClick() {
            val ctx = requireContext()
            if (!com.example.alarmtracker.util.HealthSleep.isAvailable(ctx)) {
                toast(R.string.health_unavailable)
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(
                            "market://details?id=${com.example.alarmtracker.util.HealthSleep.providerPackage()}"
                        ))
                    )
                } catch (_: Exception) {
                }
                return
            }
            lifecycleScope.launch {
                if (com.example.alarmtracker.util.HealthSleep.hasPermission(ctx)) {
                    syncHealthSleep()
                } else {
                    healthPermission.launch(com.example.alarmtracker.util.HealthSleep.PERMISSIONS)
                }
            }
        }

        /** Read last night's sleep session and store it as the strongest bedtime signal. */
        private fun syncHealthSleep() {
            val appCtx = requireContext().applicationContext
            lifecycleScope.launch {
                val session = com.example.alarmtracker.util.HealthSleep.lastNight(appCtx)
                if (session == null) {
                    toast(R.string.health_no_session)
                    return@launch
                }
                val (bedtime, wake) = session
                AlarmRepository.get(appCtx).recordSleepSignal(
                    com.example.alarmtracker.data.SleepSignal.SOURCE_HEALTH_CONNECT, bedtime
                )
                val hours = (wake - bedtime) / 3_600_000.0
                if (isAdded) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.health_synced_fmt, String.format(java.util.Locale.getDefault(), "%.1f h", hours)),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        private fun updateBatterySummary() {
            findPreference<androidx.preference.Preference>("pref_battery_opt")?.setSummary(
                if (Reliability.isBatteryOptimized(requireContext())) {
                    R.string.pref_battery_on
                } else {
                    R.string.pref_battery_off
                }
            )
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.clipToPadding = false
        }

        override fun onResume() {
            super.onResume()
            preferenceScreen.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
            updateBatterySummary()
        }

        override fun onPause() {
            super.onPause()
            preferenceScreen.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
            when (key) {
                Prefs.KEY_THEME -> Prefs.applyThemeFromPrefs(requireContext())
                // Recreate so the new palette is generated for every surface, not just this screen.
                Prefs.KEY_DYNAMIC_COLOR, Prefs.KEY_ACCENT -> activity?.recreate()
                Prefs.KEY_PREFLIGHT -> PreflightScheduler.apply(requireContext().applicationContext)
            }
        }

        // ---- CSV export ----

        private fun defaultExportName(): String {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(java.util.Date())
            return "alarmtracker_export_$stamp.csv"
        }

        private fun exportTo(uri: Uri) {
            val appCtx = requireContext().applicationContext
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) {
                    val repo = AlarmRepository.get(appCtx)
                    val alarms = repo.allAlarms()
                    val events = repo.allEvents()
                    val signals = repo.allSleepSignals()
                    if (alarms.isEmpty() && events.isEmpty() && signals.isEmpty()) return@withContext null
                    try {
                        appCtx.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(DataExport.buildCsv(alarms, events, signals).toByteArray())
                        }
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                val msg = when (ok) {
                    null -> R.string.export_empty
                    true -> R.string.export_success
                    false -> R.string.export_failed
                }
                toast(msg)
            }
        }

        // ---- Wipe all data ----

        private fun confirmWipe() {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.wipe_dialog_title)
                .setMessage(R.string.wipe_dialog_body)
                .setNegativeButton(R.string.wipe_cancel, null)
                .setPositiveButton(R.string.wipe_confirm) { _, _ -> wipe() }
                .show()
        }

        private fun wipe() {
            val appCtx = requireContext().applicationContext
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    val repo = AlarmRepository.get(appCtx)
                    repo.wipeAll()
                    // commit(), not apply(): already off the main thread, and the clear must be
                    // flushed before rescheduleNext below reads settings back.
                    @Suppress("ApplySharedPref")
                    Prefs.get(appCtx).edit().clear().commit()
                    // No enabled alarms remain: cancel system registration + refresh widget.
                    AlarmScheduler.rescheduleNext(appCtx)
                }
                Prefs.applyThemeFromPrefs(appCtx)
                PreflightScheduler.apply(appCtx)
                toast(R.string.wipe_done)
                // Rebuild the preference screen so cleared toggles show their defaults.
                setPreferencesFromResource(R.xml.preferences, null)
                activity?.recreate()
            }
        }

        private fun toast(res: Int) {
            if (isAdded) Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
        }
    }
}
