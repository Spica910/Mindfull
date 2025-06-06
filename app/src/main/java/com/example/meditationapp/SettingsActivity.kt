package com.example.meditationapp

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        val textViewSystemPrompt: TextView = findViewById(R.id.textViewSystemPrompt)

        val systemPrompt = "You are a mindful meditation guide.\n" +
                           "Your goal is to help the user maintain proper posture and focus on their breath.\n" +
                           "Observe the user through the camera feed.\n" +
                           "If you notice the user's posture is slumping, gently remind them to sit up straight.\n" +
                           "If you notice the user's breathing is shallow or erratic, guide them to take deep, slow breaths.\n" +
                           "If the user seems distracted or restless, offer words of encouragement to bring their focus back to their breath.\n" +
                           "Provide guidance in a calm, soothing, and positive tone.\n" +
                           "Keep your instructions concise and easy to follow.\n" +
                           "The user is doing a mindfulness exercise.\n" +
                           "Your persona is a calm and experienced meditation coach."

        textViewSystemPrompt.text = systemPrompt
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed() // Changed from onBackPressed() for compatibility
        return true
    }
}
