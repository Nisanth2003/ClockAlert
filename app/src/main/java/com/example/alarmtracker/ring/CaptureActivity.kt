package com.example.alarmtracker.ring

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.alarmtracker.R
import com.example.alarmtracker.databinding.ActivityCaptureBinding
import com.example.alarmtracker.util.CameraImage
import com.example.alarmtracker.util.PerceptualHash
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Setup-time camera surface. In BARCODE mode it scans a single code and returns its
 * raw value; in PHOTO mode it captures a reference photo and returns its perceptual
 * hash (never the image). Used from the edit sheet to register a QR/photo mission
 * target. Requests CAMERA itself and cancels cleanly if denied.
 */
class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding
    private lateinit var mode: String
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val barcodeScanner by lazy { BarcodeScanning.getClient() }
    private var controller: LifecycleCameraController? = null
    private var finished = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_BARCODE
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.captureInstruction.setText(
            if (mode == MODE_PHOTO) R.string.capture_photo_instruction
            else R.string.capture_barcode_instruction
        )
        binding.captureShutter.visibility = if (mode == MODE_PHOTO) View.VISIBLE else View.GONE
        binding.captureShutter.setOnClickListener { takePhoto() }
        binding.captureCancel.setOnClickListener { finishCancelled() }
        binding.captureDeniedClose.setOnClickListener { finishCancelled() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        binding.captureDenied.visibility = View.GONE
        val ctrl = LifecycleCameraController(this).apply {
            bindToLifecycle(this@CaptureActivity)
        }
        controller = ctrl
        binding.capturePreview.controller = ctrl
        if (mode == MODE_BARCODE) {
            ctrl.setImageAnalysisAnalyzer(analysisExecutor, ::analyzeForBarcode)
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeForBarcode(proxy: androidx.camera.core.ImageProxy) {
        val media = proxy.image
        if (media == null) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        barcodeScanner.process(input)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                if (value != null) onBarcode(value)
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun onBarcode(rawValue: String) {
        if (finished) return
        finished = true
        controller?.clearImageAnalysisAnalyzer()
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_RESULT_VALUE, rawValue))
        finish()
    }

    private fun takePhoto() {
        val ctrl = controller ?: return
        binding.captureShutter.isEnabled = false
        ctrl.takePicture(
            analysisExecutor,
            object : androidx.camera.core.ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                    val bmp = try {
                        CameraImage.toBitmap(image)
                    } finally {
                        image.close()
                    }
                    val hash = bmp?.let { PerceptualHash.compute(it) }
                    runOnUiThread {
                        if (hash == null) {
                            binding.captureShutter.isEnabled = true
                            Toast.makeText(
                                this@CaptureActivity,
                                R.string.capture_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            setResult(
                                Activity.RESULT_OK,
                                Intent().putExtra(EXTRA_RESULT_VALUE, hash)
                            )
                            finish()
                        }
                    }
                }

                override fun onError(exception: androidx.camera.core.ImageCaptureException) {
                    runOnUiThread {
                        binding.captureShutter.isEnabled = true
                        Toast.makeText(
                            this@CaptureActivity,
                            R.string.capture_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun showDenied() {
        binding.captureDenied.visibility = View.VISIBLE
    }

    private fun finishCancelled() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        barcodeScanner.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_RESULT_VALUE = "extra_result_value"
        const val MODE_BARCODE = "BARCODE"
        const val MODE_PHOTO = "PHOTO"

        fun intent(context: Context, mode: String): Intent =
            Intent(context, CaptureActivity::class.java).putExtra(EXTRA_MODE, mode)
    }
}
