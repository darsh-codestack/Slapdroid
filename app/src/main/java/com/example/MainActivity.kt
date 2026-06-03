package com.example

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.*

data class Feature(
    val id: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val hasSoundSelection: Boolean = true
)

val featuresList = listOf(
    Feature("pocket_slap", "Pocket Slap", "Slapping the phone while it's in your pocket triggers a funny alert sound.", Icons.Default.Smartphone, true),
    Feature("shake_sound", "Shake Gesture", "Shake phone in a quick chop motion for a custom action.", Icons.Default.PanTool, false),
    Feature("battery_announce", "Battery Announcer", "Connecting or disconnecting power plays a custom robot tone.", Icons.Default.BatteryChargingFull, false),
    Feature("air_gestures", "Air Gesture", "Wave your hand over the proximity sensor to silence incoming calls.", Icons.Default.PanTool, false)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Thread.setDefaultUncaughtExceptionHandler(MyExceptionHandler(this, Thread.getDefaultUncaughtExceptionHandler()))
        try {
            val file = java.io.File(filesDir, "crash_log.txt")
            if (file.exists()) {
                val log = file.readText()
                android.widget.Toast.makeText(this, "CRASH: ${log.take(150)}", android.widget.Toast.LENGTH_LONG).show()
                android.util.Log.e("CRASH_LOG", log)
                // We'll also just delete it so it doesn't keep showing
                file.delete()
            }
        } catch (e: Exception) {}

        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val navController = rememberNavController()
                
                val permissionsToRequest = remember {
                    mutableListOf(
                        android.Manifest.permission.READ_PHONE_STATE
                    ).apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            add(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
                
                val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    // Proceed even if denied, as some features might still work
                }
                
                LaunchedEffect(Unit) {
                    permissionLauncher.launch(permissionsToRequest.toTypedArray())
                }
                
                // Start the service to always monitor if any feature is enabled
                LaunchedEffect(Unit) {
                    try {
                        androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, SlapDetectionService::class.java))
                    } catch (e: Exception) {}
                    
                    try {
                        androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, CallGestureService::class.java))
                    } catch (e: Exception) {}

                    try {
                        androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, AirGestureService::class.java))
                    } catch (e: Exception) {}
                }

                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(navController)
                    }
                    composable("debug") {
                        CallGestureDebugScreen(navController)
                    }
                    composable("air_gesture_lab") {
                        AirGestureLabScreen(navController)
                    }
                    composable("feature/{id}") { backStackEntry ->
                        val featureId = backStackEntry.arguments?.getString("id") ?: ""
                        val feature = featuresList.find { it.id == featureId }
                        if (feature != null) {
                            FeatureDetailScreen(feature, navController)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(navController: NavController) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .border(1.dp, OutlineColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "?",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .border(1.dp, OutlineColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = "Notifications",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            Text(
                text = "Hi Darsh,\nHow can I help\nyou today?",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                lineHeight = 44.sp,
                fontSize = 36.sp,
                letterSpacing = (-1).sp
            )

            val featureColors = listOf(PastelBlue, PastelGray, PastelGreen, PastelYellow, PastelPurple)

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(featuresList.size) { index ->
                    val feature = featuresList[index]
                    val color = featureColors[index % featureColors.size]
                    FeatureCard(
                        feature = feature,
                        color = color,
                        onClick = { navController.navigate("feature/${feature.id}") }
                    )
                }
            }
        }
    }
}

