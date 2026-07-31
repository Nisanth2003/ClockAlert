package com.example.alarmtracker.ui.map

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import androidx.core.content.ContextCompat
import com.example.alarmtracker.R
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.hypot

/**
 * The alert ring drawn around the centre pin: cross this circle and the alarm rings. Seeing it is how
 * the user judges how much warning they actually get — a 200 m ring is "you're there", a 2 km ring is
 * "a few minutes out".
 *
 * The circle is always centred on the pin (the map centre), so rather than storing a centre it reads
 * it at draw time. The pixel radius is derived by projecting a point exactly [radiusMeters] north of
 * the centre and measuring the on-screen distance — exact at any zoom, latitude, DPI-tile-scaling
 * setting or map rotation, with no ground-resolution assumptions of our own.
 */
class RadiusOverlay(private val map: MapView) : Overlay() {

    /** Ring radius in metres. Zero or less draws nothing. */
    var radiusMeters: Double = 0.0

    private val centrePixel = Point()
    private val northPixel = Point()

    private val fillPaint = Paint().apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(map.context, R.color.map_ring_fill)
        isAntiAlias = true
    }

    private val strokePaint = Paint().apply {
        style = Paint.Style.STROKE
        color = ContextCompat.getColor(map.context, R.color.map_ring_stroke)
        strokeWidth = map.context.resources.displayMetrics.density * 3f
        isAntiAlias = true
    }

    /**
     * osmdroid dispatches through this signature; the Projection one below is the newer API. Both are
     * overridden on purpose - overriding only the newer one left the ring invisible, and whichever
     * path the library takes, exactly one of these runs (neither calls the other).
     */
    override fun draw(canvas: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        draw(canvas, mapView.projection)
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        if (radiusMeters <= 0.0) return
        val centre = map.mapCenter
        // One degree of latitude is ~111.32 km everywhere, so this needs no longitude correction.
        val offsetLat = (centre.latitude + radiusMeters / METRES_PER_DEGREE_LAT).coerceIn(-89.9, 89.9)
        projection.toPixels(GeoPoint(centre.latitude, centre.longitude), centrePixel)
        projection.toPixels(GeoPoint(offsetLat, centre.longitude), northPixel)
        val radiusPx = hypot(
            (northPixel.x - centrePixel.x).toDouble(),
            (northPixel.y - centrePixel.y).toDouble()
        ).toFloat()
        if (radiusPx < 1f) return
        canvas.drawCircle(centrePixel.x.toFloat(), centrePixel.y.toFloat(), radiusPx, fillPaint)
        canvas.drawCircle(centrePixel.x.toFloat(), centrePixel.y.toFloat(), radiusPx, strokePaint)
    }

    private companion object {
        const val METRES_PER_DEGREE_LAT = 111_320.0
    }
}
