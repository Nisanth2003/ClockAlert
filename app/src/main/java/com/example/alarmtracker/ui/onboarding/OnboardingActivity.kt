package com.example.alarmtracker.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.alarmtracker.R
import com.example.alarmtracker.databinding.ActivityOnboardingBinding
import com.example.alarmtracker.databinding.ItemHealthCheckBinding
import com.example.alarmtracker.util.Prefs
import com.example.alarmtracker.util.Reliability
import com.google.android.material.color.MaterialColors

/**
 * First-run guided setup, shown once (gated by [Prefs.onboardingDone]). Three steps:
 *   1. Welcome + the two pillars (reliability + Wake Score).
 *   2. Reliability setup — grant the permissions that let alarms actually ring (reuses
 *      [Reliability.checks], the same source the Health Check screen uses). Re-renders on
 *      resume so a granted permission turns green when the user returns from Settings.
 *   3. Ready — hands off to the app (the empty state points at the + button).
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.onboardingRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        binding.skipBtn.visibility = View.GONE // mandatory flow — the required step can't be skipped
        binding.btnGetStarted.setOnClickListener { goTo(1) }
        binding.btnContinue.setOnClickListener { goTo(2) }
        binding.btnFinish.setOnClickListener { finishOnboarding() }
        binding.onbOverlayGrant.setOnClickListener { openOverlaySettings() }
        binding.onbAutostartOpen.setOnClickListener { openAutostart() }
        binding.onbAutostartAck.setOnCheckedChangeListener { _, _ -> updateGate() }

        // Back never bypasses the required step: step back through pages, and from the first page
        // send the task to the background (onboarding re-shows next launch) rather than finishing.
        onBackPressedDispatcher.addCallback(this) {
            val current = binding.onboardingFlipper.displayedChild
            if (current > 0) goTo(current - 1) else moveTaskToBack(true)
        }

        updateStep()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        renderRequired()
        renderChecks()
        updateGate()
    }

    /** The hard-gated required items: overlay (detectable) + OEM autostart (acknowledged). */
    private fun renderRequired() {
        val overlayOk = Settings.canDrawOverlays(this)
        binding.onbOverlayStatus.setText(
            if (overlayOk) R.string.onb_overlay_enabled else R.string.onb_overlay_required
        )
        binding.onbOverlayGrant.visibility = if (overlayOk) View.GONE else View.VISIBLE

        val oem = Reliability.oemGuidance(this)
        if (oem == null) {
            binding.onbAutostartBlock.visibility = View.GONE
        } else {
            binding.onbAutostartBlock.visibility = View.VISIBLE
            binding.onbAutostartName.setText(oem.nameRes)
            binding.onbAutostartBody.setText(oem.guidanceRes)
        }
    }

    /** Continue is enabled only once the required items are satisfied. */
    private fun updateGate() {
        val overlayOk = Settings.canDrawOverlays(this)
        val autostartOk = Reliability.oemGuidance(this) == null || binding.onbAutostartAck.isChecked
        binding.btnContinue.isEnabled = overlayOk && autostartOk
    }

    private fun openOverlaySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try { startActivity(Reliability.appDetailsIntent(this)) } catch (_: Exception) {}
        }
    }

    private fun openAutostart() {
        launchFix(Reliability.oemGuidance(this)?.intent)
    }

    private fun goTo(index: Int) {
        binding.onboardingFlipper.displayedChild = index
        updateStep()
    }

    private fun updateStep() {
        val step = binding.onboardingFlipper.displayedChild + 1
        val total = binding.onboardingFlipper.childCount
        binding.stepText.text = getString(R.string.onb_step_fmt, step, total)
    }

    private fun renderChecks() {
        val container = binding.onbChecksContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        // Overlay is promoted to the "required" block above — don't list it twice.
        Reliability.checks(this).filter { it.id != Reliability.Id.OVERLAY }.forEach { check ->
            val row = ItemHealthCheckBinding.inflate(inflater, container, false)
            row.checkTitle.setText(check.titleRes)
            row.checkSummary.setText(check.summaryRes)
            if (check.ok) {
                row.checkIcon.setImageResource(R.drawable.ic_check)
                row.checkIcon.setColorFilter(themeColor(androidx.appcompat.R.attr.colorPrimary))
                row.checkFix.visibility = View.GONE
            } else {
                row.checkIcon.setImageResource(R.drawable.ic_warning)
                row.checkIcon.setColorFilter(themeColor(androidx.appcompat.R.attr.colorError))
                row.checkFix.visibility = View.VISIBLE
                row.checkFix.setText(check.actionLabelRes)
                row.checkFix.isEnabled = check.intent != null
                row.checkFix.setOnClickListener { launchFix(check.intent) }
            }
            container.addView(row.root)
        }
    }

    private fun launchFix(intent: Intent?) {
        val target = intent ?: Reliability.appDetailsIntent(this)
        try {
            startActivity(target)
        } catch (_: Exception) {
            try {
                startActivity(Reliability.appDetailsIntent(this))
            } catch (_: Exception) {
                // Nothing we can open on this device — leave the row as-is.
            }
        }
    }

    private fun finishOnboarding() {
        Prefs.setOnboardingDone(this)
        finish()
    }

    private fun themeColor(attr: Int): Int =
        MaterialColors.getColor(binding.root, attr)
}
