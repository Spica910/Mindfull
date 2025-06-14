package com.example.meditationapp

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
// import com.google.ai.client.generativeai.GenerativeModel // Placeholder for actual Gemini SDK
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.widget.Spinner
import android.widget.AdapterView
import android.content.res.Configuration
// ArrayAdapter might be needed if not using android:entries, but not for this example
import java.util.Locale
import androidx.activity.ComponentActivity
import android.media.MediaPlayer
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import com.example.meditationapp.BuildConfig
// It's good practice to use the specific R class from your package if available,
// but for this tool, direct R.layout/id might be more straightforward if full
// build environment isn't simulated. Assuming R references will resolve.


class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener { // Added TTS Listener

    private var tts: TextToSpeech? = null // Added for TTS
    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null // Added for WakeLock

    // private var generativeModel: com.google.ai.client.generativeai.GenerativeModel? = null // Placeholder - Now fully commented
    // private var generativeModel: GenerativeModel? = null // Fully commented out

    private var isMeditating = false
    private var meditationTimeMillis: Long = 30 * 60 * 1000 // Default 30 minutes
    private var meditationMethod: String = "guided" // guided or silent
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

    private lateinit var buttonStart: ImageButton
    private lateinit var textViewTime: TextView
    private lateinit var textViewGuide: TextView // Added
    private lateinit var buttonSettings: ImageButton // Added
    private lateinit var spinnerLanguage: Spinner // Added


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ensure this matches your layout file name, e.g., from a previous step
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("MeditationPrefs", MODE_PRIVATE)
        meditationTimeMillis = prefs.getInt("meditation_duration", 30) * 60 * 1000L
        meditationMethod = prefs.getString("meditation_method", "guided") ?: "guided"

        Log.d("GeminiAPI", "API Key: ${BuildConfig.GEMINI_API_KEY}")
        initializeGeminiClient() // Called here

        buttonStart = findViewById(R.id.buttonStart)
        textViewTime = findViewById(R.id.textViewTime)
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

        }

        tts = TextToSpeech(this, this) // Initialize TTS
    }

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
                    tts?.setSpeechRate(0.7f) // Calmer speed
                    tts?.setPitch(0.9f)    // Slightly lower pitch
                    Log.i("TTS", "Set speech rate to 0.7, pitch to 0.9 for default language.")
                }
            } else {
                Log.i("TTS", "TTS language set to Korean.")
                // Set speech rate and pitch for Korean
                tts?.setSpeechRate(0.7f) // Calmer speed
                tts?.setPitch(0.9f)    // Slightly lower pitch
                Log.i("TTS", "Set speech rate to 0.7, pitch to 0.9 for Korean.")
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

        if (meditationMethod == "guided" && meditationSegments.isNotEmpty()) {
            updateGuideText(getString(meditationSegments[0].stringResId))

            initialAudioHandler?.removeCallbacks(initialAudioRunnable!!)
            initialAudioHandler = Handler(Looper.getMainLooper())
            initialAudioRunnable = Runnable {
                if (isMeditating && currentSegmentIndex == 0) {
                    if (meditationSegments.isNotEmpty()) {
                        val audioSuccess = tryPlaySoundAndReturnSuccess(meditationSegments[0].audioResId)
                        if (!audioSuccess) {
                            speak(getString(meditationSegments[0].stringResId))
                            Log.i("MeditationApp", "Audio fallback in initialAudioRunnable: Spoke segment 0")
                        }
                    }
                }
            }
            initialAudioHandler?.postDelayed(initialAudioRunnable!!, 1500)
        } else if (meditationMethod == "silent") {
            updateGuideText(getString(R.string.silent_mode_text))
        }

        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(meditationTimeMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                updateTimerDisplay(millisUntilFinished)
                val elapsedTime = meditationTimeMillis - millisUntilFinished

                if (meditationMethod == "guided" && currentSegmentIndex + 1 < meditationSegments.size) {
                    val nextSegment = meditationSegments[currentSegmentIndex + 1]
                    if (elapsedTime >= nextSegment.startTimeMillis) {
                        currentSegmentIndex++
                        updateGuideText(getString(nextSegment.stringResId))
                        val audioSuccess = tryPlaySoundAndReturnSuccess(nextSegment.audioResId)
                        if (!audioSuccess) {
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
            if (meditationMethod == "guided" && meditationSegments.isNotEmpty()) {
                val lastPlayedSegmentStringId = if (currentSegmentIndex != -1 && currentSegmentIndex < meditationSegments.size) {
                    meditationSegments[currentSegmentIndex].stringResId
                } else {
                    meditationSegments.last().stringResId
                }
                updateGuideText(getString(lastPlayedSegmentStringId))
            } else {
                updateGuideText(getString(R.string.silent_mode_text))
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
