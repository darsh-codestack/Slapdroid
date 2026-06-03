package com.example

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallGestureDebugScreen(navController: NavController) {
    val context = LocalContext.current
    
    val isRunning by DebugGestureState.isRunning.collectAsState()
    val callState by DebugGestureState.callState.collectAsState()
    val accX by DebugGestureState.accX.collectAsState()
    val accY by DebugGestureState.accY.collectAsState()
    val accZ by DebugGestureState.accZ.collectAsState()
    val gravX by DebugGestureState.gravX.collectAsState()
    val gravY by DebugGestureState.gravY.collectAsState()
    val gravZ by DebugGestureState.gravZ.collectAsState()
    val linAccMag by DebugGestureState.linAccMag.collectAsState()
    val gyroMag by DebugGestureState.gyroMag.collectAsState()
    val proximity by DebugGestureState.proximity.collectAsState()
    val lastEvent by DebugGestureState.lastEvent.collectAsState()
    val lastSilenceResult by DebugGestureState.lastSilenceResult.collectAsState()
    val lastError by DebugGestureState.lastError.collectAsState()
    val logs by DebugGestureState.logs.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug Sensor Tests") },
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
                    val intent = Intent(context, CallGestureService::class.java).apply { action = "TEST_MODE_START" }
                    context.startService(intent)
                }) { Text("Start Test") }
                Button(onClick = { 
                    val intent = Intent(context, CallGestureService::class.java).apply { action = "TEST_MODE_STOP" }
                    context.startService(intent)
                }) { Text("Stop Test") }
                Button(onClick = { 
                    val intent = Intent(context, CallGestureService::class.java).apply { action = "SIMULATE_RINGING" }
                    context.startService(intent)
                }) { Text("Sim Ring") }
                Button(onClick = { 
                    val intent = Intent(context, CallGestureService::class.java).apply { action = "SIMULATE_IDLE" }
                    context.startService(intent)
                }) { Text("Sim Idle") }
                Button(onClick = { 
                    val intent = Intent(context, CallGestureService::class.java).apply { action = "TEST_SILENCE" }
                    context.startService(intent)
                }) { Text("Test Silence") }
                Button(onClick = { DebugGestureState.clearLogs() }) { Text("Clear Logs") }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("Service Running: $isRunning", fontWeight = FontWeight.Bold)
                Text("Call State: $callState", fontWeight = FontWeight.Bold)
                Text("Acc: \${String.format(\"%.2f\", accX)}, \${String.format(\"%.2f\", accY)}, \${String.format(\"%.2f\", accZ)}")
                Text("Grav: \${String.format(\"%.2f\", gravX)}, \${String.format(\"%.2f\", gravY)}, \${String.format(\"%.2f\", gravZ)}")
                Text("LinAcc: \${String.format(\"%.2f\", linAccMag)}")
                Text("Gyro: \${String.format(\"%.2f\", gyroMag)}")
                Text("Proximity: $proximity")
                Text("Last Event: $lastEvent", color = MaterialTheme.colorScheme.primary)
                Text("Last Silence Result: $lastSilenceResult", color = if (lastSilenceResult.contains("SUCCESS")) Color.Green else Color.Red)
                Text("Last Error: $lastError", color = Color.Red)

                Spacer(modifier = Modifier.height(16.dp))
                Text("Logs:", fontWeight = FontWeight.Bold)
                Divider()
                logs.forEach { log ->
                    Text(log, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
