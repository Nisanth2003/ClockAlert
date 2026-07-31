package com.example.alarmtracker.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.camera.core.ImageProxy

/** Decodes a JPEG [ImageProxy] (from CameraX ImageCapture) into an upright [Bitmap]. */
object CameraImage {

    fun toBitmap(proxy: ImageProxy): Bitmap? {
        val buffer = proxy.planes.firstOrNull()?.buffer ?: return null
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val rotation = proxy.imageInfo.rotationDegrees
        if (rotation == 0) return bmp
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        if (rotated != bmp) bmp.recycle()
        return rotated
    }
}
