package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

object DebugGestureState {
    val isRunning = MutableStateFlow(false)
    val callState = MutableStateFlow("UNKNOWN")
    var accX = MutableStateFlow(0f)
    var accY = MutableStateFlow(0f)
    var accZ = MutableStateFlow(0f)
    var gravX = MutableStateFlow(0f)
    var gravY = MutableStateFlow(0f)
    var gravZ = MutableStateFlow(0f)
    var linAccMag = MutableStateFlow(0f)
    var gyroMag = MutableStateFlow(0f)
    var proximity = MutableStateFlow("UNAVAILABLE")
    var lastEvent = MutableStateFlow("NONE")
    var lastSilenceResult = MutableStateFlow("NONE")
    var lastError = MutableStateFlow("NONE")
    val logs = MutableStateFlow<List<String>>(emptyList())
    var isTestMode = MutableStateFlow(false)

    fun log(msg: String) {
        val current = logs.value.toMutableList()
        current.add(0, msg)
        if (current.size > 50) current.removeAt(current.lastIndex)
        logs.value = current
        lastEvent.value = msg
        Log.d("CallGestureService", msg)
    }

    fun clearLogs() {
        logs.value = emptyList()
        lastEvent.value = "NONE"
        lastError.value = "NONE"
        lastSilenceResult.value = "NONE"
    }
}

