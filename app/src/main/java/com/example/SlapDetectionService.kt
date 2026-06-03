package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.media.SoundPool
import android.util.Log
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

// Note: Some OEMs aggressively kill background services or restrict sensors when the screen is locked,
// unless battery optimization is disabled for this app.

class SlapDetectionService : Service() {

    private var inPocket = false

    private lateinit var slapImpactDetector: SlapImpactDetector
    private lateinit var shakeGestureDetector: ShakeGestureDetector
    
    private var isTorchOn = false
    
    private var shakeSoundId: Int = 0
    private var soundPool: SoundPool? = null
    private var isShakeSoundLoaded = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        if (key == "pocket_slap_enabled" || key == "enable_partial_wakelock") {
            if (sharedPreferences.getBoolean("pocket_slap_enabled", false)) {
                slapImpactDetector.start()
            } else {
                slapImpactDetector.stop()
            }
            updateWakeLock()
        } else if (key == "pocket_slap_volume") {
            try {
                toneGenerator?.release()
                val vol = (sharedPreferences.getFloat("pocket_slap_volume", 1.0f) * ToneGenerator.MAX_VOLUME).toInt()
                toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, vol)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else if (key == "shake_sound_enabled") {
            if (sharedPreferences.getBoolean("shake_sound_enabled", false)) {
                shakeGestureDetector.start()
            } else {
                shakeGestureDetector.stop()
            }
        }
    }

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var toneGenerator: ToneGenerator? = null
    private var tts: TextToSpeech? = null
    
