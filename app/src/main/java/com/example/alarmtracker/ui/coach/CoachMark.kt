package com.example.alarmtracker.ui.coach

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.example.alarmtracker.R
import com.example.alarmtracker.databinding.ViewCoachCaptionBinding

/** One walkthrough step: spotlight [target] (or nothing, centred) and explain it. */
data class CoachStep(val target: View?, val title: CharSequence, val body: CharSequence)

/**
 * A lightweight guided walkthrough: dims the screen, punches a rounded highlight around each
 * target view in turn, and shows a caption with Skip / Next. Pure in-house, no dependency.
 */
object CoachMark {

    fun show(activity: Activity, steps: List<CoachStep>, onFinished: () -> Unit = {}) {
        if (steps.isEmpty()) {
            onFinished()
            return
        }
        val root = activity.findViewById<FrameLayout>(android.R.id.content) ?: run {
            onFinished()
            return
        }
        val density = activity.resources.displayMetrics.density

        val overlay = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true // swallow taps so the UI underneath isn't triggered
            isFocusable = true
        }
        val scrim = ScrimView(activity)
        overlay.addView(
            scrim,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val caption = ViewCoachCaptionBinding.inflate(activity.layoutInflater, overlay, false)
        val capLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ).apply {
            val m = (16 * density).toInt()
            setMargins(m, m, m, (120 * density).toInt())
        }
        overlay.addView(caption.root, capLp)
        root.addView(overlay)

        var index = 0
        fun finish() {
            root.removeView(overlay)
            onFinished()
        }
        fun render() {
            val step = steps[index]
            caption.coachStep.text = activity.getString(R.string.coach_step_fmt, index + 1, steps.size)
            caption.coachTitle.text = step.title
            caption.coachBody.text = step.body
            caption.coachNext.setText(
                if (index == steps.lastIndex) R.string.coach_got_it else R.string.coach_next
            )
            val target = step.target
            if (target != null && target.isShown && target.width > 0) {
                val t = IntArray(2); target.getLocationInWindow(t)
                val o = IntArray(2); overlay.getLocationInWindow(o)
                val pad = 8 * density
                scrim.setHole(
                    RectF(
                        (t[0] - o[0]) - pad,
                        (t[1] - o[1]) - pad,
                        (t[0] - o[0]) + target.width + pad,
                        (t[1] - o[1]) + target.height + pad
                    ),
                    20 * density
                )
            } else {
                scrim.setHole(null, 0f)
            }
        }

        caption.coachNext.setOnClickListener {
            if (index < steps.lastIndex) {
                index++
                render()
            } else {
                finish()
            }
        }
        caption.coachSkip.setOnClickListener { finish() }
        overlay.post { render() }
    }

    /** Full-screen dim with a transparent rounded "hole" around the highlighted view. */
    private class ScrimView(context: Context) : View(context) {
        private var hole: RectF? = null
        private var radius = 0f
        private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null) // PorterDuff.CLEAR needs a software layer
        }

        fun setHole(rect: RectF?, cornerRadius: Float) {
            hole = rect
            radius = cornerRadius
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0xCC000000.toInt())
            hole?.let { canvas.drawRoundRect(it, radius, radius, clearPaint) }
        }
    }
}
