package com.example.meditationapp

import android.Manifest
import android.content.Intent // Added
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer // Added
import android.os.Handler // Added
import android.os.Looper // Added
import android.util.Log
import androidx.camera.core.ImageAnalysis // Added
import androidx.camera.core.ImageProxy // Added
import android.graphics.Bitmap // Added
import android.graphics.BitmapFactory // Added
import android.graphics.ImageFormat // Added
import android.graphics.Rect // Added
import android.graphics.YuvImage // Added
import java.io.ByteArrayOutputStream // Added
// import com.google.ai.client.generativeai.GenerativeModel // Placeholder for actual Gemini SDK
import android.widget.ImageButton // Added
import android.widget.TextView // Added
import android.widget.Toast
import android.widget.Spinner // Added
import android.widget.AdapterView // Added
import android.content.res.Configuration // Added
// ArrayAdapter might be needed if not using android:entries, but not for this example
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
import java.util.concurrent.ExecutorService // Added
import java.util.concurrent.Executors // Added
// It's good practice to use the specific R class from your package if available,
// but for this tool, direct R.layout/id might be more straightforward if full
// build environment isn't simulated. Assuming R references will resolve.


class MainActivity : ComponentActivity() {

    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var previewView: PreviewView
    private var mediaPlayer: MediaPlayer? = null

    private lateinit var cameraExecutor: ExecutorService // Added
    private var imageAnalysis: ImageAnalysis? = null // Added
    // private var generativeModel: com.google.ai.client.generativeai.GenerativeModel? = null // Placeholder - Now fully commented
    // private var generativeModel: GenerativeModel? = null // Fully commented out

    private var isMeditating = false
    private var meditationTimeMillis: Long = 5 * 60 * 1000 // Default 5 minutes
    private var countDownTimer: CountDownTimer? = null

    private lateinit var buttonStart: ImageButton
    private lateinit var textViewTime: TextView
    private lateinit var textViewGuide: TextView // Added
    private lateinit var buttonSettings: ImageButton // Added
    private lateinit var spinnerLanguage: Spinner // Added

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

        cameraExecutor = Executors.newSingleThreadExecutor() // Added

        // Ensure this matches your PreviewView's ID in the XML, e.g., from a previous step
        previewView = findViewById(R.id.previewView)

        Log.d("GeminiAPI", "API Key: ${BuildConfig.GEMINI_API_KEY}")
        initializeGeminiClient() // Called here

        buttonStart = findViewById(R.id.buttonStart)
        textViewTime = findViewById(R.id.textViewTime)
        textViewGuide = findViewById(R.id.textViewGuide) // Added
        buttonSettings = findViewById(R.id.buttonSettings) // Added

        updateTimerDisplay(meditationTimeMillis) // Show initial time
        // updateGuideText("명상을 시작하려면 시작 버튼을 누르세요.") // Will be set after spinner

        spinnerLanguage = findViewById(R.id.spinnerLanguage) // Added

        // Set spinner to current language
        val currentLang = Locale.getDefault().language
        val langCodes = resources.getStringArray(R.array.language_codes)
        val currentLangIndex = langCodes.indexOf(currentLang).takeIf { it >= 0 } ?: 0
        spinnerLanguage.setSelection(currentLangIndex, false) // false to prevent listener firing on init

