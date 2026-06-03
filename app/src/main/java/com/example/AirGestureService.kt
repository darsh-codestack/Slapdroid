package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
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
import androidx.core.app.NotificationCompat

class AirGestureService : Service(), SensorEventListener {

    enum class DetectorState {
        DISARMED,
        ARMED_WAITING_FOR_WAVE,
        NEAR_SEEN,
        CALL_SILENCED
    }

    private lateinit var sensorManager: SensorManager
    private lateinit var telephonyManager: TelephonyManager
    private lateinit var audioManager: AudioManager
    
    private val handler = Handler(Looper.getMainLooper())
    
    private var proximitySensor: Sensor? = null
    private var isSensorsRegistered = false
    
    private var telephonyCallback: TelephonyCallback? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null
    
    private var currentState = DetectorState.DISARMED
    private var nearStartTime = 0L
    
    private var nearHoldRunnable: Runnable? = null
    private var safetyTimeoutRunnable: Runnable? = null

    // For Audio fallback restoration
    private var previousRingerMode = -1
    private var previousRingVolume = -1
    private var whetherAppChangedAudio = false

    private val PROX_NEAR_HOLD_MS = 60L
    private val WAVE_MAX_DURATION_MS = 900L
    private val WAVE_MIN_DURATION_MS = 80L

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        createNotificationChannel()
        startForegroundServiceCorrectly()

        setupCallStateDetection()
        
        AirGestureState.isRunning.value = true
        AirGestureState.log("AIR_SERVICE_CREATED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AirGestureState.log("AIR_SERVICE_STARTED")
        return START_NOT_STICKY
    }

    private fun startForegroundServiceCorrectly() {
        val notification = androidx.core.app.NotificationCompat.Builder(this, "AIR_GESTURE_CHANNEL")
            .setContentTitle("Air Gesture")
            .setContentText("Monitoring...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(4, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(4, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("AIR_GESTURE_CHANNEL", "Air Gestures", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

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
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
            }
        } catch (e: SecurityException) {
            AirGestureState.log("CALL_STATE_SECURITY_EXCEPTION")
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
        AirGestureState.log("AIR_GESTURE: CALL_STATE_$newState received")
        AirGestureState.callState.value = newState
        
        if (newState == "RINGING") {
            if (currentState == DetectorState.DISARMED) {
                currentState = DetectorState.ARMED_WAITING_FOR_WAVE
                AirGestureState.log("AIR_GESTURE: call ringing, detector armed")
                
                previousRingerMode = audioManager.ringerMode
                previousRingVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
                whetherAppChangedAudio = false
                AirGestureState.log("AIR_GESTURE: saved ringerMode=$previousRingerMode volume=$previousRingVolume")
                
                registerSensors()
            }
        } else {
            // OFFHOOK or IDLE
            if (newState == "OFFHOOK") {
                AirGestureState.log("AIR_GESTURE: CALL_STATE_OFFHOOK received")
            } else {
                AirGestureState.log("AIR_GESTURE: CALL_STATE_IDLE received")
            }
            
            restoreAudioIfNeeded()
            
            currentState = DetectorState.DISARMED
            AirGestureState.log("AIR_GESTURE: detector reset for next call")
            unregisterSensors()
            cancelRunnables()
        }
    }

