package com.example.alarmtracker.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Manual share-sheet helpers (feature 8) — always user-initiated ACTION_SEND. */
object ShareUtil {

    /** Plain-text share via the system chooser. */
    fun shareText(context: Context, chooserTitle: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }

    /**
     * Shares a rendered image of [view] with accompanying [text]. Falls back to a
     * plain-text share if the image can't be written for any reason.
     */
    fun shareViewImage(context: Context, view: View, chooserTitle: String, text: String) {
        val uri = try {
            val bitmap = renderToBitmap(view)
            val dir = File(context.cacheDir, "shared").apply { mkdirs() }
            val file = File(dir, "report.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Exception) {
            null
        }
        if (uri == null) {
            shareText(context, chooserTitle, text)
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }

    private fun renderToBitmap(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        val bg = view.background
        if (bg != null) bg.draw(canvas) else canvas.drawColor(android.graphics.Color.WHITE)
        view.draw(canvas)
        return bitmap
    }
}
