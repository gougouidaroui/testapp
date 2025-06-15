package com.example.testapp

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.abs

class GyroscopeManager(private val context: Context) : SensorEventListener {

    interface GyroscopeListener {
        fun onGyroscopeMotion(gestureType: GestureType, intensity: Float)
    }

    private var sensorManager: SensorManager? = null
    private var gyroscopeSensor: Sensor? = null
    private var listener: GyroscopeListener? = null
    private var isListening = false

    // Sensitivity thresholds (rad/s)
    private var sensitivity = GyroSensitivity()

    // Debouncing to prevent rapid fire events
    private var lastMotionTime = 0L
    private val motionCooldownMs = 1000L // 1 second cooldown between gestures

    // Motion detection parameters
    private val motionSustainDurationMs = 200L // Motion must be sustained for this duration
    private var motionStartTime = 0L
    private var lastGestureType: GestureType? = null
    private var isInMotion = false

    init {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        gyroscopeSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (gyroscopeSensor == null) {
            Log.w("GyroscopeManager", "Gyroscope sensor not available on this device")
        }
    }

    fun setListener(listener: GyroscopeListener) {
        this.listener = listener
    }

    fun setSensitivity(sensitivity: GyroSensitivity) {
        this.sensitivity = sensitivity
        Log.d("GyroscopeManager", "Updated sensitivity: X=${sensitivity.xSensitivity}, Y=${sensitivity.ySensitivity}, Z=${sensitivity.zSensitivity}")
    }

    fun startListening(): Boolean {
        if (gyroscopeSensor == null) {
            Log.e("GyroscopeManager", "Cannot start listening: gyroscope not available")
            return false
        }

        if (isListening) {
            Log.d("GyroscopeManager", "Already listening to gyroscope")
            return true
        }

        val success = sensorManager?.registerListener(
            this,
            gyroscopeSensor,
            SensorManager.SENSOR_DELAY_GAME // Good balance between responsiveness and battery
        ) == true

        if (success) {
            isListening = true
            Log.d("GyroscopeManager", "Started listening to gyroscope")
        } else {
            Log.e("GyroscopeManager", "Failed to register gyroscope listener")
        }

        return success
    }

    fun stopListening() {
        if (isListening) {
            sensorManager?.unregisterListener(this)
            isListening = false
            isInMotion = false
            lastGestureType = null
            Log.d("GyroscopeManager", "Stopped listening to gyroscope")
        }
    }

    fun isGyroscopeAvailable(): Boolean {
        return gyroscopeSensor != null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return

        val currentTime = System.currentTimeMillis()

        // Debouncing: ignore events too close to the last motion
        if (currentTime - lastMotionTime < motionCooldownMs) {
            return
        }

        // Get rotation rates (rad/s) for each axis
        val rotationX = event.values[0]  // Rotation around X-axis
        val rotationY = event.values[1]  // Rotation around Y-axis
        val rotationZ = event.values[2]  // Rotation around Z-axis

        // Determine which gesture is being performed
        val detectedGesture = detectGesture(rotationX, rotationY, rotationZ)

        if (detectedGesture != null) {
            if (!isInMotion || lastGestureType != detectedGesture) {
                // Start of new motion or different motion
                motionStartTime = currentTime
                lastGestureType = detectedGesture
                isInMotion = true
            } else if (currentTime - motionStartTime >= motionSustainDurationMs) {
                // Motion has been sustained long enough, trigger the gesture
                val intensity = getIntensity(rotationX, rotationY, rotationZ, detectedGesture)

                Log.d("GyroscopeManager", "Gyroscope gesture detected: $detectedGesture (intensity: $intensity)")
                listener?.onGyroscopeMotion(detectedGesture, intensity)

                lastMotionTime = currentTime
                isInMotion = false
                lastGestureType = null
            }
        } else {
            // No significant motion detected
            if (isInMotion && currentTime - motionStartTime > motionSustainDurationMs) {
                // Reset motion state if no motion for a while
                isInMotion = false
                lastGestureType = null
            }
        }
    }

    private fun detectGesture(rotationX: Float, rotationY: Float, rotationZ: Float): GestureType? {
        // Check X-axis rotation
        if (abs(rotationX) > sensitivity.xSensitivity) {
            return if (rotationX > 0) GestureType.GYRO_X_POSITIVE else GestureType.GYRO_X_NEGATIVE
        }

        // Check Y-axis rotation
        if (abs(rotationY) > sensitivity.ySensitivity) {
            return if (rotationY > 0) GestureType.GYRO_Y_POSITIVE else GestureType.GYRO_Y_NEGATIVE
        }

        // Check Z-axis rotation
        if (abs(rotationZ) > sensitivity.zSensitivity) {
            return if (rotationZ > 0) GestureType.GYRO_Z_POSITIVE else GestureType.GYRO_Z_NEGATIVE
        }

        return null
    }

    private fun getIntensity(rotationX: Float, rotationY: Float, rotationZ: Float, gesture: GestureType): Float {
        return when (gesture) {
            GestureType.GYRO_X_POSITIVE, GestureType.GYRO_X_NEGATIVE -> abs(rotationX)
            GestureType.GYRO_Y_POSITIVE, GestureType.GYRO_Y_NEGATIVE -> abs(rotationY)
            GestureType.GYRO_Z_POSITIVE, GestureType.GYRO_Z_NEGATIVE -> abs(rotationZ)
            else -> 0f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for gyroscope
    }
}
