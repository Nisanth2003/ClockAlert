package com.example.alarmtracker.ui.postmortem

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.example.alarmtracker.R
import com.example.alarmtracker.data.AlarmRepository
import com.example.alarmtracker.databinding.ActivityPostmortemBinding
import com.example.alarmtracker.databinding.ItemPostmortemBinding
import com.example.alarmtracker.ui.health.HealthCheckActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Missed-Alarm Postmortem (feature 1). Lists recent MISSED / FIRED_LATE events with
 * a per-incident diagnosis; each incident's one-tap fix routes to the reliability
 * checklist. Re-runs on resume so returning from a fix updates the current-state causes.
 */
class PostmortemActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPostmortemBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPostmortemBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.postmortemRoot) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(left = bars.left, right = bars.right)
            binding.postmortemScroll.updatePadding(bottom = bars.bottom + v.paddingBottom)
            insets
        }
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val incidents = withContext(Dispatchers.Default) {
                val repo = AlarmRepository.get(applicationContext)
                val since = System.currentTimeMillis() - WINDOW_MS
                PostmortemAnalyzer.analyze(applicationContext, repo.problemsSince(since))
            }
            render(incidents)
        }
    }

    private fun render(incidents: List<PostmortemAnalyzer.Incident>) {
        val container = binding.incidentsContainer
        container.removeAllViews()
        binding.postmortemEmptyState.visibility =
            if (incidents.isEmpty()) View.VISIBLE else View.GONE

        val inflater = layoutInflater
        incidents.forEach { incident ->
            val row = ItemPostmortemBinding.inflate(inflater, container, false)
            row.incidentHeadline.setText(incident.headlineRes)
            row.incidentWhen.text = incident.whenText
            incident.causes.forEach { cause ->
                val tv = TextView(this).apply {
                    text = getString(R.string.bullet_line, cause)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                    setTextColor(
                        com.google.android.material.color.MaterialColors.getColor(
                            this, com.google.android.material.R.attr.colorOnSurfaceVariant
                        )
                    )
                }
                row.incidentCauses.addView(tv)
            }
            row.incidentFix.setOnClickListener {
                startActivity(Intent(this, HealthCheckActivity::class.java))
            }
            container.addView(row.root)
        }
    }

    private companion object {
        const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000
    }
}
