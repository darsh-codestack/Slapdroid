package com.example

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

object AirGestureState {
    val isRunning = MutableStateFlow(false)
    val callState = MutableStateFlow("UNKNOWN")
    val proximityAvailable = MutableStateFlow(false)
    val rawProximity = MutableStateFlow(0f)
    val interpretedState = MutableStateFlow("UNKNOWN") // NEAR / FAR
    val airGestureState = MutableStateFlow("IDLE") // IDLE / NEAR_STARTED / WAVE_CONFIRMED / COOLDOWN
    val nearStartTime = MutableStateFlow(0L)
    val nearDuration = MutableStateFlow(0L)
    val lastWaveDetectedTime = MutableStateFlow(0L)
    val lastActionAttempted = MutableStateFlow("NONE")
    val lastActionResult = MutableStateFlow("NONE")
    val lastExceptionMessage = MutableStateFlow("NONE")
    val logs = MutableStateFlow<List<String>>(emptyList())
    val isTestMode = MutableStateFlow(false)

    fun log(msg: String) {
        val current = logs.value.toMutableList()
        current.add(0, msg)
        if (current.size > 50) current.removeAt(current.lastIndex)
        logs.value = current
        Log.d("AirGesture", msg)
    }

    fun clearLogs() {
        logs.value = emptyList()
        lastActionAttempted.value = "NONE"
        lastActionResult.value = "NONE"
        lastExceptionMessage.value = "NONE"
        nearStartTime.value = 0L
        nearDuration.value = 0L
        lastWaveDetectedTime.value = 0L
        airGestureState.value = "IDLE"
    }
}