        spinnerLanguage.onItemSelectedListener = object : AdapterView.OnItemSelectedListener { // Added
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedLangCode = langCodes[position]
                if (Locale.getDefault().language != selectedLangCode) {
                    setLocale(selectedLangCode)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        updateGuideText(getString(R.string.guide_initial_prompt)) // Moved here and uses getString

        buttonStart.setOnClickListener {
            if (isMeditating) {
                stopMeditation()
            } else {
                startMeditation()
            }
        }

        buttonSettings.setOnClickListener { // Added
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
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
            bindCameraUseCases(cameraProvider) // Use the new method name
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) { // Renamed from bindPreview
        val preview: Preview = Preview.Builder().build()
        val cameraSelector: CameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
            .build()

        preview.setSurfaceProvider(previewView.surfaceProvider)

        // Setup ImageAnalysis
        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // Optionally set target resolution if needed: .setTargetResolution(Size(640, 480))
            .build()

        // Direct analyzer implementation
        imageAnalysis?.setAnalyzer(cameraExecutor, ImageAnalysis.Analyzer { imageProxy ->
            try {
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                Log.d("CameraXApp", "ImageAnalysis: Frame received. Rotation: $rotationDegrees")

                val bitmap = imageProxyToBitmap(imageProxy)
                if (bitmap != null) {
                    Log.d("CameraXApp", "Bitmap created: ${bitmap.width}x${bitmap.height}")
                    // TODO: Pass this bitmap to Gemini API
                    // bitmap.recycle() // Consider lifecycle if passing bitmap around
                } else {
                    Log.e("CameraXApp", "Could not convert ImageProxy to Bitmap.")
                }
            } catch (e: Exception) {
                Log.e("CameraXApp", "Error during image analysis or conversion", e)
            } finally {
                imageProxy.close() // Ensure this is always called.
            }
        })

        try {
            cameraProvider.unbindAll() // Unbind use cases before rebinding
            // Bind use cases to camera
            if (imageAnalysis != null) {
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } else {
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            }
            Log.d("CameraXApp", "Camera use cases bound.")
        } catch (e: Exception) {
            Log.e("CameraXApp", "Use case binding failed", e)
            Toast.makeText(this, "Failed to start camera features: ${e.message}", Toast.LENGTH_SHORT).show()
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
        updateGuideText(getString(R.string.guide_on_start)) // Uses getString
        buttonStart.setImageResource(R.drawable.ic_stop_placeholder) // Using placeholder

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
            updateGuideText(getString(R.string.guide_initial_prompt)) // Uses getString
            return
        }

        val wasMeditating = isMeditating // Capture state before changing
        isMeditating = false
        countDownTimer?.cancel()
        countDownTimer = null

        if (wasMeditating) { // Only if it was actually meditating
            updateGuideText(getString(R.string.guide_on_finish)) // Uses getString
            playSound(R.raw.singing_bowl_end)
        }

        updateTimerDisplay(meditationTimeMillis) // Reset timer text to full duration
        buttonStart.setImageResource(R.drawable.ic_play_arrow_placeholder) // Using placeholder

        if (wasMeditating) {
            Handler(Looper.getMainLooper()).postDelayed({
                if(!isMeditating) { // Check again, in case user restarted meditation quickly
                    updateGuideText(getString(R.string.guide_initial_prompt)) // Uses getString
                }
            }, 2000) // 2-second delay
        } else {
             // If not previously meditating (e.g. timer finished and this is a cleanup call from onFinish,
             // or was stopped before truly starting by clicking button again quickly), ensure default text is set.
             updateGuideText(getString(R.string.guide_initial_prompt)) // Uses getString
        }
        Log.d("MeditationApp", "Meditation stopped. Guide text updated.")
    }

    private fun updateTimerDisplay(millis: Long) {
        val minutes = (millis / 1000) / 60
        val seconds = (millis / 1000) % 60
        // Use string resource for time label
        textViewTime.text = getString(R.string.meditation_time_label, String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds))
    }

    private fun updateGuideText(text: String) {
        textViewGuide.text = text
        Log.d("MeditationApp", "Guide text updated: $text")
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun initializeGeminiClient() { // Added
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey == "YOUR_API_KEY_HERE" || apiKey == "MISSING_API_KEY" || apiKey.isBlank()) {
            Toast.makeText(this, "Gemini API Key not set. Please set it in local.properties.", Toast.LENGTH_LONG).show()
            Log.e("GeminiAPI", "API Key is missing or a placeholder.")
            return
        }

        try {
            // Replace with actual Gemini SDK initialization call and model name
            // generativeModel = GenerativeModel(
            //    modelName = "gemini-pro-vision", // Or your desired vision model
            //    apiKey = apiKey
            // )
            Log.i("GeminiAPI", "Gemini AI Client would be initialized here if SDK was fully available.")
            Toast.makeText(this, "Gemini Client ready (simulated)", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e("GeminiAPI", "Error initializing Gemini AI Client", e)
            Toast.makeText(this, "Error initializing Gemini: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? { // Added
        if (image.format != ImageFormat.YUV_420_888) {
            Log.e("ImageConverter", "Unsupported image format: ${image.format}")
            return null
        }

        val yBuffer = image.planes[0].buffer // Y
        val uBuffer = image.planes[1].buffer // U
        val vBuffer = image.planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        if (!yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)) {
            Log.e("ImageConverter", "YuvImage compression failed")
            return null
        }
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun setLocale(languageCode: String) {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration()
        config.setLocale(locale)
        baseContext.resources.updateConfiguration(config, baseContext.resources.displayMetrics)

        recreate()
    }
}
