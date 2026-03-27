package com.cheesus.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.cheesus.app.databinding.ActivityMainBinding
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), RecognitionListener {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private var speechService: SpeechService? = null
    private var isModelLoaded = false

    // Required permissions
    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    // Permission request launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startApp()
        } else {
            Toast.makeText(this, "Camera and microphone permissions are required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startApp()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startApp() {
        startCamera()
        loadVoskModel()
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return

        // Build a filename with timestamp so photos don't overwrite each other
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "CHEESUS_$timestamp")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CheesUs")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    runOnUiThread {
                        binding.statusText.text = getString(R.string.status_cheese)
                        // Briefly flash the status, then go back to listening
                        binding.root.postDelayed({
                            if (isModelLoaded) {
                                binding.statusText.text = getString(R.string.status_listening)
                            }
                        }, 2000)
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${e.message}", e)
                }
            }
        )
    }

    // ── Vosk Voice Recognition ─────────────────────────────────────────────────
    // Vosk runs a small AI model entirely on-device — no internet needed.
    // We only tell it to listen for "cheese" to keep it fast and private.

    private fun loadVoskModel() {
        binding.statusText.text = getString(R.string.status_loading)

        // StorageService unpacks the model from assets into internal storage on first run,
        // then loads it directly on subsequent launches.
        StorageService.unpack(
            this,
            "model-en-us",   // folder name inside app/src/main/assets/
            "model",         // destination folder name in internal storage
            { model: Model ->
                // Called on main thread when the model is ready
                isModelLoaded = true
                binding.statusText.text = getString(R.string.status_listening)
                val recognizer = Recognizer(model, 16000.0f, "[\"cheese\", \"[unk]\"]")
                speechService = SpeechService(recognizer, 16000.0f)
                speechService?.startListening(this)
            },
            { e: Exception ->
                Log.e(TAG, "Model load failed", e)
                binding.statusText.text = getString(R.string.status_error)
            }
        )
    }

    // ── RecognitionListener callbacks (called by Vosk) ────────────────────────

    override fun onPartialResult(hypothesis: String?) {
        // Called while you're still speaking — check for "cheese" early
        if (hypothesis?.contains("cheese", ignoreCase = true) == true) {
            runOnUiThread { takePhoto() }
        }
    }

    override fun onResult(hypothesis: String?) {
        // Called when Vosk is confident about a complete phrase
        if (hypothesis?.contains("cheese", ignoreCase = true) == true) {
            runOnUiThread { takePhoto() }
        }
    }

    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(e: Exception?) {
        Log.e(TAG, "Recognition error", e)
    }
    override fun onTimeout() {}

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CheesUs"
    }
}
