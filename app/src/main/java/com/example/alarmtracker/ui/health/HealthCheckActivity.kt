package com.example.alarmtracker.ui.health

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.alarmtracker.R
import com.example.alarmtracker.databinding.ActivityHealthCheckBinding
import com.example.alarmtracker.databinding.ItemHealthCheckBinding
import com.example.alarmtracker.util.Reliability

/**
 * Full reliability & permission checklist (feature 5). Expands the alarm-list
 * warning banner into a screen covering every condition that can silently stop an
 * alarm, each with a status and one-tap fix, plus OEM-specific battery guidance.
 * The missed-alarm postmortem routes its one-tap fixes here.
 */
class HealthCheckActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHealthCheckBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityHealthCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.healthRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, right = bars.right)
            binding.healthScroll.updatePadding(bottom = bars.bottom + v.paddingBottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        renderChecks()
        renderOem()
    }

    private fun renderChecks() {
        val container = binding.checksContainer
        container.removeAllViews()
        val checks = Reliability.checks(this)
        binding.allClearCard.visibility =
            if (checks.none { !it.ok }) android.view.View.VISIBLE else android.view.View.GONE

        val inflater = LayoutInflater.from(this)
        checks.forEach { check ->
            val row = ItemHealthCheckBinding.inflate(inflater, container, false)
            row.checkTitle.setText(check.titleRes)
            row.checkSummary.setText(check.summaryRes)
            if (check.ok) {
                row.checkIcon.setImageResource(R.drawable.ic_check)
                row.checkIcon.contentDescription = getString(R.string.health_ok_icon)
                row.checkIcon.setColorFilter(themeColor(androidx.appcompat.R.attr.colorPrimary))
                row.checkFix.visibility = android.view.View.GONE
            } else {
                row.checkIcon.setImageResource(R.drawable.ic_warning)
                row.checkIcon.contentDescription = getString(R.string.health_problem_icon)
                row.checkIcon.setColorFilter(themeColor(androidx.appcompat.R.attr.colorError))
                row.checkFix.visibility = android.view.View.VISIBLE
                row.checkFix.setText(check.actionLabelRes)
                row.checkFix.setOnClickListener { launchFix(check.intent) }
                row.checkFix.isEnabled = check.intent != null
            }
            container.addView(row.root)
        }
    }

    private fun renderOem() {
        val oem = Reliability.oemGuidance(this)
        if (oem == null) {
            binding.oemCard.visibility = android.view.View.GONE
            return
        }
        binding.oemCard.visibility = android.view.View.VISIBLE
        binding.oemName.setText(oem.nameRes)
        binding.oemBody.setText(oem.guidanceRes)
        binding.oemAction.setOnClickListener { launchFix(oem.intent) }
    }

    private fun launchFix(intent: Intent?) {
        val target = intent ?: Reliability.appDetailsIntent(this)
        try {
            startActivity(target)
        } catch (_: Exception) {
            // OEM component missing / settings screen unavailable — last-resort app details.
            try {
                startActivity(Reliability.appDetailsIntent(this))
            } catch (_: Exception) {
                // Nothing we can open on this device.
            }
        }
    }

    private fun themeColor(attr: Int): Int =
        com.google.android.material.color.MaterialColors.getColor(binding.root, attr)
}