@Composable
fun FeatureCard(feature: Feature, color: Color, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.1f)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(1.dp, PrimaryBlack.copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = feature.icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = PrimaryBlack
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = feature.title, 
                style = MaterialTheme.typography.titleMedium, 
                fontWeight = FontWeight.Bold,
                color = PrimaryBlack,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureDetailScreen(feature: Feature, navController: NavController) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("slapdroid_prefs", Context.MODE_PRIVATE)
    
    var isEnabled by remember { mutableStateOf(prefs.getBoolean("${feature.id}_enabled", false)) }
    var selectedSound by remember { mutableStateOf(prefs.getString("${feature.id}_sound", "Default")) }
    var selectedAction by remember { mutableStateOf(prefs.getString("shake_gesture_action", "Play Sound") ?: "Play Sound") }

    val soundOptions = remember(selectedSound) {
        val options = mutableListOf("Default", "Sci-Fi Beep", "Scream 1", "Yelp", "Robot Connect")
        if (selectedSound?.startsWith("Custom:") == true) {
            options.add(selectedSound!!)
        }
        options.add("Choose from files...")
        options
    }

    var expanded by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val timeString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val time = timeString?.toLongOrNull() ?: 0L
                    if (time <= 5000L) {
                        var fileName = "custom_sound"
                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (nameIndex != -1) {
                                    fileName = cursor.getString(nameIndex)
                                }
                            }
                        }
                        val internalFile = File(context.filesDir, "${feature.id}_custom_audio")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            internalFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        val saveName = "Custom: $fileName"
                        withContext(Dispatchers.Main) {
                            selectedSound = saveName
                            prefs.edit().putString("${feature.id}_sound", saveName).apply()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Sound must be under 5 seconds", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Error reading audio file", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    try {
                        retriever.release()
                    } catch (e: Exception) {}
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Text(
                        feature.title,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack, 
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(32.dp))
                    .border(2.dp, OutlineColor, RoundedCornerShape(32.dp))
                    .background(ActionBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = feature.icon,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = PrimaryBlack
                )
            }
            
            Text(
                text = feature.description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "STATUS", 
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { checked ->
                                if (feature.id == "air_gestures" && checked) {
                                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                                    val componentName = android.content.ComponentName(context, SlapDroidAdmin::class.java)
                                    if (!dpm.isAdminActive(componentName)) {
                                        val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                            putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, componentName)
                                            putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required to lock screen via air gestures")
                                        }
                                        context.startActivity(intent)
                                    }
                                }
                                isEnabled = checked
                                prefs.edit().putBoolean("${feature.id}_enabled", checked).apply()
                                
                                // Restart gesture service
                                try {
                                    val serviceClass = when(feature.id) {
                                        "call_gestures" -> CallGestureService::class.java
                                        "air_gestures" -> AirGestureService::class.java
                                        else -> SlapDetectionService::class.java
                                    }
                                    val serviceIntent = Intent(context, serviceClass)
                                    androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                                } catch (e: Exception) {}
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.background,
                                checkedTrackColor = MaterialTheme.colorScheme.secondary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                uncheckedTrackColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
            }

            if (feature.id == "battery_announce") {
                var chargerSoundEnabled by remember { mutableStateOf(prefs.getBoolean("charger_noises_enabled", false)) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "POWER CONNECT/DISCONNECT SOUND", 
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Play a custom sound when connecting or disconnecting power", 
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Switch(
                            checked = chargerSoundEnabled,
                            onCheckedChange = { checked ->
                                chargerSoundEnabled = checked
                                prefs.edit().putBoolean("charger_noises_enabled", checked).apply()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.background,
                                checkedTrackColor = MaterialTheme.colorScheme.secondary,
                                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                uncheckedTrackColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    }
                }
                
                if (chargerSoundEnabled) {
                    var localSelectedSound by remember { mutableStateOf(prefs.getString("charger_noises_sound", "Default")) }
                    var localExpanded by remember { mutableStateOf(false) }
                    
                    val localSoundOptions = remember(localSelectedSound) {
                        val options = mutableListOf("Default", "Sci-Fi Beep", "Robot Connect")
                        if (localSelectedSound?.startsWith("Custom:") == true) {
                            options.add(localSelectedSound!!)
                        }
                        options.add("Choose from files...")
                        options
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                        Text(
                            text = "AUDIO SIGNATURE", 
                            style = MaterialTheme.typography.labelLarge,
                            color = PrimaryBlack,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        
                        @OptIn(ExperimentalMaterial3Api::class)
                        ExposedDropdownMenuBox(
                            expanded = localExpanded,
                            onExpandedChange = { localExpanded = it },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = localSelectedSound ?: "Default",
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = localExpanded) },
                                modifier = Modifier
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                                    .fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = PrimaryBlack,
                                    unfocusedBorderColor = OutlineColor
                                ),
                                shape = RoundedCornerShape(12.dp)
                            )
                            ExposedDropdownMenu(
                                expanded = localExpanded,
                                onDismissRequest = { localExpanded = false },
                            ) {
                                localSoundOptions.forEach { selectionOption ->
                                    DropdownMenuItem(
                                        text = { Text(selectionOption) },
                                        onClick = {
                                            if (selectionOption == "Choose from files...") {
                                                localExpanded = false
                                                // Using the existing filePickerLauncher from parent scope
                                                filePickerLauncher.launch("audio/*")
                                            } else {
                                                localSelectedSound = selectionOption
                                                prefs.edit().putString("charger_noises_sound", selectionOption).apply()
                                                localExpanded = false
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (feature.id == "pocket_slap") {
                val volumeKey = "pocket_slap_volume"
                var volume by remember { mutableStateOf(prefs.getFloat(volumeKey, 1.0f)) }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                    ) {
                        Text(
                            text = "VOLUME", 
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeMute, 
                                contentDescription = "Low Volume",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Slider(
                                value = volume,
                                onValueChange = { 
                                    volume = it 
                                    prefs.edit().putFloat(volumeKey, it).apply()
                                },
                                modifier = Modifier.weight(1f),
                                valueRange = 0f..1f,
                                colors = SliderDefaults.colors(
                                    thumbColor = MaterialTheme.colorScheme.primary,
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp, 
                                contentDescription = "High Volume",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            if (feature.id == "shake_sound") {
                var expandedAction by remember { mutableStateOf(false) }
                val actions = listOf("Off", "Play Sound", "Toggle Flashlight")

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "SHAKE ACTION", 
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    
                    @OptIn(ExperimentalMaterial3Api::class)
                    ExposedDropdownMenuBox(
                        expanded = expandedAction,
                        onExpandedChange = { expandedAction = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedAction,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAction) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                                .fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = expandedAction,
                            onDismissRequest = { expandedAction = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            actions.forEach { action ->
                                DropdownMenuItem(
                                    text = { Text(action, color = MaterialTheme.colorScheme.onSurface) },
                                    onClick = {
                                        selectedAction = action
                                        prefs.edit().putString("shake_gesture_action", action).apply()
                                        expandedAction = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (selectedAction == "Play Sound") {
                    val volumeKey = "shake_sound_volume"
                    var volume by remember { mutableStateOf(prefs.getFloat(volumeKey, 1.0f)) }
                    Card(
                        modifier = Modifier.fillMaxWidth()
                            .padding(top = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                        ) {
                            Text(
                                text = "SOUND VOLUME", 
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeMute, 
                                    contentDescription = "Low Volume",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Slider(
                                    value = volume,
                                    onValueChange = { 
                                        volume = it 
                                        prefs.edit().putFloat(volumeKey, it).apply()
                                    },
                                    modifier = Modifier.weight(1f),
                                    valueRange = 0f..1f,
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary,
                                        inactiveTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.VolumeUp, 
                                    contentDescription = "High Volume",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                            
                        }
                    }
                }
            }

            if (feature.hasSoundSelection || (feature.id == "shake_sound" && selectedAction == "Play Sound")) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "AUDIO SIGNATURE", 
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    
                    @OptIn(ExperimentalMaterial3Api::class)
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedSound ?: "Default",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
                                .fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            soundOptions.forEach { selectionOption ->
                                DropdownMenuItem(
                                    text = { Text(selectionOption, color = MaterialTheme.colorScheme.onSurface) },
                                    onClick = {
                                        if (selectionOption == "Choose from files...") {
                                            expanded = false
                                            filePickerLauncher.launch("audio/*")
                                        } else {
                                            selectedSound = selectionOption
                                            prefs.edit().putString("${feature.id}_sound", selectionOption).apply()
                                            expanded = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
