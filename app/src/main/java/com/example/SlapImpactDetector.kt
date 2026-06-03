package com.example

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

enum class SlapType {
    POCKET_SLAP, OPEN_IMPACT, TABLE_HIT
}

class SlapImpactDetector(
    private val context: Context,
    private var onSlapDetected: ((SlapType) -> Unit)? = null
) : SensorEventListener {

    enum class State {
        IDLE, CANDIDATE_IMPACT, COOLDOWN
    }

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var proximity: Sensor? = null
    private var gyroscope: Sensor? = null

    private var state = State.IDLE
    private var isNear = false
    private var gyroMagnitude = 0f

    // Configurable thresholds
    private var impactThreshold = 18f
    private var jerkThreshold = 12f
    private var gyroRejectThreshold = 8f

    private var gravity = FloatArray(3)
    private var hasGravity = false
    private var previousMagnitude = 0f

    private var candidateStartTime = 0L
    private var candidatePeakMagnitude = 0f
    private var candidatePeakJerk = 0f
    private var candidatePeakGyro = 0f

    private var lastSlapTimeMs = 0L
    private val COOLDOWN_MS = 800L
    private val CONFIRMATION_WINDOW_MS = 120L
    private var isSensorRegistered = false

    private var lastLogTimeMs = 0L

    fun isStarted(): Boolean {
        return isSensorRegistered
    }

    fun setCallback(callback: (SlapType) -> Unit) {
        this.onSlapDetected = callback
    }

    fun setSensitivity(level: String) {
        when (level) {
            "High" -> {
                impactThreshold = 12f
                jerkThreshold = 8f
                gyroRejectThreshold = 10f
            }
            "Low" -> {
                impactThreshold = 25f
                jerkThreshold = 20f
                gyroRejectThreshold = 5f
            }
            else -> { // Medium default
                impactThreshold = 18f
                jerkThreshold = 12f
                gyroRejectThreshold = 8f
            }
        }
    }

    fun start() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        proximity = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        gyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        state = State.IDLE
        isNear = false
        gyroMagnitude = 0f
        previousMagnitude = 0f
        hasGravity = false
        lastSlapTimeMs = 0L

        Log.d("SlapDetector", "SLAP_DETECTOR_STARTED")

        accelerometer?.let {
            val success = sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) == true
            if (success) {
                isSensorRegistered = true
                Log.d("SlapDetection", "POCKET_SLAP: sensor registered successfully")
            } else {
                Log.d("SlapDetection", "POCKET_SLAP: sensor registration failed")
            }
        } ?: Log.d("SlapDetection", "POCKET_SLAP: No accelerometer available")
        
        proximity?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        gyroscope?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        Log.d("SlapDetector", "SLAP_DETECTOR_STOPPED")
        sensorManager?.unregisterListener(this)
        isSensorRegistered = false
        state = State.IDLE
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val distance = event.values[0]
                val maxRange = event.sensor.maximumRange
                isNear = distance < maxRange && distance <= 5f
            }
            Sensor.TYPE_GYROSCOPE -> {
                val gx = event.values[0]
                val gy = event.values[1]
                val gz = event.values[2]
                gyroMagnitude = sqrt((gx * gx + gy * gy + gz * gz).toDouble()).toFloat()
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                handleAcceleration(event.values[0], event.values[1], event.values[2])
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val alpha = 0.8f
                if (!hasGravity) {
                    gravity[0] = event.values[0]
                    gravity[1] = event.values[1]
                    gravity[2] = event.values[2]
                    hasGravity = true
                } else {
                    gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                    gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                    gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]
                }

                val linearX = event.values[0] - gravity[0]
                val linearY = event.values[1] - gravity[1]
                val linearZ = event.values[2] - gravity[2]

                handleAcceleration(linearX, linearY, linearZ)
            }
        }
    }

    private fun handleAcceleration(x: Float, y: Float, z: Float) {
        val currentMagnitude = sqrt((x * x + y * y + z * z).toDouble()).toFloat()

        var jerk = 0f
        if (previousMagnitude != 0f) {
            jerk = abs(currentMagnitude - previousMagnitude)
        }
        previousMagnitude = currentMagnitude

        val now = SystemClock.elapsedRealtime()

        if (now - lastLogTimeMs > 1000) {
            // Live stats, throttled
            if (currentMagnitude > 5f || jerk > 5f) {
                Log.d("SlapDetector", "Live stats: mag=$currentMagnitude, jerk=$jerk, gyro=$gyroMagnitude, state=$state")
            }
            lastLogTimeMs = now
        }

        if (state == State.COOLDOWN) {
            if (now - lastSlapTimeMs >= COOLDOWN_MS) {
                Log.d("SlapDetector", "SLAP_DETECTOR: returned to IDLE")
                state = State.IDLE
            } else {
                return
            }
        }

        if (state == State.IDLE) {
            if (currentMagnitude > impactThreshold || jerk > jerkThreshold) {
                state = State.CANDIDATE_IMPACT
                candidateStartTime = now
                candidatePeakMagnitude = currentMagnitude
                candidatePeakJerk = jerk
                candidatePeakGyro = gyroMagnitude
                Log.d("SlapDetector", "SLAP_DETECTOR: candidate impact started")
            }
        }

        if (state == State.CANDIDATE_IMPACT) {
            if (currentMagnitude > candidatePeakMagnitude) candidatePeakMagnitude = currentMagnitude
            if (jerk > candidatePeakJerk) candidatePeakJerk = jerk
            if (gyroMagnitude > candidatePeakGyro) candidatePeakGyro = gyroMagnitude

            if (now - candidateStartTime >= CONFIRMATION_WINDOW_MS) {
                // Time to score and evaluate
                var score = 0
                if (candidatePeakMagnitude > impactThreshold) score += 1
                if (candidatePeakJerk > jerkThreshold) score += 1
                
                var rejectReason: String? = null
                if (candidatePeakGyro > gyroRejectThreshold) {
                    // Reduce score significantly if high rotation is detected
                    score -= 2
                    rejectReason = "rotation only (gyro=${candidatePeakGyro})"
                }
                
                if (score > 0) {
                    val detectedType = if (isNear) {
                        SlapType.POCKET_SLAP
                    } else if (candidatePeakMagnitude > impactThreshold * 1.5f || candidatePeakGyro < 2.5f) {
                        SlapType.TABLE_HIT
                    } else {
                        SlapType.OPEN_IMPACT
                    }

                    Log.d("SlapDetector", "SLAP_DETECTOR: confirmed slap type=$detectedType, score=$score, mag_peak=$candidatePeakMagnitude, jerk_peak=$candidatePeakJerk")
                    lastSlapTimeMs = now
                    state = State.COOLDOWN
                    onSlapDetected?.invoke(detectedType)
                } else {
                    Log.d("SlapDetector", "SLAP_DETECTOR: rejected - ${rejectReason ?: "low score"}")
                    state = State.IDLE
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

