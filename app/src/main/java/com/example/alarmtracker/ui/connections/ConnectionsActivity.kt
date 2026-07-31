package com.example.alarmtracker.ui.connections

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.alarmtracker.R
import com.example.alarmtracker.connector.ConnectorAlarmSync
import com.example.alarmtracker.connector.ConnectorScheduler
import com.example.alarmtracker.connector.JiraConnector
import com.example.alarmtracker.databinding.ActivityConnectionsBinding
import com.example.alarmtracker.databinding.DialogJiraConnectBinding
import com.example.alarmtracker.util.Features
import com.example.alarmtracker.util.NetworkState
import com.example.alarmtracker.util.Prefs
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Settings → Connections. Link external services (Jira available now; Google Calendar pending its
 * OAuth setup) so AlarmTracker sets alarms for what's due. Connectors are a Pro feature; until
 * real billing is wired, the upsell offers a local "Unlock Pro (dev)" toggle for testing.
 */
class ConnectionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectionsBinding

    /** Interval options shown to the user, paired with their poll period in hours. */
    private val intervalHours = longArrayOf(24, 12, 6, 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityConnectionsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.connectionsRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, right = bars.right)
            binding.connectionsScroll.updatePadding(bottom = bars.bottom + v.paddingBottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.jiraPrimaryBtn.setOnClickListener { onJiraPrimary() }
        binding.jiraCheckNow.setOnClickListener { checkNow() }
        binding.jiraIntervalBtn.setOnClickListener { showIntervalPicker() }
        binding.calendarSetupBtn.setOnClickListener { onCalendarSetup() }
        binding.calendarGuideBtn.setOnClickListener { showCalendarGuide() }
    }

    private val calendarPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { renderCalendar() }

    private fun onCalendarSetup() {
        if (com.example.alarmtracker.util.CalendarAlarm.hasPermission(this)) {
            // Already granted → jump straight to creating a calendar alarm.
            startActivity(
                Intent(this, com.example.alarmtracker.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        } else {
            calendarPermission.launch(android.Manifest.permission.READ_CALENDAR)
        }
    }

    private fun showCalendarGuide() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.calendar_guide_title)
            .setMessage(R.string.calendar_guide_body)
            .setNegativeButton(android.R.string.ok, null)
            .setPositiveButton(R.string.calendar_open_alarms) { _, _ ->
                startActivity(
                    Intent(this, com.example.alarmtracker.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
            .show()
    }

    private fun renderCalendar() {
        val ready = com.example.alarmtracker.util.CalendarAlarm.hasPermission(this)
        binding.calendarStatus.setText(
            if (ready) R.string.connector_calendar_ready else R.string.connector_calendar_not_set
        )
        binding.calendarSetupBtn.setText(
            if (ready) R.string.calendar_open_alarms else R.string.connector_calendar_setup
        )
    }

    override fun onResume() {
        super.onResume()
        render()
        renderCalendar()
    }

    private fun render() {
        val connected = JiraConnector.isConnected(this)
        if (connected) {
            binding.jiraStatus.text =
                getString(R.string.connector_status_connected_fmt, JiraConnector.accountLabel(this).orEmpty())
            binding.jiraPrimaryBtn.setText(R.string.connector_disconnect)
            binding.jiraConnectedGroup.visibility = View.VISIBLE
            updateIntervalLabel()
        } else {
            binding.jiraStatus.setText(R.string.connector_status_not_connected)
            binding.jiraPrimaryBtn.setText(R.string.connector_connect)
            binding.jiraConnectedGroup.visibility = View.GONE
        }
    }

    private fun onJiraPrimary() {
        if (JiraConnector.isConnected(this)) {
            JiraConnector.disconnect(this)
            ConnectorScheduler.apply(applicationContext)
            binding.jiraSyncStatus.visibility = View.GONE
            render()
            return
        }
        if (!Features.isEnabled(this, Features.Feature.CONNECTORS)) {
            showProUpsell()
            return
        }
        showJiraConnectDialog()
    }

    private fun showProUpsell() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pro_upsell_title)
            .setMessage(R.string.pro_upsell_body)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.pro_unlock_dev) { _, _ ->
                Features.setProUnlocked(this, true)
                toast(getString(R.string.pro_unlocked_toast))
                showJiraConnectDialog()
            }
            .show()
    }

    private fun showJiraConnectDialog() {
        val dv = DialogJiraConnectBinding.inflate(layoutInflater)
        dv.jiraOpenTokens.setOnClickListener {
            openUrl("https://id.atlassian.com/manage-profile/security/api-tokens")
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.jira_connect_title)
            .setView(dv.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.connector_connect, null) // overridden below to gate dismiss
            .create()
        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val url = dv.jiraUrlInput.text?.toString()?.trim().orEmpty()
                val email = dv.jiraEmailInput.text?.toString()?.trim().orEmpty()
                val token = dv.jiraTokenInput.text?.toString()?.trim().orEmpty()
                if (url.isEmpty() || email.isEmpty() || token.isEmpty()) {
                    toast(getString(R.string.jira_missing_fields))
                    return@setOnClickListener
                }
                // Offline, the catch below would tell them to check the URL, email and token — all
                // three of which are fine. Never send someone hunting a credential bug that isn't there.
                if (NetworkState.blocked(this@ConnectionsActivity)) {
                    NetworkState.promptToConnect(this@ConnectionsActivity, R.string.net_feature_jira)
                    return@setOnClickListener
                }
                positive.isEnabled = false
                lifecycleScope.launch {
                    try {
                        val name = JiraConnector.connect(applicationContext, url, email, token)
                        toast(getString(R.string.jira_connect_ok_fmt, name))
                        ConnectorScheduler.apply(applicationContext)
                        dialog.dismiss()
                        render()
                        checkNow() // pull the first batch immediately
                    } catch (e: Exception) {
                        positive.isEnabled = true
                        toast(
                            NetworkState.explain(
                                this@ConnectionsActivity, R.string.jira_connect_failed
                            )
                        )
                    }
                }
            }
        }
        dialog.show()
    }

    private fun checkNow() {
        binding.jiraSyncStatus.visibility = View.VISIBLE
        if (NetworkState.blocked(this)) {
            binding.jiraSyncStatus.text = NetworkState.explain(this, R.string.connector_sync_failed)
            NetworkState.promptToConnect(this, R.string.net_feature_jira)
            return
        }
        binding.jiraCheckNow.isEnabled = false
        binding.jiraSyncStatus.setText(R.string.connector_checking)
        lifecycleScope.launch {
            val msg = try {
                val items = JiraConnector.poll(applicationContext)
                val n = ConnectorAlarmSync.sync(applicationContext, JiraConnector, items)
                if (n > 0) getString(R.string.connector_synced_fmt, n) else getString(R.string.connector_synced_none)
            } catch (e: Exception) {
                // Reached Jira and it refused? That's the Jira message. Never left the phone? Say that.
                NetworkState.explain(this@ConnectionsActivity, R.string.connector_sync_failed)
            }
            binding.jiraSyncStatus.text = msg
            binding.jiraCheckNow.isEnabled = true
        }
    }

    private fun showIntervalPicker() {
        val entries = resources.getStringArray(R.array.connector_interval_entries)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.connector_interval_title)
            .setSingleChoiceItems(entries, intervalIndex()) { d, which ->
                Prefs.setConnectorIntervalHours(this, intervalHours[which])
                ConnectorScheduler.apply(applicationContext)
                updateIntervalLabel()
                d.dismiss()
            }
            .show()
    }

    private fun updateIntervalLabel() {
        val entries = resources.getStringArray(R.array.connector_interval_entries)
        binding.jiraIntervalBtn.text =
            getString(R.string.connector_interval_label_fmt, entries[intervalIndex()])
    }

    private fun intervalIndex(): Int =
        intervalHours.indexOf(Prefs.connectorIntervalHours(this)).coerceAtLeast(0)

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast(getString(R.string.connector_no_browser))
        }
    }

    private fun toast(text: String) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
    }
}
