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
import com.google.ai.client.generativeai.GenerativeModel // Added for Gemini SDK
import com.google.ai.client.generativeai.type.content // For the content { } builder
import com.google.ai.client.generativeai.type.InvalidStateException // For error handling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import android.os.PowerManager // Added for WakeLock
import android.speech.tts.TextToSpeech // Added for TTS
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.example.meditationapp.BuildConfig // Import BuildConfig
import java.util.concurrent.ExecutorService // Added
import java.util.concurrent.Executors // Added
// It's good practice to use the specific R class from your package if available,
// but for this tool, direct R.layout/id might be more straightforward if full
// build environment isn't simulated. Assuming R references will resolve.


class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener { // Added TTS Listener

    private var tts: TextToSpeech? = null // Added for TTS
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    private lateinit var previewView: PreviewView
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null // Added for WakeLock

    private lateinit var cameraExecutor: ExecutorService // Added
    private var imageAnalysis: ImageAnalysis? = null // Added
    private var generativeModel: GenerativeModel? = null // For Gemini AI Client

    private var isMeditating = false
    private var meditationTimeMillis: Long = 30 * 60 * 1000 // Default 30 minutes
    private var countDownTimer: CountDownTimer? = null

    // Data structures and variables for timed meditation segments
    private data class MeditationSegment(
        val stringResId: Int,
        val audioResId: Int,
        val startTimeMillis: Long // Time from the beginning of meditation
    )

    private val meditationSegments = listOf(
        MeditationSegment(R.string.guide_kor_0_3_min, R.raw.meditation_guide_kor_0_3_min, 0L),
        MeditationSegment(R.string.guide_kor_3_7_min, R.raw.meditation_guide_kor_3_7_min, 3 * 60 * 1000L),
        MeditationSegment(R.string.guide_kor_7_12_min, R.raw.meditation_guide_kor_7_12_min, 7 * 60 * 1000L),
        MeditationSegment(R.string.guide_kor_12_17_min, R.raw.meditation_guide_kor_12_17_min, 12 * 60 * 1000L),
        MeditationSegment(R.string.guide_kor_17_23_min, R.raw.meditation_guide_kor_17_23_min, 17 * 60 * 1000L),
        MeditationSegment(R.string.guide_kor_23_27_min, R.raw.meditation_guide_kor_23_27_min, 23 * 60 * 1000L),
        MeditationSegment(R.string.guide_kor_27_30_min, R.raw.meditation_guide_kor_27_30_min, 27 * 60 * 1000L)
    )

    private var currentSegmentIndex = -1 // Initialized to -1, set to 0 when meditation starts
    private var initialAudioHandler: Handler? = null
    private var initialAudioRunnable: Runnable? = null

    @Volatile // Ensure visibility across threads, though System.currentTimeMillis is usually safe
    private var lastFrameSentToGeminiMs: Long = 0L
    private val geminiFrameProcessingIntervalMs: Long = 5000L // Process one frame every 5 seconds

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
        if (tts != null && !(tts?.isSpeaking ?: false) && Locale.getDefault().language == "ko") {
            Handler(Looper.getMainLooper()).postDelayed({ // Delay slightly to ensure UI is ready
                speak(getString(R.string.guide_initial_prompt))
            }, 500) // 0.5 second delay
        }

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