    private fun registerSensors() {
        if (isSensorsRegistered) return
        if (proximitySensor == null) return
        isSensorsRegistered = true
        sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_FASTEST)
    }

    private fun unregisterSensors() {
        if (!isSensorsRegistered) return
        isSensorsRegistered = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_PROXIMITY) return
        
        // Ignore if not armed or already silenced
        if (currentState == DetectorState.DISARMED || currentState == DetectorState.CALL_SILENCED) return
        
        val distance = event.values[0]
        val maxRange = event.sensor.maximumRange
        val isNear = distance < maxRange && distance < 5.0f
        val now = System.currentTimeMillis()
        
        if (isNear) {
            if (currentState == DetectorState.ARMED_WAITING_FOR_WAVE) {
                currentState = DetectorState.NEAR_SEEN
                nearStartTime = now
                AirGestureState.log("AIR_GESTURE: proximity near")
                
                // Set near hold runnable
                nearHoldRunnable = Runnable {
                    if (currentState == DetectorState.NEAR_SEEN) {
                        AirGestureState.log("AIR_GESTURE: wave confirmed (hold)")
                        triggerSilence()
                    }
                }
                handler.postDelayed(nearHoldRunnable!!, PROX_NEAR_HOLD_MS)
            }
        } else {
            // FAR
            if (currentState == DetectorState.NEAR_SEEN) {
                nearHoldRunnable?.let { handler.removeCallbacks(it) }
                AirGestureState.log("AIR_GESTURE: proximity far")
                
                val duration = now - nearStartTime
                if (duration in WAVE_MIN_DURATION_MS..WAVE_MAX_DURATION_MS) {
                    AirGestureState.log("AIR_GESTURE: wave confirmed")
                    triggerSilence()
                } else if (duration > WAVE_MAX_DURATION_MS) {
                    AirGestureState.log("AIR_GESTURE: rejected - too long")
                    currentState = DetectorState.ARMED_WAITING_FOR_WAVE
                } else {
                    AirGestureState.log("AIR_GESTURE: rejected - too short")
                    currentState = DetectorState.ARMED_WAITING_FOR_WAVE
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    @android.annotation.SuppressLint("MissingPermission")
    private fun triggerSilence() {
        currentState = DetectorState.CALL_SILENCED
        AirGestureState.log("AIR_GESTURE: silencing current incoming call")
        
        val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                telecomManager.silenceRinger()
                AirGestureState.log("AIR_GESTURE: Silence method used: TelecomManager")
                return
            }
        } catch (e: SecurityException) {
            AirGestureState.log("AIR_GESTURE: TelecomManager failed: SecurityException")
        } catch (e: Exception) {
            AirGestureState.log("AIR_GESTURE: TelecomManager failed: Exception")
        }

        // Fallback
        try {
            whetherAppChangedAudio = true
            AirGestureState.log("AIR_GESTURE: App changed audio = true")
            
            // Attempt to mute stream
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            } catch (e: Exception) {}
            
            // Attempt to set silent mode
            try {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            } catch (e: Exception) {}
            
            AirGestureState.log("AIR_GESTURE: Silence method used: AudioManager fallback")
            
            // Start safety timeout
            safetyTimeoutRunnable = Runnable {
                AirGestureState.log("AIR_GESTURE: Safety timeout restore triggered")
                restoreAudioIfNeeded()
            }
            handler.postDelayed(safetyTimeoutRunnable!!, 60000)
            
        } catch (e: Exception) {
            AirGestureState.log("AIR_GESTURE: Fallback failed: ${e.message}")
        }
    }
    
    private fun restoreAudioIfNeeded() {
        safetyTimeoutRunnable?.let { handler.removeCallbacks(it) }
        
        if (whetherAppChangedAudio) {
            AirGestureState.log("AIR_GESTURE: call ended, restoring audio")
            try {
                if (previousRingerMode != -1) {
                    audioManager.ringerMode = previousRingerMode
                }
                if (previousRingVolume != -1) {
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, previousRingVolume, 0)
                }
                AirGestureState.log("AIR_GESTURE: restored ringerMode=$previousRingerMode volume=$previousRingVolume")
            } catch (e: Exception) {
                AirGestureState.log("AIR_GESTURE: restore failed: ${e.message}")
            }
            whetherAppChangedAudio = false
        } else {
            AirGestureState.log("AIR_GESTURE: Restore skipped because app did not change audio")
        }
    }
    
    private fun cancelRunnables() {
        nearHoldRunnable?.let { handler.removeCallbacks(it) }
        safetyTimeoutRunnable?.let { handler.removeCallbacks(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterSensors()
        cancelRunnables()
        restoreAudioIfNeeded()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && telephonyCallback != null) {
                telephonyManager.unregisterTelephonyCallback(telephonyCallback!!)
            } else if (phoneStateListener != null) {
                @Suppress("DEPRECATION")
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (e: Exception) {}
        AirGestureState.isRunning.value = false
        AirGestureState.log("AIR_SERVICE_DESTROYED")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

