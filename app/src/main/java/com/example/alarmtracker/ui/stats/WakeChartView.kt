package com.example.alarmtracker.ui.stats

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.appcompat.R as AppCompatR
import com.example.alarmtracker.R
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * Minimal line/bar chart drawn with Paint, themed via MaterialColors.
 * Handles 0 and 1 data points without crashing (0 → centered "No data" hint,
 * 1 → single dot / single bar).
 */
class WakeChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { LINE, BAR }

    data class Point(val label: String, val value: Float)

    private var mode: Mode = Mode.LINE
    private var points: List<Point> = emptyList()
    private var valueFormatter: (Float) -> String = { it.toInt().toString() }

    private val density = resources.displayMetrics.density

    /** Reused across frames — allocating a Path per draw churns the GC while Stats scrolls. */
    private val linePath = Path()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = MaterialColors.getColor(this@WakeChartView, AppCompatR.attr.colorPrimary)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MaterialColors.getColor(this@WakeChartView, AppCompatR.attr.colorPrimary)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MaterialColors.getColor(this@WakeChartView, AppCompatR.attr.colorPrimary)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = MaterialColors.getColor(this@WakeChartView, MaterialR.attr.colorOutlineVariant)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics
        )
        color = MaterialColors.getColor(this@WakeChartView, MaterialR.attr.colorOnSurfaceVariant)
    }

    fun setData(mode: Mode, points: List<Point>, valueFormatter: (Float) -> String) {
        this.mode = mode
        this.points = points
        this.valueFormatter = valueFormatter
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        if (points.isEmpty()) {
            val hint = resources.getString(R.string.chart_no_data)
            canvas.drawText(
                hint,
                (width - labelPaint.measureText(hint)) / 2f,
                height / 2f + labelPaint.textSize / 3,
                labelPaint
            )
            return
        }

        val labelHeight = labelPaint.textSize + 6 * density
        var yMin: Float
        var yMax: Float
        if (mode == Mode.BAR) {
            yMin = 0f
            yMax = max(points.maxOf { it.value }, 1f)
            yMax = ceil(yMax) // integer axis for counts
        } else {
            yMin = points.minOf { it.value }
            yMax = points.maxOf { it.value }
            if (yMax - yMin < 30f) { // flat series / single point: pad the window
                val mid = (yMax + yMin) / 2f
                yMin = mid - 30f
                yMax = mid + 30f
            }
        }
        val ySpan = max(yMax - yMin, 0.001f)

        // Measure Y labels to size the left gutter.
        val gridValues = listOf(yMin, (yMin + yMax) / 2f, yMax)
        val yLabels = gridValues.map { valueFormatter(it) }
        val gutter = yLabels.maxOf { labelPaint.measureText(it) } + 8 * density

        val plotLeft = paddingLeft + gutter
        val plotRight = width - paddingRight.toFloat()
        val plotTop = paddingTop + labelPaint.textSize
        val plotBottom = height - paddingBottom - labelHeight
        if (plotRight <= plotLeft || plotBottom <= plotTop) return
        val plotWidth = plotRight - plotLeft
        val plotHeight = plotBottom - plotTop

        fun yFor(value: Float): Float = plotBottom - (value - yMin) / ySpan * plotHeight

        // Gridlines + Y labels (duplicate label texts drawn once, e.g. [0, 0.5, 1] → "0", "1").
        val drawnYLabels = mutableSetOf<String>()
        gridValues.forEachIndexed { i, v ->
            val y = yFor(v)
            canvas.drawLine(plotLeft, y, plotRight, y, gridPaint)
            if (drawnYLabels.add(yLabels[i])) {
                canvas.drawText(
                    yLabels[i],
                    plotLeft - 8 * density - labelPaint.measureText(yLabels[i]),
                    y + labelPaint.textSize / 3,
                    labelPaint
                )
            }
        }

        val n = points.size
        val slot = plotWidth / n
        fun xFor(index: Int): Float = plotLeft + slot * index + slot / 2f

        when (mode) {
            Mode.LINE -> {
                if (n == 1) {
                    canvas.drawCircle(xFor(0), yFor(points[0].value), 4 * density, dotPaint)
                } else {
                    linePath.reset()
                    points.forEachIndexed { i, p ->
                        val x = xFor(i)
                        val y = yFor(p.value)
                        if (i == 0) linePath.moveTo(x, y) else linePath.lineTo(x, y)
                    }
                    canvas.drawPath(linePath, linePaint)
                    points.forEachIndexed { i, p ->
                        canvas.drawCircle(xFor(i), yFor(p.value), 3 * density, dotPaint)
                    }
                }
            }

            Mode.BAR -> {
                val barWidth = (slot * 0.6f).coerceAtMost(28 * density)
                points.forEachIndexed { i, p ->
                    val cx = xFor(i)
                    val top = yFor(p.value)
                    canvas.drawRoundRect(
                        cx - barWidth / 2, top, cx + barWidth / 2, plotBottom,
                        3 * density, 3 * density, barPaint
                    )
                }
            }
        }

        // X labels: first, last, and (when it fits) the middle one.
        val labelY = height - paddingBottom.toFloat()
        val indices = when {
            n == 1 -> listOf(0)
            n == 2 -> listOf(0, n - 1)
            else -> listOf(0, n / 2, n - 1)
        }.distinct()
        indices.forEach { i ->
            val text = points[i].label
            val w = labelPaint.measureText(text)
            val x = (xFor(i) - w / 2).coerceIn(plotLeft, plotRight - w)
            canvas.drawText(text, x, labelY, labelPaint)
        }
    }
}
