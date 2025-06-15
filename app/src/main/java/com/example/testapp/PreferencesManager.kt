package com.example.testapp

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("gesture_mappings", Context.MODE_PRIVATE)
    private val gyroPrefs: SharedPreferences = context.getSharedPreferences("gyro_settings", Context.MODE_PRIVATE)

    // Gesture mappings
    fun setGestureMapping(gesture: GestureType, shortcutId: String) {
        prefs.edit { putString(gesture.name, shortcutId) }
    }

    fun getGestureMapping(gesture: GestureType): String? {
        return prefs.getString(gesture.name, null)
    }

    fun clearGestureMapping(gesture: GestureType) {
        prefs.edit { remove(gesture.name) }
    }

    fun getAllMappings(): Map<GestureType, String?> {
        return GestureType.entries.associateWith { getGestureMapping(it) }
    }

    // Gyroscope sensitivity settings
    fun setGyroSensitivity(sensitivity: GyroSensitivity) {
        gyroPrefs.edit().apply {
            putFloat("x_sensitivity", sensitivity.xSensitivity)
            putFloat("y_sensitivity", sensitivity.ySensitivity)
            putFloat("z_sensitivity", sensitivity.zSensitivity)
            apply()
        }
    }

    fun getGyroSensitivity(): GyroSensitivity {
        return GyroSensitivity(
            xSensitivity = gyroPrefs.getFloat("x_sensitivity", 2.0f),
            ySensitivity = gyroPrefs.getFloat("y_sensitivity", 2.0f),
            zSensitivity = gyroPrefs.getFloat("z_sensitivity", 2.0f)
        )
    }

    // Individual axis sensitivity
    fun setAxisSensitivity(axis: String, sensitivity: Float) {
        gyroPrefs.edit { putFloat("${axis}_sensitivity", sensitivity) }
    }

    fun getAxisSensitivity(axis: String): Float {
        return gyroPrefs.getFloat("${axis}_sensitivity", 2.0f)
    }
}
