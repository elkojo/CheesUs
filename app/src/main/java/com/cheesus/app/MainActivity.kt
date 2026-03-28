package com.cheesus.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import java.io.File
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
    private var isTakingPhoto = false

    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null

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
        initTts()

        if (allPermissionsGranted()) {
            startApp()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // ── TTS → pre-synthesize to file → MediaPlayer ────────────────────────────
    // We synthesize "HALLOUMI" to a WAV file once at startup. At photo time we
    // play that file via MediaPlayer, which runs independently of the Vosk
    // AudioRecord session and is guaranteed to come out of the speaker.

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS init failed with status $status")
                return@TextToSpeech
            }
            val langResult = tts?.setLanguage(Locale.US) ?: TextToSpeech.LANG_NOT_SUPPORTED
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS language not supported: $langResult")
                return@TextToSpeech
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == UTTERANCE_SYNTH) prepareMediaPlayer()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS synthesis error for utterance $utteranceId")
                }
            })
            val outFile = File(filesDir, "halloumi.wav")
            tts?.synthesizeToFile("HALLOUMI", null, outFile, UTTERANCE_SYNTH)
        }
    }

    private fun prepareMediaPlayer() {
        val file = File(filesDir, "halloumi.wav")
        if (!file.exists()) { Log.e(TAG, "Synthesized audio file missing"); return }
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                prepare()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer prepare failed: ${e.message}", e)
        }
    }

    private fun playHalloumi() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) mp.seekTo(0) else mp.start()
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startApp() {
        startCamera()
        loadVoskModel()
    }

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
                    playHalloumi()
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
        tts?.shutdown()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    companion object {
        private const val TAG = "CheesUs"
        private const val UTTERANCE_SYNTH = "synth_halloumi"
    }
}
