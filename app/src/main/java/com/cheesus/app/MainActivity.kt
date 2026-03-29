package com.cheesus.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class MainActivity : AppCompatActivity(), RecognitionListener {

    private lateinit var binding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private var speechService: SpeechService? = null
    private var isModelLoaded = false
    private var isTakingPhoto = false
    private var isBackCamera = true

    private var shutterTrack: AudioTrack? = null

    private val requiredPermissions = mutableListOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

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
        buildShutterTrack()

        binding.btnToggleCamera.setOnClickListener { toggleCamera() }
        binding.btnGallery.setOnClickListener { openGallery() }

        if (allPermissionsGranted()) {
            startApp()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // ── Shutter click (AudioTrack, USAGE_ALARM) ────────────────────────────────
    // Generates PCM audio directly — no files, no TTS, no system sounds.
    // USAGE_ALARM uses the alarm volume channel, which is independent of
    // media volume and plays even when the ringer is silenced.

    private fun buildShutterTrack() {
        val sampleRate = 44100
        val numSamples = sampleRate * 120 / 1000   // 120 ms
        val pcm = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val envelope = exp(-t * 30)             // fast exponential decay
            pcm[i] = (Short.MAX_VALUE * 0.8 * sin(2 * PI * 1000.0 * t) * envelope).toInt().toShort()
        }
        shutterTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(numSamples * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        shutterTrack?.write(pcm, 0, numSamples)
    }

    private fun playShutterSound() {
        val track = shutterTrack ?: return
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
        track.reloadStaticData()
        track.play()
    }

    // ── Permissions ───────────────────────────────────────────────────────────

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

            val selector = if (isBackCamera) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                CameraSelector.DEFAULT_FRONT_CAMERA
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, preview, imageCapture)
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleCamera() {
        isBackCamera = !isBackCamera
        startCamera()
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    private fun takePhoto() {
        if (isTakingPhoto) return
        isTakingPhoto = true
        val capture = imageCapture ?: run { isTakingPhoto = false; return }

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
                    playShutterSound()
                    runOnUiThread {
                        binding.statusText.text = getString(R.string.status_cheese)
                        binding.root.postDelayed({
                            isTakingPhoto = false
                            if (isModelLoaded) {
                                binding.statusText.text = getString(R.string.status_listening)
                            }
                        }, 2000)
                    }
                }

                override fun onError(e: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${e.message}", e)
                    isTakingPhoto = false
                }
            }
        )
    }

    // ── Vosk Voice Recognition ─────────────────────────────────────────────────

    private fun loadVoskModel() {
        binding.statusText.text = getString(R.string.status_loading)

        StorageService.unpack(
            this,
            "model-en-us",
            "model",
            { model: Model ->
                isModelLoaded = true
                binding.statusText.text = getString(R.string.status_listening)
                val recognizer = Recognizer(model, 16000.0f, "[\"cheese\", \"[unk]\"]")
                speechService = SpeechService(recognizer, 16000.0f)
                speechService?.startListening(this)
            },
            { e: Exception ->
                Log.e(TAG, "Model load failed: ${e.message}", e)
                binding.statusText.text = "Error: ${e.message}"
            }
        )
    }

    // ── RecognitionListener callbacks ─────────────────────────────────────────

    override fun onPartialResult(hypothesis: String?) {
        if (hypothesis?.contains("cheese", ignoreCase = true) == true) {
            runOnUiThread { takePhoto() }
        }
    }

    override fun onResult(hypothesis: String?) {
        if (hypothesis?.contains("cheese", ignoreCase = true) == true) {
            runOnUiThread { takePhoto() }
        }
    }

    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(e: Exception?) { Log.e(TAG, "Recognition error", e) }
    override fun onTimeout() {}

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        speechService?.stop()
        speechService?.shutdown()
        cameraExecutor.shutdown()
        shutterTrack?.stop()
        shutterTrack?.release()
        shutterTrack = null
    }

    companion object {
        private const val TAG = "CheesUs"
    }
}
