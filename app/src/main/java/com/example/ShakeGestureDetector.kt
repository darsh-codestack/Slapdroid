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
import kotlin.math.sign

class ShakeGestureDetector(
    private val context: Context,
    private val onShakeGestureDetected: () -> Unit
) : SensorEventListener {

    enum class State {
        IDLE, COLLECTING_SEGMENTS, TRIGGERED_COOLDOWN
    }

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    private var state = State.IDLE

    // Constants
    private val AXIS_SPIKE_THRESHOLD = 6.5f
    private val MAGNITUDE_THRESHOLD = 9.5f
    private val MIN_TIME_BETWEEN_SEGMENTS_MS = 60L
    private val MAX_TIME_BETWEEN_SEGMENTS_MS = 400L
    private val REQUIRED_DIRECTION_CHANGES = 2 // 3 segments total
    private val GESTURE_WINDOW_MS = 1200L
    private val TRIGGER_COOLDOWN_MS = 1500L

    private var segmentCount = 0
    private var sequenceStartTime = 0L
    private var lastSegmentTime = 0L
    private var lastTriggerTime = 0L
    private var lastDirection = 0f
    
    private var gravity = FloatArray(3)
    private var hasGravity = false
    private var sensorRegisteredTime = 0L
    private val WARMUP_TIME_MS = 250L
    private var isWarmupLogged = false
    private val ALPHA = 0.8f
    private var isSensorRegistered = false

    // Debug throttling
    private var lastLogTime = 0L

    fun isStarted(): Boolean {
        return isSensorRegistered
    }

    fun start() {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        state = State.IDLE
        segmentCount = 0
        hasGravity = false
        sensorRegisteredTime = SystemClock.elapsedRealtime()
        isWarmupLogged = false

        if (accelerometer != null) {
            val success = sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME) == true
            if (success) {
                isSensorRegistered = true
                Log.d("ShakeGesture", "SHAKE_GESTURE: sensor registered successfully")
            } else {
                Log.d("ShakeGesture", "SHAKE_GESTURE: sensor registration failed")
            }
        } else {
            Log.d("ShakeGesture", "SHAKE_GESTURE: accelerometer unavailable")
        }
    }

    fun stop() {
        Log.d("ShakeGesture", "SHAKE_GESTURE: accelerometer unregistered")
        sensorManager?.unregisterListener(this)
        isSensorRegistered = false
        state = State.IDLE
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val now = SystemClock.elapsedRealtime()

        if (!hasGravity) {
            gravity[0] = event.values[0]
            gravity[1] = event.values[1]
            gravity[2] = event.values[2]
            hasGravity = true
            return
        }

        // Apply high-pass filter
        gravity[0] = ALPHA * gravity[0] + (1 - ALPHA) * event.values[0]
        gravity[1] = ALPHA * gravity[1] + (1 - ALPHA) * event.values[1]
        gravity[2] = ALPHA * gravity[2] + (1 - ALPHA) * event.values[2]

        val lx = event.values[0] - gravity[0]
        val ly = event.values[1] - gravity[1]
        val lz = event.values[2] - gravity[2]

        if (now - sensorRegisteredTime < WARMUP_TIME_MS) {
            if (!isWarmupLogged) {
                Log.d("ShakeGesture", "SHAKE_GESTURE: warmup active")
                isWarmupLogged = true
            }
            return
        } else if (isWarmupLogged) {
            Log.d("ShakeGesture", "SHAKE_GESTURE: warmup finished")
            isWarmupLogged = false
        }

        val magnitude = sqrt((lx * lx + ly * ly + lz * lz).toDouble()).toFloat()

        if (now - lastLogTime > 500) {
            // Log.d("ShakeGesture", "SHAKE_GESTURE: raw x=${event.values[0]}, y=${event.values[1]}, z=${event.values[2]}")
            // Log.d("ShakeGesture", "SHAKE_GESTURE: linear x=$lx, y=$ly, z=$lz")
            // Log.d("ShakeGesture", "SHAKE_GESTURE: magnitude=$magnitude")
            lastLogTime = now
        }

        if (state == State.TRIGGERED_COOLDOWN) {
            if (now - lastTriggerTime >= TRIGGER_COOLDOWN_MS) {
                Log.d("ShakeGesture", "SHAKE_GESTURE: cooldown active -> IDLE")
                state = State.IDLE
                segmentCount = 0
            } else {
                return
            }
        }

        if (state == State.COLLECTING_SEGMENTS) {
            if (now - sequenceStartTime > GESTURE_WINDOW_MS) {
                Log.d("ShakeGesture", "SHAKE_GESTURE: rejected - sequence expired")
                state = State.IDLE
                segmentCount = 0
            }
        }

        // Determine dominant axis
        val absX = abs(lx)
        val absY = abs(ly)
        val absZ = abs(lz)

        val dominantAxisValue = if (absX > absY && absX > absZ) lx else if (absY > absX && absY > absZ) ly else lz
        val dominantAxisName = if (absX > absY && absX > absZ) "X" else if (absY > absX && absY > absZ) "Y" else "Z"

        if (magnitude < MAGNITUDE_THRESHOLD || abs(dominantAxisValue) < AXIS_SPIKE_THRESHOLD) {
            return
        }

        val currentDirection = sign(dominantAxisValue)

        if (segmentCount == 0) {
            Log.d("ShakeGesture", "SHAKE_GESTURE: first spike counted segmentCount=1, dominantAxis=$dominantAxisName value=$dominantAxisValue magnitude=$magnitude")
            sequenceStartTime = now
            lastSegmentTime = now
            lastDirection = currentDirection
            segmentCount = 1
            state = State.COLLECTING_SEGMENTS
        } else if (currentDirection == lastDirection) {
            // Keep the segment alive, update lastSegmentTime so we don't timeout
            lastSegmentTime = now
        } else {
            val timeSinceLastSegment = now - lastSegmentTime
            if (timeSinceLastSegment < MIN_TIME_BETWEEN_SEGMENTS_MS) {
                Log.d("ShakeGesture", "SHAKE_GESTURE: rejected too soon")
                return
            }
            if (timeSinceLastSegment > MAX_TIME_BETWEEN_SEGMENTS_MS) {
                Log.d("ShakeGesture", "SHAKE_GESTURE: rejected too slow")
                state = State.IDLE
                segmentCount = 0
                return
            }

            segmentCount++
            lastDirection = currentDirection
            lastSegmentTime = now

            if (segmentCount == 2) {
                Log.d("ShakeGesture", "SHAKE_GESTURE: direction changed segmentCount=2")
            } else if (segmentCount == 3) {
                Log.d("ShakeGesture", "SHAKE_GESTURE: direction changed segmentCount=3")
            }

            if (segmentCount >= REQUIRED_DIRECTION_CHANGES + 1) { // 1 initial + X changes = X+1 segments
                Log.d("ShakeGesture", "SHAKE_GESTURE: trigger action")
                state = State.TRIGGERED_COOLDOWN
                lastTriggerTime = now
                segmentCount = 0
                onShakeGestureDetected.invoke()
                Log.d("ShakeGesture", "SHAKE_GESTURE: cooldown active")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
