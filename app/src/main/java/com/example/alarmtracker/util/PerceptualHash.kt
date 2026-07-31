package com.example.alarmtracker.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.scale

/**
 * Tiny on-device perceptual hash (dHash) for the photo dismiss mission. NO cloud ML.
 *
 * dHash downsizes to a 9x8 grayscale grid and encodes, per row, whether each pixel
 * is brighter than its right neighbour — 64 bits packed into a 16-char hex string.
 * Similar-looking photos map to hashes a small Hamming distance apart, so a re-shot
 * of the same scene dismisses even with modest framing/lighting changes.
 */
object PerceptualHash {

    private const val W = 9
    private const val H = 8

    /** 16-char hex dHash of [bitmap]. */
    fun compute(bitmap: Bitmap): String {
        val small = bitmap.scale(W, H)
        val gray = IntArray(W * H)
        for (y in 0 until H) {
            for (x in 0 until W) {
                val p = small.getPixel(x, y)
                // Rec. 601 luma.
                gray[y * W + x] =
                    (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)).toInt()
            }
        }
        if (small != bitmap) small.recycle()
        var bits = 0L
        var index = 0
        for (y in 0 until H) {
            for (x in 0 until W - 1) {
                val left = gray[y * W + x]
                val right = gray[y * W + x + 1]
                if (left > right) bits = bits or (1L shl index)
                index++
            }
        }
        return String.format("%016x", bits)
    }

    /** Number of differing bits between two hex dHashes; Int.MAX_VALUE if either is unparseable. */
    fun hammingDistance(a: String?, b: String?): Int {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return Int.MAX_VALUE
        return try {
            val x = a.toULong(16).toLong()
            val y = b.toULong(16).toLong()
            java.lang.Long.bitCount(x xor y)
        } catch (_: NumberFormatException) {
            Int.MAX_VALUE
        }
    }

    /** Threshold under which two photos count as the same scene. */
    const val MATCH_THRESHOLD = 12
}
