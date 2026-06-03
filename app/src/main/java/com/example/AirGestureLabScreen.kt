package com.example

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AirGestureLabScreen(navController: NavController) {
    val context = LocalContext.current
    
    val isRunning by AirGestureState.isRunning.collectAsState()
    val callState by AirGestureState.callState.collectAsState()
    val proximityAvailable by AirGestureState.proximityAvailable.collectAsState()
    val rawProximity by AirGestureState.rawProximity.collectAsState()
    val interpretedState by AirGestureState.interpretedState.collectAsState()
    val airGestureState by AirGestureState.airGestureState.collectAsState()
    val nearStartTime by AirGestureState.nearStartTime.collectAsState()
    val nearDuration by AirGestureState.nearDuration.collectAsState()
    val lastWaveTime by AirGestureState.lastWaveDetectedTime.collectAsState()
    val lastActionAttempted by AirGestureState.lastActionAttempted.collectAsState()
    val lastActionResult by AirGestureState.lastActionResult.collectAsState()
    val lastException by AirGestureState.lastExceptionMessage.collectAsState()
    val logs by AirGestureState.logs.collectAsState()

    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Air Gesture Lab") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { 
                    val intent = Intent(context, AirGestureService::class.java).apply { action = "TEST_MODE_START" }
                    context.startService(intent)
                }) { Text("Start Test") }
                Button(onClick = { 
                    val intent = Intent(context, AirGestureService::class.java).apply { action = "TEST_MODE_STOP" }
                    context.startService(intent)
                    context.stopService(intent)
                }) { Text("Stop Test") }
                Button(onClick = { 
                    val intent = Intent(context, AirGestureService::class.java).apply { action = "SIMULATE_RINGING" }
                    context.startService(intent)
                }) { Text("Sim Ringing") }
                Button(onClick = { 
                    val intent = Intent(context, AirGestureService::class.java).apply { action = "SIMULATE_ACTIVE_CALL" }
                    context.startService(intent)
                }) { Text("Sim Active") }
                Button(onClick = { 
                    val intent = Intent(context, AirGestureService::class.java).apply { action = "SIMULATE_IDLE" }
                    context.startService(intent)
                }) { Text("Sim Idle") }
                Button(onClick = { 
                    val intent = Intent(context, AirGestureService::class.java).apply { action = "TEST_LOCK_NOW" }
                    context.startService(intent)
                }) { Text("Test Lock") }
                Button(onClick = { AirGestureState.clearLogs() }) { Text("Clear Logs") }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("Service Running: $isRunning", fontWeight = FontWeight.Bold)
                Text("Call State: $callState", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Proximity Sensor Available: $proximityAvailable")
                Text("Raw Proximity Value: $rawProximity")
                Text("Interpreted State: $interpretedState", fontWeight = FontWeight.Bold, color = if (interpretedState == "NEAR") Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurface)
                Text("Air Gesture State: $airGestureState", fontWeight = FontWeight.Bold)
                Text("Near Start Time: ${if (nearStartTime > 0) timeFormat.format(Date(nearStartTime)) else "N/A"}")
                Text("Near Duration: ${nearDuration}ms")
                Text("Last Wave Detected Time: ${if (lastWaveTime > 0) timeFormat.format(Date(lastWaveTime)) else "N/A"}")
                Text("Last Action Attempted: $lastActionAttempted")
                Text("Last Action Result: $lastActionResult", color = if (lastActionResult.contains("SUCCESS")) Color(0xFF4CAF50) else Color(0xFFF44336))
                Text("Last Exception: $lastException")

                Spacer(modifier = Modifier.height(16.dp))
                Text("Logs:", fontWeight = FontWeight.Bold)
                HorizontalDivider()
                logs.forEach { log ->
                    Text(log, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