    private lateinit var prefs: SharedPreferences
    private var lastAnnouncedBatteryPct: Int = -1
    private var lastProximityTime: Long = 0
    private var isRinging = false
    private var watchdogJob: kotlinx.coroutines.Job? = null

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        exception.printStackTrace()
    }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + exceptionHandler)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent?.action == Intent.ACTION_POWER_CONNECTED) {
                    if (prefs.getBoolean("charger_noises_enabled", false)) {
                        playChargerTone(true)
                    }
                } else if (intent?.action == Intent.ACTION_POWER_DISCONNECTED) {
                    if (prefs.getBoolean("charger_noises_enabled", false)) {
                        playChargerTone(false)
                    }
                } else if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    handleBatteryLevel(intent)
                } else if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                    val state = intent?.getStringExtra(TelephonyManager.EXTRA_STATE)
                    isRinging = state == TelephonyManager.EXTRA_STATE_RINGING
                } else if (intent?.action == Intent.ACTION_SCREEN_OFF || intent?.action == Intent.ACTION_SCREEN_ON || intent?.action == Intent.ACTION_USER_PRESENT) {
                    // Re-verify sensors are registered if they should be
                    try {
                        if (prefs.getBoolean("pocket_slap_enabled", false)) {
                            // Stop and start the detector to re-register the sensors
                            slapImpactDetector.stop()
                            slapImpactDetector.start()
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateWakeLock() {
        val needsWakeLock = prefs.getBoolean("pocket_slap_enabled", false)
        // Warning: WakeLock can drain battery. Optional feature.
        val enableWakeLock = prefs.getBoolean("enable_partial_wakelock", false)
        if (needsWakeLock && enableWakeLock) {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SlapDroid::PocketSlapWakeLock")
                wakeLock?.acquire()
            }
        } else {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
                wakeLock = null
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        
        prefs = getSharedPreferences("slapdroid_prefs", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        slapImpactDetector = SlapImpactDetector(this).apply {
            setCallback { type ->
                when (type) {
                    SlapType.POCKET_SLAP -> {
                        if (prefs.getBoolean("pocket_slap_enabled", false)) {
                            playPocketSlapSound()
                        }
                    }
                    SlapType.OPEN_IMPACT, SlapType.TABLE_HIT -> {
                        if (type == SlapType.TABLE_HIT && prefs.getBoolean("slam_mute_enabled", false) && isRinging) {
                            silenceRinger()
                        }
                    }
                }
            }
        }
        
        shakeGestureDetector = ShakeGestureDetector(this) {
            handleShakeGesture()
        }


        // Start or stop based on current preferences
        if (prefs.getBoolean("pocket_slap_enabled", false)) {
            slapImpactDetector.start()
        }
        if (prefs.getBoolean("shake_sound_enabled", false)) {
            shakeGestureDetector.start()
        }

        updateWakeLock()

        try {
            Log.d("SlapDetectionService", "SHAKE_SOUND: initializing SoundPool")
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            
            soundPool = android.media.SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(audioAttributes)
                .build()
            
            Log.d("SlapDetectionService", "SHAKE_SOUND: loading R.raw.shake_sound")
            val resId = resources.getIdentifier("shake_sound", "raw", packageName)
            if (resId != 0) {
                shakeSoundId = soundPool?.load(this, resId, 1) ?: 0
                Log.d("SlapDetectionService", "SHAKE_SOUND: soundId=$shakeSoundId")
                soundPool?.setOnLoadCompleteListener { _, sampleId, status ->
                    if (status == 0 && sampleId == shakeSoundId) {
                        isShakeSoundLoaded = true
                        Log.d("SlapDetectionService", "SHAKE_SOUND: sound loaded successfully id=$sampleId")
                    } else {
                        Log.e("SlapDetectionService", "SHAKE_SOUND: sound load failed status=$status sampleId=$sampleId")
                    }
                }
            } else {
                Log.e("SlapDetectionService", "SHAKE_SOUND: shake_sound.mp3 not found in raw resources")
            }
        } catch (e: Exception) {
            Log.e("SlapDetectionService", "SHAKE_SOUND: initialization error", e)
        }

        try {
            val vol = (prefs.getFloat("pocket_slap_volume", 1.0f) * ToneGenerator.MAX_VOLUME).toInt()
            toneGenerator = ToneGenerator(AudioManager.STREAM_ALARM, vol)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        try {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    // Fully operational
                }
            }
        } catch (e: Exception) {}
        
        // Start Foreground so service doesn't crash on Android 8+
        Log.d("SlapDetectionService", "SERVICE: onCreate")
        try {
            createNotificationChannel()
            val notification = NotificationCompat.Builder(this, "SLAPDROID_CHANNEL")
                .setContentTitle("SlapDroid Active")
                .setContentText("Monitoring Gestures.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
            
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
            Log.d("SlapDetectionService", "SERVICE: startForeground called")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        watchdogJob = scope.launch {
            while (isActive) {
                delay(45000)
                try {
                    val p_enabled = prefs.getBoolean("pocket_slap_enabled", false)
                    val s_enabled = prefs.getBoolean("shake_sound_enabled", false)
                    
                    if (p_enabled && !slapImpactDetector.isStarted()) {
                        Log.d("SlapDetectionService", "POCKET_SLAP: watchdog repaired sensor registration")
                        slapImpactDetector.start()
                    }
                    if (s_enabled && !shakeGestureDetector.isStarted()) {
                        Log.d("SlapDetectionService", "SHAKE_GESTURE: watchdog repaired sensor registration")
                        shakeGestureDetector.start()
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }

        // Broadcasts
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleShakeGesture() {
        Log.d("SlapDetectionService", "SHAKE_ACTION: shake detected")
        if (!prefs.getBoolean("shake_sound_enabled", false)) return

        val action = prefs.getString("shake_gesture_action", "Play Sound") ?: "Play Sound"
        Log.d("SlapDetectionService", "SHAKE_ACTION: selected action=$action")

        when (action) {
            "Play Sound" -> {
                Log.d("SlapDetectionService", "SHAKE_ACTION: calling playShakeSound")
                playShakeSound()
            }
            "Toggle Flashlight" -> {
                Log.d("SlapDetectionService", "SHAKE_ACTION: detected action=TOGGLE_TORCH")
                toggleTorch()
            }
            "Off" -> {
                // Do nothing
            }
        }
    }

    private fun playShakeSound() {
        Log.d("SlapDetectionService", "SHAKE_SOUND: play requested")
        val soundPref = prefs.getString("shake_sound_sound", "Default") ?: "Default"
        val vol = prefs.getFloat("shake_sound_volume", 1.0f)
        
        if (soundPref.startsWith("Custom:")) {
            if (isPlaying) return
            isPlaying = true
            val file = java.io.File(filesDir, "shake_sound_custom_audio")
            if (file.exists()) {
                try {
                    android.media.MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setAudioAttributes(android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                        setOnErrorListener { mp, _, _ -> 
                            mp.release()
                            this@SlapDetectionService.isPlaying = false
                            true 
                        }
                        setOnCompletionListener { it.release(); this@SlapDetectionService.isPlaying = false }
                        setOnPreparedListener { 
                            it.setVolume(vol, vol)
                            it.start() 
                        }
                        prepareAsync()
                    }
                } catch (e: Exception) { e.printStackTrace(); this@SlapDetectionService.isPlaying = false }
            } else this@SlapDetectionService.isPlaying = false
            return
        }

        if (soundPref == "Default") {
            try {
                if (!isShakeSoundLoaded || shakeSoundId == 0) {
                    Log.e("SlapDetectionService", "SHAKE_SOUND: failed because not loaded or soundId=0 (loaded=$isShakeSoundLoaded, soundId=$shakeSoundId)")
                    
                    // Fallback to ToneGenerator if SoundPool fails
                    Log.d("SlapDetectionService", "SHAKE_GESTURE: fallback ToneGenerator")
                    toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 500)
                    return
                }

                val result = soundPool?.play(
                    shakeSoundId,
                    vol,
                    vol,
                    1,
                    0,
                    1.0f
                ) ?: 0
                
                Log.d("SlapDetectionService", "SHAKE_SOUND: play result=$result")
                if (result == 0) {
                    Log.e("SlapDetectionService", "SHAKE_SOUND: play failed (result=0)")
                }
            } catch (e: Exception) {
                Log.e("SlapDetectionService", "SHAKE_GESTURE: sound play error", e)
            }
            return
        }

        if (isPlaying) return
        isPlaying = true
        scope.launch {
            try {
                var tone = ToneGenerator.TONE_CDMA_ABBR_ALERT
                if (soundPref == "Scream 1") tone = ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK
                else if (soundPref == "Yelp") tone = ToneGenerator.TONE_CDMA_ALERT_INCALL_LITE
                else if (soundPref == "Sci-Fi Beep") tone = ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD
                else if (soundPref == "Robot Connect") tone = ToneGenerator.TONE_SUP_CONFIRM
                
                toneGenerator?.startTone(tone, 300)
                delay(500)
            } finally {
                isPlaying = false
            }
        }
    }

    private fun toggleTorch() {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var rearCameraId: String? = null
            
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val isRear = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                val hasFlash = characteristics.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (isRear && hasFlash) {
                    rearCameraId = id
                    break
                }
            }
            
            if (rearCameraId != null) {
                isTorchOn = !isTorchOn
                cameraManager.setTorchMode(rearCameraId, isTorchOn)
                Log.d("SlapDetectionService", "SHAKE_GESTURE: torch toggled " + if (isTorchOn) "on" else "off")
            } else {
                Log.e("SlapDetectionService", "SHAKE_GESTURE: no suitable camera found for torch")
            }
        } catch (e: Exception) {
            Log.e("SlapDetectionService", "SHAKE_GESTURE: torch failed", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("SlapDetectionService", "SERVICE: onStartCommand")
        
        // Re-read prefs and verify states
        if (prefs.getBoolean("pocket_slap_enabled", false)) {
            Log.d("SlapDetectionService", "SERVICE: starting slap detector from onStartCommand")
            slapImpactDetector.start()
        }
        if (prefs.getBoolean("shake_sound_enabled", false)) {
            Log.d("SlapDetectionService", "SERVICE: starting shake detector from onStartCommand")
            shakeGestureDetector.start()
        }
        
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("SlapDetectionService", "SERVICE: onTaskRemoved")
        // Don't stop anything, keep it alive if possible
    }

    override fun onDestroy() {
        Log.d("SlapDetectionService", "SERVICE: onDestroy")
        try {
            watchdogJob?.cancel()
        } catch (e: Exception) {}

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) { e.printStackTrace() }

        try {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (e: Exception) { e.printStackTrace() }
        
        try {
            slapImpactDetector.stop()
            shakeGestureDetector.stop()
        } catch (e: Exception) { e.printStackTrace() }
        
        try {
            soundPool?.release()
            soundPool = null
        } catch (e: Exception) { e.printStackTrace() }
        
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) { e.printStackTrace() }
        
        try {
            toneGenerator?.release()
        } catch (e: Exception) { e.printStackTrace() }

        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) { e.printStackTrace() }
        
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var isPlaying = false

    private fun handleBatteryLevel(intent: Intent) {
        if (!prefs.getBoolean("battery_announce_enabled", false)) return
        
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        
        if (level == -1 || scale <= 0 || !isCharging) return
        
        val batteryPct = (level * 100) / scale
        
        if (batteryPct % 10 == 0) {
            if (batteryPct != lastAnnouncedBatteryPct) {
                lastAnnouncedBatteryPct = batteryPct
                tts?.speak("Battery at $batteryPct percent", TextToSpeech.QUEUE_FLUSH, null, null)
            }
        }
    }

    private fun playChargerTone(connected: Boolean) {
        val soundPref = prefs.getString("charger_noises_sound", "Default") ?: "Default"
        
        if (soundPref.startsWith("Custom:")) {
            val file = java.io.File(filesDir, "charger_noises_custom_audio")
            if (file.exists()) {
                try {
                    android.media.MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        setOnErrorListener { mp, _, _ -> 
                            mp.release()
                            true 
                        }
                        setOnCompletionListener { it.release() }
                        setOnPreparedListener { it.start() }
                        prepareAsync()
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            return
        }

        var tone = if (connected) ToneGenerator.TONE_DTMF_1 else ToneGenerator.TONE_DTMF_2
        if (soundPref == "Sci-Fi Beep") tone = if (connected) ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD else ToneGenerator.TONE_CDMA_ABBR_ALERT
        else if (soundPref == "Robot Connect") tone = if (connected) ToneGenerator.TONE_SUP_CONFIRM else ToneGenerator.TONE_SUP_ERROR

        toneGenerator?.startTone(tone, 200)
    }

    private fun playPocketSlapSound() {
        val soundPref = prefs.getString("pocket_slap_sound", "Default") ?: "Default"
        
        if (soundPref.startsWith("Custom:")) {
            if (isPlaying) return
            isPlaying = true
            val file = java.io.File(filesDir, "pocket_slap_custom_audio")
            if (file.exists()) {
                try {
                    android.media.MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setAudioAttributes(android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                        setOnErrorListener { mp, _, _ -> 
                            mp.release()
                            this@SlapDetectionService.isPlaying = false
                            true 
                        }
                        setOnCompletionListener { it.release(); this@SlapDetectionService.isPlaying = false }
                        setOnPreparedListener { it.start() }
                        prepareAsync()
                    }
                } catch (e: Exception) { e.printStackTrace(); this@SlapDetectionService.isPlaying = false }
            } else this@SlapDetectionService.isPlaying = false
            return
        }

        if (isPlaying) return
        isPlaying = true
        scope.launch {
            try {
                var tone = ToneGenerator.TONE_CDMA_ABBR_ALERT
                if (soundPref == "Scream 1") tone = ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK
                else if (soundPref == "Yelp") tone = ToneGenerator.TONE_CDMA_ALERT_INCALL_LITE
                
                toneGenerator?.startTone(tone, 300)
                delay(500)
            } finally {
                isPlaying = false
            }
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun silenceRinger() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                if (audioManager.isVolumeFixed) return
            } catch (e: Exception) { return }
            
            try {
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                scope.launch {
                    delay(5000)
                    try {
                        audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                    } catch (e: SecurityException) {} 
                      catch (e: Exception) {}
                }
            } catch (e: SecurityException) {
                // Ignore if Do Not Disturb access is missing
            } catch (e: Exception) {}
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "SLAPDROID_CHANNEL",
                "SlapDroid Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
