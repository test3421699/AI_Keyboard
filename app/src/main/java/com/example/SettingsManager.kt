package com.example

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "gemini_keyboard_prefs",
        Context.MODE_PRIVATE
    )

    companion object {
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        private const val KEY_SOUND_ENABLED = "sound_enabled"
    }

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value).apply()

    var isVibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, value).apply()

    var isSoundEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOUND_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SOUND_ENABLED, value).apply()

    /**
     * Gets the active API key. Falls back to BuildConfig.GEMINI_API_KEY
     * if the user hasn't configured a custom key yet, ensuring seamless
     * out-of-the-box prototyping!
     */
    fun getActiveApiKey(): String {
        val customKey = geminiApiKey.trim()
        if (customKey.isNotEmpty()) {
            return customKey
        }
        // Fallback to BuildConfig if configured
        val fallback = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }
        return if (fallback != "MY_GEMINI_API_KEY" && fallback.isNotEmpty()) fallback else ""
    }
}
