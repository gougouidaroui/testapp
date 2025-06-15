package com.example.testapp

import android.content.Intent
import android.graphics.drawable.Drawable

data class AppShortcutInfo(
    val id: String,
    val packageName: String,
    val label: String,
    val longLabel: String? = null,
    val icon: Drawable? = null,
    val intent: Intent,
    val type: ShortcutType
)

enum class ShortcutType {
    APP_SHORTCUT,    // App shortcuts (API 25+)
    LEGACY_SHORTCUT, // Legacy shortcuts
    DIRECT_LAUNCH    // Direct app launch
}

enum class GestureType {
    DOUBLE_TAP,
    DOUBLE_SWIPE_UP,

    // Gyroscope gestures
    GYRO_X_POSITIVE,    // Rotate around X-axis (positive direction)
    GYRO_X_NEGATIVE,    // Rotate around X-axis (negative direction)
    GYRO_Y_POSITIVE,    // Rotate around Y-axis (positive direction)
    GYRO_Y_NEGATIVE,    // Rotate around Y-axis (negative direction)
    GYRO_Z_POSITIVE,    // Rotate around Z-axis (positive direction)
    GYRO_Z_NEGATIVE     // Rotate around Z-axis (negative direction)
}

data class GyroSensitivity(
    val xSensitivity: Float = 2.0f,  // rad/s threshold for X-axis
    val ySensitivity: Float = 2.0f,  // rad/s threshold for Y-axis
    val zSensitivity: Float = 2.0f   // rad/s threshold for Z-axis
)
