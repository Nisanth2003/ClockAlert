package com.example.alarmtracker.ui.map

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.view.Surface
import android.view.WindowManager

/**
 * Which way the phone (and so the user) is pointing, in degrees clockwise from TRUE north — what the
 * map needs to draw a "you are facing this way" arrow and to run compass mode.
 *
 * Uses the fused rotation-vector sensor rather than raw magnetometer + accelerometer, so the
 * platform does the sensor fusion and tilt compensation. The reading is magnetic north, so the local
 * declination (from [onLocation]) is added to get true north — [Location.setBearing] and every map
 * bearing are defined against true north, and the two differ by double-digit degrees in some places.
 *
 * Readings are low-pass filtered and only reported on a real change, because a raw compass jitters
 * by a couple of degrees constantly and every report costs a map redraw.
 */
class CompassHeading(
    private val context: Context,
    private val onHeading: (Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val rotationVector: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotationMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    private var declination = 0f
    private var smoothed = Float.NaN
    private var reported = Float.NaN
    private var running = false

    /** False on the rare device with no rotation-vector sensor — callers hide the heading UI. */
    val isAvailable: Boolean get() = rotationVector != null

    fun start() {
        val sensor = rotationVector ?: return
        if (running) return
        running = sensorManager?.registerListener(
            this, sensor, SensorManager.SENSOR_DELAY_UI
        ) == true
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager?.unregisterListener(this)
        smoothed = Float.NaN
        reported = Float.NaN
    }

    /** Keeps magnetic-to-true-north correction right for wherever the user actually is. */
    fun onLocation(location: Location) {
        declination = GeomagneticField(
            location.latitude.toFloat(),
            location.longitude.toFloat(),
            location.altitude.toFloat(),
            System.currentTimeMillis()
        ).declination
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        // The sensor frame is tied to the device's natural orientation; remap it to however the
        // screen is currently turned, or the heading is 90° out in landscape.
        val (axisX, axisY) = when (displayRotation()) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val magnetic = Math.toDegrees(orientation[0].toDouble()).toFloat()
        val target = normalize(magnetic + declination)

        smoothed = if (smoothed.isNaN()) target else normalize(smoothed + delta(smoothed, target) * SMOOTHING)
        if (reported.isNaN() || kotlin.math.abs(delta(reported, smoothed)) >= REPORT_THRESHOLD_DEG) {
            reported = smoothed
            onHeading(smoothed)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            context.getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation
                ?: Surface.ROTATION_0
        }

    companion object {
        private const val SMOOTHING = 0.25f
        private const val REPORT_THRESHOLD_DEG = 1.5f

        /** Shortest signed turn from [from] to [to], in (-180, 180]. */
        fun delta(from: Float, to: Float): Float {
            var d = (to - from) % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }

        fun normalize(degrees: Float): Float {
            var d = degrees % 360f
            if (d < 0f) d += 360f
            return d
        }
    }
}