        tts = TextToSpeech(this, this) // Initialize TTS
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            Log.i("TTS", "TTS Engine Initialized successfully.")
            // Attempt to set Korean language
            val langResult = tts?.setLanguage(Locale.KOREAN)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "Korean language is not supported or missing data for TTS. Trying default.")
                // Fallback to default language
                val defaultLangResult = tts?.setLanguage(Locale.getDefault())
                if (defaultLangResult == TextToSpeech.LANG_MISSING_DATA || defaultLangResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e("TTS", "Default language is also not supported or missing data for TTS.")
                    Toast.makeText(this, "TTS language not supported.", Toast.LENGTH_SHORT).show()
                } else {
                    Log.i("TTS", "TTS language set to default system language.")
                    // Set speech rate and pitch for default language
                    tts?.setSpeechRate(0.8f) // Slower speed
                    tts?.setPitch(1.0f)    // Normal pitch
                    Log.i("TTS", "Set speech rate to 0.8, pitch to 1.0 for default language.")
                }
            } else {
                Log.i("TTS", "TTS language set to Korean.")
                // Set speech rate and pitch for Korean
                tts?.setSpeechRate(0.8f) // Slower speed
                tts?.setPitch(1.0f)    // Normal pitch
                Log.i("TTS", "Set speech rate to 0.8, pitch to 1.0 for Korean.")
            }
        } else {
            Log.e("TTS", "TTS initialization failed with status: $status")
            Toast.makeText(this, "TTS initialization failed.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun speak(text: String) { // Added for TTS
        if (tts?.isSpeaking == true) {
            tts?.stop() // Stop any current speech before starting new one
        }
        // QUEUE_FLUSH will interrupt current speech, QUEUE_ADD will add to the end.
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        Log.d("TTS", "Attempting to speak: $text")
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
                // --- Start of Throttling Logic ---
                val currentTimeMs = System.currentTimeMillis()
                if (currentTimeMs - lastFrameSentToGeminiMs < geminiFrameProcessingIntervalMs) {
                    imageProxy.close() // Must close the proxy to allow next frame
                    return@Analyzer   // Skip this frame
                }
                // --- End of Throttling Logic ---

                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                // Log.d("CameraXApp", "ImageAnalysis: Frame received. Rotation: $rotationDegrees") // Keep for debugging if needed

                val bitmap = imageProxyToBitmap(imageProxy)
                if (bitmap != null) {
                    Log.d("CameraXApp", "Bitmap created: ${bitmap.width}x${bitmap.height}. Throttling check passed, preparing for Gemini.")

                    // Update last sent time *before* launching the coroutine to avoid race conditions
                    // if multiple frames arrive quickly and pass the initial check.
                    lastFrameSentToGeminiMs = currentTimeMs

                    // Check if generativeModel is initialized
                    if (generativeModel == null) {
                        Log.w("GeminiAPI", "Gemini model not initialized. Skipping image submission.")
                        // imageProxy.close() is handled in finally
                        return@Analyzer
                    }

                    // Launch a coroutine for the network request
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val prompt = "Describe what you see in this image." // Simple initial prompt

                            // Construct the content for Gemini
                            val inputContent = content {
                                image(bitmap)
                                text(prompt)
                            }

                            Log.d("GeminiAPI", "Sending image and prompt to Gemini API...")
                            val response = generativeModel!!.generateContent(inputContent) // Use !! as we checked for null

                            // Log the response (next step will handle displaying it)
                            Log.i("GeminiAPI", "Gemini API Response: ${response.text}")

                            // Recycle the bitmap after it has been used by the API if it's not needed anymore
                            // However, be cautious if the bitmap is still needed elsewhere or if generateContent is truly async
                            // For now, let's assume generateContent processes it and we can recycle.
                            // If issues occur, this recycle might need to be moved or removed.
                            // bitmap.recycle() // Potentially recycle, but test carefully. For now, let's omit to be safe with async calls.

                        } catch (e: InvalidStateException) {
                            Log.e("GeminiAPI", "InvalidStateException (e.g. API key issue after init): ${e.localizedMessage}", e)
                            // Potentially show a Toast to the user on the main thread
                            // launch(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Gemini API Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
                        } catch (e: Exception) {
                            Log.e("GeminiAPI", "Error calling Gemini API: ${e.localizedMessage}", e)
                            // Potentially show a Toast to the user on the main thread
                            // launch(Dispatchers.Main) { Toast.makeText(this@MainActivity, "Gemini API Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
                        }
                    }
                } else {
                    Log.e("CameraXApp", "Could not convert ImageProxy to Bitmap.")
                }
            } catch (e: Exception) {
                Log.e("CameraXApp", "Error during image analysis or Gemini call setup", e)
            } finally {
                imageProxy.close() // Crucial: Ensure ImageProxy is always closed
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

    private fun tryPlaySoundAndReturnSuccess(soundResourceId: Int): Boolean {
        Log.d("MediaPlayerDebug", "tryPlaySoundAndReturnSuccess called with soundResourceId: $soundResourceId (0x${Integer.toHexString(soundResourceId)})")
        try {
            if (mediaPlayer != null) {
                Log.d("MediaPlayerDebug", "Releasing existing mediaPlayer instance.")
                mediaPlayer?.release()
                mediaPlayer = null
            } else {
                Log.d("MediaPlayerDebug", "No existing mediaPlayer instance to release.")
            }

            Log.d("MediaPlayerDebug", "Attempting to create MediaPlayer for resource ID: $soundResourceId")
            mediaPlayer = MediaPlayer.create(this, soundResourceId)

            if (mediaPlayer == null) {
                val resourceName = try { resources.getResourceEntryName(soundResourceId) } catch (e: Exception) { "unknown" }
                Log.e("MediaPlayerDebug", "MediaPlayer.create returned null for resource ID: $soundResourceId (Name: $resourceName). Sound will not play.")
                Toast.makeText(this, "Error: Cannot create media for sound: $resourceName", Toast.LENGTH_LONG).show()
                return false // Indicate failure
            }
            val resourceName = try { resources.getResourceEntryName(soundResourceId) } catch (e: Exception) { "unknown" }
            Log.d("MediaPlayerDebug", "MediaPlayer created successfully for $soundResourceId (Name: $resourceName).")

            mediaPlayer?.setOnErrorListener { mp, what, extra ->
                Log.e("MediaPlayerDebug", "MediaPlayer error occurred: what: $what, extra: $extra for resource ID: $soundResourceId")
                mp.release()
                mediaPlayer = null
                true // Error handled
            }

            mediaPlayer?.setOnCompletionListener { mp ->
                Log.d("MediaPlayerDebug", "Sound playback completed for resource ID: $soundResourceId. Releasing MediaPlayer.")
                mp.release()
                mediaPlayer = null
            }

            Log.d("MediaPlayerDebug", "Attempting to start media player.")
            mediaPlayer?.start()
            Log.d("MediaPlayerDebug", "Media player started for resource ID: $soundResourceId.")
            return true // Indicate success

        } catch (e: Exception) {
            Log.e("MediaPlayerDebug", "Exception in tryPlaySoundAndReturnSuccess for resource ID: $soundResourceId", e)
            Toast.makeText(this, "Error playing sound: ${e.message}", Toast.LENGTH_SHORT).show()
            if (mediaPlayer != null) {
                mediaPlayer?.release()
                mediaPlayer = null
            }
            return false // Indicate failure
        }
    }

    // Example usage (to be called later, e.g., on button click):
    // To play start sound:
    // tryPlaySoundAndReturnSuccess(R.raw.singing_bowl_start)
    // To play end sound:
    // tryPlaySoundAndReturnSuccess(R.raw.singing_bowl_end)

    private fun startMeditation() {
        if (isMeditating) return

        isMeditating = true
        buttonStart.setImageResource(R.drawable.ic_stop_placeholder)

        currentSegmentIndex = 0 // Start with the first segment
        tryPlaySoundAndReturnSuccess(R.raw.singing_bowl_start) // Play start sound

        if (meditationSegments.isNotEmpty()) {
            updateGuideText(getString(meditationSegments[0].stringResId))

            initialAudioHandler?.removeCallbacks(initialAudioRunnable!!) // Cancel previous, if any
            initialAudioHandler = Handler(Looper.getMainLooper())
            initialAudioRunnable = Runnable {
                if (isMeditating && currentSegmentIndex == 0) {
                    if (meditationSegments.isNotEmpty()) { // Ensure segments exist
                        val audioSuccess = tryPlaySoundAndReturnSuccess(meditationSegments[0].audioResId)
                        if (!audioSuccess) {
                            // Fallback to TTS if audio failed for the first segment
                            speak(getString(meditationSegments[0].stringResId))
                            Log.i("MeditationApp", "Audio fallback in initialAudioRunnable: Spoke segment 0")
                        }
                    }
                }
            }
            initialAudioHandler?.postDelayed(initialAudioRunnable!!, 1500) // 1.5s delay
        } else {
            Log.w("MeditationApp", "Meditation segments list is empty.")
            // Potentially play a default sound or show a default message if segments are missing
        }

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(meditationTimeMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                updateTimerDisplay(millisUntilFinished)
                val elapsedTime = meditationTimeMillis - millisUntilFinished

                // Check if it's time to switch to the NEXT segment
                if (currentSegmentIndex + 1 < meditationSegments.size) {
                    val nextSegment = meditationSegments[currentSegmentIndex + 1]
                    if (elapsedTime >= nextSegment.startTimeMillis) {
                        currentSegmentIndex++ // Move to the next segment
                        updateGuideText(getString(nextSegment.stringResId))
                        val audioSuccess = tryPlaySoundAndReturnSuccess(nextSegment.audioResId)
                        if (!audioSuccess) {
                            // Fallback to TTS if audio failed for this segment
                            speak(getString(nextSegment.stringResId))
                            Log.i("MeditationApp", "Audio fallback in onTick: Spoke segment ${getString(nextSegment.stringResId)}")
                        }
                        Log.i("MeditationApp", "Transitioning to segment $currentSegmentIndex at $elapsedTime ms: ${getString(nextSegment.stringResId)}")
                    }
                }
            }

            override fun onFinish() {
                stopMeditation()
                Toast.makeText(this@MainActivity, getString(R.string.meditation_finished_toast), Toast.LENGTH_SHORT).show()
            }
        }.start()

        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "MeditationApp::MeditationWakeLock")
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(meditationTimeMillis + 30000L) // Ensure it's a Long
            Log.d("MeditationApp", "WakeLock acquired.")
        }
        Log.d("MeditationApp", "Meditation started with timed segments.")
    }

    private fun stopMeditation() {
        initialAudioHandler?.removeCallbacks(initialAudioRunnable!!)
        initialAudioHandler = null
        initialAudioRunnable = null

        val wasMeditating = isMeditating
        isMeditating = false
        countDownTimer?.cancel()
        countDownTimer = null

        if (tts?.isSpeaking == true) {
            tts?.stop()
        }

        // Explicitly stop and release current MediaPlayer before playing end bowl sound
        // mediaPlayer?.stop() // Temporarily commented out
        // mediaPlayer?.release() // Temporarily commented out
        // mediaPlayer = null // Temporarily commented out

        if (wasMeditating) {
            tryPlaySoundAndReturnSuccess(R.raw.singing_bowl_end)
            if (meditationSegments.isNotEmpty()) {
                // Show text of the last segment that was playing or should have played
                // If currentSegmentIndex is valid, use it, otherwise default to last.
                val lastPlayedSegmentStringId = if (currentSegmentIndex != -1 && currentSegmentIndex < meditationSegments.size) {
                    meditationSegments[currentSegmentIndex].stringResId
                } else {
                    meditationSegments.last().stringResId
                }
                 updateGuideText(getString(lastPlayedSegmentStringId))
            }
        } else {
            updateGuideText(getString(R.string.guide_initial_prompt))
            if (tts != null && !(tts?.isSpeaking ?: false) && Locale.getDefault().language == "ko") {
                speak(getString(R.string.guide_initial_prompt))
            }
        }

        updateTimerDisplay(meditationTimeMillis)
        buttonStart.setImageResource(R.drawable.ic_play_arrow_placeholder)

        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Log.d("MeditationApp", "WakeLock released.")
        }
        Log.d("MeditationApp", "Meditation stopped.")
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
        // Release TTS resources
        if (tts != null) { // Added for TTS
            tts?.stop()
            tts?.shutdown()
            Log.d("TTS", "TTS engine shut down.")
        }
        super.onDestroy()
        cameraExecutor.shutdown()
        // Release WakeLock if held
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLock = null // Clean up
            Log.d("MeditationApp", "WakeLock released in onDestroy.")
        }
    }

    private fun initializeGeminiClient() { // Added
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey == "YOUR_API_KEY_HERE" || apiKey == "MISSING_API_KEY" || apiKey.isBlank()) {
            Toast.makeText(this, "Gemini API Key not set. Please set it in local.properties.", Toast.LENGTH_LONG).show()
            Log.e("GeminiAPI", "API Key is missing or a placeholder.")
            return
        }

        try {
            // Uncommented and configured:
            generativeModel = GenerativeModel(
                modelName = "gemini-pro-vision",
                apiKey = apiKey
                // Optionally, add generationConfig, safetySettings etc. if needed later
            )
            Log.i("GeminiAPI", "Gemini AI Client Initialized successfully with gemini-pro-vision.")
            Toast.makeText(this, "Gemini Client Initialized.", Toast.LENGTH_SHORT).show()

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
        if (tts?.isSpeaking == true) { // Added for TTS
            tts?.stop()
            Log.d("TTS", "TTS stopped due to locale change.")
        }
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration()
        config.setLocale(locale)
        baseContext.resources.updateConfiguration(config, baseContext.resources.displayMetrics)

        recreate()
    }
}
