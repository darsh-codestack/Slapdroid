package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = context.getSharedPreferences("slapdroid_prefs", Context.MODE_PRIVATE)
            val slapEnabled = prefs.getBoolean("pocket_slap_enabled", false)
            val shakeEnabled = prefs.getBoolean("shake_sound_enabled", false)
            val callEnabled = prefs.getBoolean("call_gestures_enabled", false)
            val airEnabled = prefs.getBoolean("air_gestures_enabled", false)
            
            if (slapEnabled || shakeEnabled) {
                Log.d("BootReceiver", "BOOT_RECEIVER: restarting gesture service because pocketSlapEnabled=$slapEnabled shakeEnabled=$shakeEnabled")
                try {
                    val serviceIntent = Intent(context, SlapDetectionService::class.java)
                    androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            if (callEnabled) {
                try {
                    val callIntent = Intent(context, CallGestureService::class.java)
                    androidx.core.content.ContextCompat.startForegroundService(context, callIntent)
                } catch (e: Exception) {}
            }
            if (airEnabled) {
                try {
                    val airIntent = Intent(context, AirGestureService::class.java)
                    androidx.core.content.ContextCompat.startForegroundService(context, airIntent)
                } catch (e: Exception) {}
            }
        }
    }
}
