package com.example.meditationapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer // Added
import android.os.Handler // Added
import android.os.Looper // Added
import android.util.Log
import android.widget.ImageButton // Added
import android.widget.TextView // Added
import android.widget.Toast
import java.util.Locale // Added
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import android.media.MediaPlayer // Import MediaPlayer
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.example.meditationapp.BuildConfig // Import BuildConfig
// It's good practice to use the specific R class from your package if available,
// but for this tool, direct R.layout/id might be more straightforward if full
// build environment isn't simulated. Assuming R references will resolve.


class MainActivity : ComponentActivity() {

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var previewView: PreviewView
    private var mediaPlayer: MediaPlayer? = null

    private var isMeditating = false
    private var meditationTimeMillis: Long = 5 * 60 * 1000 // Default 5 minutes
    private var countDownTimer: CountDownTimer? = null

    private lateinit var buttonStart: ImageButton
    private lateinit var textViewTime: TextView
    private lateinit var textViewGuide: TextView // Added

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startCamera()
            } else {
                Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ensure this matches your layout file name, e.g., from a previous step
        setContentView(R.layout.activity_main)

        // Ensure this matches your PreviewView's ID in the XML, e.g., from a previous step
        previewView = findViewById(R.id.previewView)

        Log.d("GeminiAPI", "API Key: ${BuildConfig.GEMINI_API_KEY}")
        // initializeGeminiClient() // Placeholder for actual client initialization

        buttonStart = findViewById(R.id.buttonStart)
        textViewTime = findViewById(R.id.textViewTime)
        textViewGuide = findViewById(R.id.textViewGuide) // Added

        updateTimerDisplay(meditationTimeMillis) // Show initial time
        updateGuideText("명상을 시작하려면 시작 버튼을 누르세요.") // Added

        buttonStart.setOnClickListener {
            if (isMeditating) {
                stopMeditation()
            } else {
                startMeditation()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindPreview(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        val preview: Preview = Preview.Builder().build()
        val cameraSelector: CameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        preview.setSurfaceProvider(previewView.surfaceProvider)

        try {
            cameraProvider.unbindAll() // Unbind use cases before rebinding
            cameraProvider.bindToLifecycle(this, cameraSelector, preview)
        } catch (e: Exception) {
            Log.e("CameraXApp", "Use case binding failed", e)
            Toast.makeText(this, "Failed to start camera: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Placeholder for actual Gemini Client Initialization
    // private fun initializeGeminiClient() {
    //     if (BuildConfig.GEMINI_API_KEY.startsWith("YOUR_API_KEY")) {
    //         Log.e("GeminiAPI", "API key not set in local.properties. Please replace YOUR_API_KEY_HERE with your actual key.")
    //         Toast.makeText(this, "API Key not configured", Toast.LENGTH_LONG).show()
    //         return
    //     }
    //     try {
    //         // Assuming GenerativeModel class exists from the placeholder library
    //         // generativeModel = GenerativeModel(
    //         //     modelName = "gemini-pro-vision",
    //         //     apiKey = BuildConfig.GEMINI_API_KEY
    //         // )
    //         Log.i("GeminiAPI", "Gemini AI Client Initialized (placeholder).")
    //         Toast.makeText(this, "Gemini Client Init (placeholder)", Toast.LENGTH_SHORT).show()
    //     } catch (e: Exception) {
    //         Log.e("GeminiAPI", "Error initializing Gemini AI Client (placeholder)", e)
    //         Toast.makeText(this, "Error initializing Gemini (placeholder): ${e.message}", Toast.LENGTH_LONG).show()
    //     }
    // }

    private fun playSound(soundResourceId: Int) {
        // Release any existing MediaPlayer instance
        mediaPlayer?.release()
        mediaPlayer = null

        try {
            mediaPlayer = MediaPlayer.create(this, soundResourceId)
            mediaPlayer?.setOnCompletionListener {
                // Release the MediaPlayer once playback is complete
                it.release()
                mediaPlayer = null
                Log.d("MediaPlayer", "Sound playback completed and resources released.")
            }
            mediaPlayer?.start()
            Log.d("MediaPlayer", "Sound playback started.")
        } catch (e: Exception) {
            Log.e("MediaPlayer", "Error playing sound", e)
            Toast.makeText(this, "Error playing sound: ${e.message}", Toast.LENGTH_SHORT).show()
            mediaPlayer?.release() // Clean up on error
            mediaPlayer = null
        }
    }

    // Example usage (to be called later, e.g., on button click):
    // To play start sound:
    // playSound(R.raw.singing_bowl_start)
    // To play end sound:
    // playSound(R.raw.singing_bowl_end)

    private fun startMeditation() {
        if (isMeditating) return

        isMeditating = true
        updateGuideText("숨을 깊게들이 마시고... 내쉬세요...") // Added
        // TODO: Change buttonStart icon to "stop" or indicate meditating state
        // buttonStart.setImageResource(R.drawable.ic_stop) // Example

        playSound(R.raw.singing_bowl_start) // Assumes R.raw.singing_bowl_start exists

        countDownTimer = object : CountDownTimer(meditationTimeMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                updateTimerDisplay(millisUntilFinished)
            }

            override fun onFinish() {
                stopMeditation()
                // Optionally, provide feedback that meditation finished naturally
                Toast.makeText(this@MainActivity, "Meditation finished", Toast.LENGTH_SHORT).show()
            }
        }.start()
        Log.d("MeditationApp", "Meditation started.")
    }

    private fun stopMeditation() {
        if (!isMeditating && countDownTimer == null) { // Check if it was never started
            updateGuideText("명상을 시작하려면 시작 버튼을 누르세요.") // Ensure default text if never started
            return
        }

        val wasMeditating = isMeditating // Capture state before changing
        isMeditating = false
        countDownTimer?.cancel()
        countDownTimer = null

        if (wasMeditating) { // Only if it was actually meditating
            updateGuideText("명상이 종료되었습니다. 잠시 현재의 느낌에 머무르세요.")
            playSound(R.raw.singing_bowl_end)
        }

        updateTimerDisplay(meditationTimeMillis) // Reset timer text to full duration
        // TODO: Change buttonStart icon back to "start"
        // buttonStart.setImageResource(R.drawable.ic_start) // Example

        if (wasMeditating) {
            Handler(Looper.getMainLooper()).postDelayed({
                if(!isMeditating) { // Check again, in case user restarted meditation quickly
                    updateGuideText("명상을 시작하려면 시작 버튼을 누르세요.")
                }
            }, 2000) // 2-second delay
        } else {
             // If not previously meditating (e.g. timer finished and this is a cleanup call from onFinish,
             // or was stopped before truly starting by clicking button again quickly), ensure default text is set.
             updateGuideText("명상을 시작하려면 시작 버튼을 누르세요.")
        }
        Log.d("MeditationApp", "Meditation stopped. Guide text updated.")
    }

    private fun updateTimerDisplay(millis: Long) {
        val minutes = (millis / 1000) / 60
        val seconds = (millis / 1000) % 60
        textViewTime.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun updateGuideText(text: String) {
        textViewGuide.text = text
        Log.d("MeditationApp", "Guide text updated: $text")
    }
}