class CallGestureService : Service(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var audioManager: AudioManager
    
    private val handler = Handler(Looper.getMainLooper())
    
    private var isSensorsRegistered = false
    private var alreadyTriggeredThisCall = false
    private var flipCandidate = false
    private var callStateContext = "IDLE"

    // Optional Sensors
    private var gravitySensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    private var linearAccelerationSensor: Sensor? = null
    private var gyroscopeSensor: Sensor? = null
    private var proximitySensor: Sensor? = null

    // State backups
    private var prevRingerMode = AudioManager.RINGER_MODE_NORMAL
    private var prevVolume = 0

    // Latest sensor data
    private var lastGravityZ = 0f

    // API 31+ Callback
    private var telephonyCallback: TelephonyCallback? = null
    private var phoneStateListener: PhoneStateListener? = null

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        createNotificationChannel()
        startForegroundServiceCorrectly()

        setupCallStateDetection()
        DebugGestureState.isRunning.value = true
        DebugGestureState.log("SERVICE_CREATED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "TEST_MODE_START") {
            DebugGestureState.isTestMode.value = true
            DebugGestureState.log("TEST_MODE_STARTED")
            registerSensors()
        } else if (intent?.action == "TEST_MODE_STOP") {
            DebugGestureState.isTestMode.value = false
            DebugGestureState.log("TEST_MODE_STOPPED")
            if (callStateContext != "RINGING") {
                unregisterSensors()
            }
        } else if (intent?.action == "SIMULATE_RINGING") {
            handleCallStateChange("RINGING")
        } else if (intent?.action == "SIMULATE_IDLE") {
            handleCallStateChange("IDLE")
        } else if (intent?.action == "TEST_SILENCE") {
            triggerSilence(false)
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceCorrectly() {
        val notification = androidx.core.app.NotificationCompat.Builder(this, "CALL_GESTURE_CHANNEL")
            .setContentTitle("Call Gesture")
            .setContentText("Monitoring...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(3, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(3, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("CALL_GESTURE_CHANNEL", "Call Gestures", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    // --- LAYER A: Call state detection ---
    private fun setupCallStateDetection() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        mapTelephonyState(state)
                    }
                }
                telephonyManager.registerTelephonyCallback(mainExecutor, telephonyCallback!!)
            } else {
                phoneStateListener = object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        mapTelephonyState(state)
                    }
                }
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: SecurityException) {
            DebugGestureState.lastError.value = "SecurityException in CallState: ${e.message}"
            DebugGestureState.log("CALL_STATE_SECURITY_EXCEPTION")
        }
    }

    private fun mapTelephonyState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> handleCallStateChange("RINGING")
            TelephonyManager.CALL_STATE_OFFHOOK -> handleCallStateChange("OFFHOOK")
            TelephonyManager.CALL_STATE_IDLE -> handleCallStateChange("IDLE")
        }
    }

    private fun handleCallStateChange(newState: String) {
        DebugGestureState.log("CALL_STATE_CHANGED_TO_$newState")
        DebugGestureState.callState.value = newState
        callStateContext = newState

        if (newState == "RINGING") {
            val prefs = getSharedPreferences("slapdroid_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("slam_mute_enabled", false) || DebugGestureState.isTestMode.value) {
                alreadyTriggeredThisCall = false
                flipCandidate = false
                storeAudioState()
                registerSensors()
            }
        } else {
            flipCandidate = false
            if (!DebugGestureState.isTestMode.value) {
                unregisterSensors()
            }
            restoreAudioState()
        }
    }

    // --- LAYER B: Gesture detection ---
    private fun registerSensors() {
        if (isSensorsRegistered) return
        isSensorsRegistered = true
        DebugGestureState.log("SENSORS_REGISTERED")

        if (gravitySensor != null) sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME) else DebugGestureState.log("SENSOR_UNAVAILABLE: GRAVITY")
        if (accelerometerSensor != null) sensorManager.registerListener(this, accelerometerSensor, SensorManager.SENSOR_DELAY_GAME) else DebugGestureState.log("SENSOR_UNAVAILABLE: ACCEL")
        if (linearAccelerationSensor != null) sensorManager.registerListener(this, linearAccelerationSensor, SensorManager.SENSOR_DELAY_GAME) else DebugGestureState.log("SENSOR_UNAVAILABLE: LIN_ACCEL")
        if (gyroscopeSensor != null) sensorManager.registerListener(this, gyroscopeSensor, SensorManager.SENSOR_DELAY_GAME) else DebugGestureState.log("SENSOR_UNAVAILABLE: GYRO")
        if (proximitySensor != null) sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL) else DebugGestureState.log("SENSOR_UNAVAILABLE: PROXIMITY")
    }

    private fun unregisterSensors() {
        if (!isSensorsRegistered) return
        isSensorsRegistered = false
        DebugGestureState.log("SENSORS_UNREGISTERED")
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                DebugGestureState.accX.value = event.values[0]
                DebugGestureState.accY.value = event.values[1]
                DebugGestureState.accZ.value = event.values[2]
                if (gravitySensor == null) {
                    // Fallback to Accel for gravity if gravity sensor doesn't exist
                    lastGravityZ = event.values[2]
                    checkFlipCondition()
                }
            }
            Sensor.TYPE_GRAVITY -> {
                DebugGestureState.gravX.value = event.values[0]
                DebugGestureState.gravY.value = event.values[1]
                DebugGestureState.gravZ.value = event.values[2]
                lastGravityZ = event.values[2]
                checkFlipCondition()
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val mag = sqrt((event.values[0]*event.values[0] + event.values[1]*event.values[1] + event.values[2]*event.values[2]).toDouble()).toFloat()
                DebugGestureState.linAccMag.value = mag
                checkSlamCondition(mag)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val mag = sqrt((event.values[0]*event.values[0] + event.values[1]*event.values[1] + event.values[2]*event.values[2]).toDouble()).toFloat()
                DebugGestureState.gyroMag.value = mag
            }
            Sensor.TYPE_PROXIMITY -> {
                DebugGestureState.proximity.value = if (event.values[0] < event.sensor.maximumRange) "NEAR" else "FAR"
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun checkFlipCondition() {
        if (lastGravityZ < -7.0f && !flipCandidate) {
            flipCandidate = true
            DebugGestureState.log("FLIP_CANDIDATE_STARTED")
            handler.postDelayed({
                // After 400ms, check again
                if ((callStateContext == "RINGING" || DebugGestureState.isTestMode.value) && lastGravityZ < -7.0f && !alreadyTriggeredThisCall) {
                    DebugGestureState.log("FLIP_CONFIRMED")
                    triggerSilence(true)
                } else {
                    flipCandidate = false
                    DebugGestureState.log("FLIP_CANDIDATE_FAILED")
                }
            }, 400)
        } else if (lastGravityZ >= -7.0f) {
            flipCandidate = false
        }
    }

    private var slamSpikeTime: Long = 0
    private fun checkSlamCondition(mag: Float) {
        if (mag > 15f && !alreadyTriggeredThisCall) {
            val now = System.currentTimeMillis()
            if (now - slamSpikeTime > 1000) {
                slamSpikeTime = now
                DebugGestureState.log("SLAM_SPIKE_DETECTED")
                handler.postDelayed({
                    if ((callStateContext == "RINGING" || DebugGestureState.isTestMode.value) && !alreadyTriggeredThisCall) {
                        if (DebugGestureState.gyroMag.value < 2.0f) { // Ensure it stabilized
                            DebugGestureState.log("SLAM_CONFIRMED")
                            triggerSilence(true)
                        } else {
                            DebugGestureState.log("SLAM_CANDIDATE_REJECTED_UNSTABLE")
                        }
                    }
                }, 300)
            }
        }
    }

    // --- LAYER C: Silence action ---
    private fun storeAudioState() {
        try {
            prevRingerMode = audioManager.ringerMode
            prevVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
        } catch (e: Exception) {}
    }

    private fun restoreAudioState() {
        if (alreadyTriggeredThisCall) {
            try {
                audioManager.ringerMode = prevRingerMode
                audioManager.setStreamVolume(AudioManager.STREAM_RING, prevVolume, 0)
            } catch (e: Exception) {}
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun triggerSilence(enforceCallCheck: Boolean) {
        if (enforceCallCheck && callStateContext != "RINGING" && !DebugGestureState.isTestMode.value) return
        if (alreadyTriggeredThisCall) return
        alreadyTriggeredThisCall = true

        DebugGestureState.log("SILENCE_ATTEMPT_STARTED")

        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecomManager.silenceRinger()
                DebugGestureState.lastSilenceResult.value = "SUCCESS: TelecomManager"
                DebugGestureState.log("SILENCE_ATTEMPT_SUCCESS (TelecomManager)")
                return
            }
        } catch (e: SecurityException) {
            DebugGestureState.lastError.value = "TelecomManager SecurityException"
            DebugGestureState.log("SILENCE_ATTEMPT_FAILED_SECURITY_EXCEPTION")
        }

        // Fallback to AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioManager.isVolumeFixed) {
                DebugGestureState.log("SILENCE_FAIL: VOLUME_FIXED")
                return
            }
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            DebugGestureState.lastSilenceResult.value = "SUCCESS: AudioManager fallback"
            DebugGestureState.log("SILENCE_ATTEMPT_SUCCESS (AudioManager)")
        } catch (e: Exception) {
            DebugGestureState.lastError.value = "AudioManager Error: ${e.message}"
            DebugGestureState.log("SILENCE_ATTEMPT_FAILED_AUDIO_FALLBACK")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterSensors()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telephonyCallback != null) {
            telephonyManager.unregisterTelephonyCallback(telephonyCallback!!)
        } else if (phoneStateListener != null) {
            telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
        }
        DebugGestureState.isRunning.value = false
        DebugGestureState.log("SERVICE_DESTROYED")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
