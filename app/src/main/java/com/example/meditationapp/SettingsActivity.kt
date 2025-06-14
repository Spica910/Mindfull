package com.example.meditationapp

import android.os.Bundle
import android.widget.AdapterView
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        val prefs = getSharedPreferences("MeditationPrefs", MODE_PRIVATE)
        val spinnerDuration: Spinner = findViewById(R.id.spinnerDuration)
        val spinnerMethod: Spinner = findViewById(R.id.spinnerMethod)

        val durations = resources.getStringArray(R.array.duration_options).map { it.toInt() }
        val savedDuration = prefs.getInt("meditation_duration", 30)
        val durationIndex = durations.indexOf(savedDuration).takeIf { it >= 0 } ?: 4
        spinnerDuration.setSelection(durationIndex)

        val methods = resources.getStringArray(R.array.method_options)
        val savedMethod = prefs.getString("meditation_method", "guided") ?: "guided"
        val methodIndex = methods.indexOfFirst { it.equals(savedMethod, ignoreCase = true) }.takeIf { it >= 0 } ?: 0
        spinnerMethod.setSelection(methodIndex)

        spinnerDuration.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putInt("meditation_duration", durations[position]).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        spinnerMethod.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString("meditation_method", methods[position].lowercase()).apply()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
