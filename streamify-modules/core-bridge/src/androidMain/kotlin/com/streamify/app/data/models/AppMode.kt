package com.streamify.app.data.models

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AppMode {
    STREAMIFY,
    SPOTIFY,
    YOUTUBE_MUSIC;

    companion object {
        private const val PREFS_NAME = "streamify_app_mode"
        private const val KEY_SELECTED_MODE = "selected_mode"
        private const val KEY_IS_ONBOARDED = "is_onboarded"

        private val _currentMode = MutableStateFlow(STREAMIFY)
        val currentMode: StateFlow<AppMode> = _currentMode.asStateFlow()

        fun initialize(context: Context): AppMode {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val modeName = prefs.getString(KEY_SELECTED_MODE, STREAMIFY.name) ?: STREAMIFY.name
            val mode = try {
                valueOf(modeName)
            } catch (e: Exception) {
                STREAMIFY
            }
            _currentMode.value = mode
            return mode
        }

        fun getSavedMode(context: Context): AppMode {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val modeName = prefs.getString(KEY_SELECTED_MODE, STREAMIFY.name) ?: STREAMIFY.name
            return try {
                valueOf(modeName)
            } catch (e: Exception) {
                STREAMIFY
            }
        }

        fun isOnboarded(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_IS_ONBOARDED, false)
        }

        fun setAppMode(context: Context, mode: AppMode) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_SELECTED_MODE, mode.name)
                .putBoolean(KEY_IS_ONBOARDED, true)
                .apply()
            _currentMode.value = mode
        }
    }
}
