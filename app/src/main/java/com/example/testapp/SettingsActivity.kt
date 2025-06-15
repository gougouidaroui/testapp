package com.example.testapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.testapp.ui.theme.TestappTheme

@OptIn(ExperimentalMaterial3Api::class)
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TestappTheme {
                SettingsScreen(
                    onBackPressed = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBackPressed: () -> Unit) {
    val context = LocalContext.current
    val preferencesManager = remember { PreferencesManager(context) }
    val shortcutRepository = remember { ShortcutRepository.getInstance(context) }
    val gyroscopeManager = remember { GyroscopeManager(context) }

    // Use StateFlow from repository - CHANGED METHOD NAME
    val shortcuts by shortcutRepository.shortcuts.collectAsState()
    val isLoading by shortcutRepository.isLoading.collectAsState()
    val error by shortcutRepository.error.collectAsState()

    var selectedGesture by remember { mutableStateOf<GestureType?>(null) }
    var currentMappings by remember { mutableStateOf(preferencesManager.getAllMappings()) }
    var showGyroSettings by remember { mutableStateOf(false) }
    var gyroSensitivity by remember { mutableStateOf(preferencesManager.getGyroSensitivity()) }
    var isGyroAvailable by remember { mutableStateOf(gyroscopeManager.isGyroscopeAvailable()) }

    // Trigger shortcut loading when screen opens
    LaunchedEffect(Unit) {
        shortcutRepository.fetchShortcuts() // This will load from cache first, then refresh in background
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (showGyroSettings) "Gyroscope Settings"
                        else if (selectedGesture != null) "Select Shortcut"
                        else "Gesture Settings"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            showGyroSettings -> showGyroSettings = false
                            selectedGesture != null -> selectedGesture = null
                            else -> onBackPressed()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!showGyroSettings && selectedGesture == null && isGyroAvailable) {
                        IconButton(onClick = { showGyroSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Gyroscope Settings")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        when {
            showGyroSettings -> {
                GyroscopeSettingsScreen(
                    sensitivity = gyroSensitivity,
                    onSensitivityChanged = { newSensitivity ->
                        gyroSensitivity = newSensitivity
                        preferencesManager.setGyroSensitivity(newSensitivity)
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }
            selectedGesture != null -> {
                ShortcutSelectionScreen(
                    gesture = selectedGesture!!,
                    shortcuts = shortcuts,
                    isLoading = isLoading,
                    onShortcutSelected = { shortcut ->
                        preferencesManager.setGestureMapping(selectedGesture!!, shortcut.id)
                        currentMappings = preferencesManager.getAllMappings()
                        selectedGesture = null
                    },
                    onBackPressed = { selectedGesture = null }
                )
            }
            else -> {
                GestureMappingScreen(
                    mappings = currentMappings,
                    shortcuts = shortcuts,
                    isGyroAvailable = isGyroAvailable,
                    onGestureClick = { gesture ->
                        selectedGesture = gesture
                    },
                    onClearMapping = { gesture ->
                        preferencesManager.clearGestureMapping(gesture)
                        currentMappings = preferencesManager.getAllMappings()
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

@Composable
fun GestureMappingScreen(
    mappings: Map<GestureType, String?>,
    shortcuts: List<AppShortcutInfo>,
    isGyroAvailable: Boolean,
    onGestureClick: (GestureType) -> Unit,
    onClearMapping: (GestureType) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "Configure Gesture Shortcuts",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tap on a gesture to assign a shortcut, or clear existing mappings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!isGyroAvailable) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "⚠️ Gyroscope not available on this device. Only touch gestures will work.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Touch Gestures Section
        item {
            Text(
                text = "Touch Gestures",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        items(listOf(GestureType.DOUBLE_TAP, GestureType.DOUBLE_SWIPE_UP)) { gesture ->
            GestureMappingCard(
                gesture = gesture,
                mappedShortcut = mappings[gesture]?.let { mappingId ->
                    shortcuts.find { it.id == mappingId }
                },
                onClick = { onGestureClick(gesture) },
                onClear = { onClearMapping(gesture) },
                isEnabled = true
            )
        }

        // Gyroscope Gestures Section
        if (isGyroAvailable) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Gyroscope Gestures",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = "Configure sensitivity in gyroscope settings (⚙️ button above)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val gyroGestures = listOf(
            GestureType.GYRO_X_POSITIVE,
            GestureType.GYRO_X_NEGATIVE,
            GestureType.GYRO_Y_POSITIVE,
            GestureType.GYRO_Y_NEGATIVE,
            GestureType.GYRO_Z_POSITIVE,
            GestureType.GYRO_Z_NEGATIVE
        )

        items(gyroGestures) { gesture ->
            GestureMappingCard(
                gesture = gesture,
                mappedShortcut = mappings[gesture]?.let { mappingId ->
                    shortcuts.find { it.id == mappingId }
                },
                onClick = { onGestureClick(gesture) },
                onClear = { onClearMapping(gesture) },
                isEnabled = isGyroAvailable
            )
        }
    }
}

@Composable
fun GestureMappingCard(
    gesture: GestureType,
    mappedShortcut: AppShortcutInfo?,
    onClick: () -> Unit,
    onClear: () -> Unit,
    isEnabled: Boolean = true
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isEnabled) { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = if (isEnabled) 4.dp else 1.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = getGestureDisplayName(gesture),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isEnabled)
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Text(
                    text = getGestureDescription(gesture),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isEnabled)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 2.dp)
                )

                Text(
                    text = mappedShortcut?.label ?: "Not assigned",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (mappedShortcut != null && isEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (mappedShortcut != null && isEnabled) {
                mappedShortcut.icon?.let { icon ->
                    Image(
                        painter = BitmapPainter(icon.toBitmap().asImageBitmap()),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onClear) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = "Clear mapping",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun GyroscopeSettingsScreen(
    sensitivity: GyroSensitivity,
    onSensitivityChanged: (GyroSensitivity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Gyroscope Sensitivity",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Adjust the sensitivity for each axis. Lower values = more sensitive (easier to trigger). Higher values = less sensitive (harder to trigger).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            SensitivitySlider(
                title = "X-Axis Sensitivity",
                description = "Rotation around X-axis (pitch - phone tilting forward/backward)",
                value = sensitivity.xSensitivity,
                onValueChange = { newValue ->
                    onSensitivityChanged(sensitivity.copy(xSensitivity = newValue))
                }
            )
        }

        item {
            SensitivitySlider(
                title = "Y-Axis Sensitivity",
                description = "Rotation around Y-axis (roll - phone tilting left/right)",
                value = sensitivity.ySensitivity,
                onValueChange = { newValue ->
                    onSensitivityChanged(sensitivity.copy(ySensitivity = newValue))
                }
            )
        }

        item {
            SensitivitySlider(
                title = "Z-Axis Sensitivity",
                description = "Rotation around Z-axis (yaw - phone spinning like a compass)",
                value = sensitivity.zSensitivity,
                onValueChange = { newValue ->
                    onSensitivityChanged(sensitivity.copy(zSensitivity = newValue))
                }
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "💡 Tips",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Start with default values (2.0) and adjust as needed\n" +
                                "• Test your gestures after making changes\n" +
                                "• Lower values may cause accidental triggers\n" +
                                "• Higher values require more deliberate movements",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
fun SensitivitySlider(
    title: String,
    description: String,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Sensitive",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Slider(
                    value = value,
                    onValueChange = onValueChange,
                    valueRange = 0.5f..5.0f,
                    steps = 18, // 0.5 to 5.0 in steps of 0.25
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                )

                Text(
                    text = "Less Sensitive",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "Current: ${String.format("%.1f", value)} rad/s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

// Keep your existing ShortcutSelectionScreen and ShortcutItem composables...
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutSelectionScreen(
    gesture: GestureType,
    shortcuts: List<AppShortcutInfo>,
    isLoading: Boolean,
    onShortcutSelected: (AppShortcutInfo) -> Unit,
    onBackPressed: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Select Shortcut for ${getGestureDisplayName(gesture)}")
                },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(shortcuts.sortedBy { it.label }) { shortcut ->
                    ShortcutItem(
                        shortcut = shortcut,
                        onClick = { onShortcutSelected(shortcut) }
                    )
                }
            }
        }
    }
}

@Composable
fun ShortcutItem(
    shortcut: AppShortcutInfo,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            shortcut.icon?.let { icon ->
                Image(
                    painter = BitmapPainter(icon.toBitmap().asImageBitmap()),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = shortcut.label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${shortcut.type.name.replace("_", " ")} • ${shortcut.packageName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Helper functions for gesture display names
fun getGestureDisplayName(gesture: GestureType): String {
    return when (gesture) {
        GestureType.DOUBLE_TAP -> "Double Tap"
        GestureType.DOUBLE_SWIPE_UP -> "Double Swipe Up"
        GestureType.GYRO_X_POSITIVE -> "X-Axis Forward"
        GestureType.GYRO_X_NEGATIVE -> "X-Axis Backward"
        GestureType.GYRO_Y_POSITIVE -> "Y-Axis Right"
        GestureType.GYRO_Y_NEGATIVE -> "Y-Axis Left"
        GestureType.GYRO_Z_POSITIVE -> "Z-Axis Clockwise"
        GestureType.GYRO_Z_NEGATIVE -> "Z-Axis Counter-Clockwise"
    }
}

fun getGestureDescription(gesture: GestureType): String {
    return when (gesture) {
        GestureType.DOUBLE_TAP -> "Tap the screen twice quickly"
        GestureType.DOUBLE_SWIPE_UP -> "Swipe up twice quickly"
        GestureType.GYRO_X_POSITIVE -> "Tilt phone forward (top down)"
        GestureType.GYRO_X_NEGATIVE -> "Tilt phone backward (top up)"
        GestureType.GYRO_Y_POSITIVE -> "Tilt phone right"
        GestureType.GYRO_Y_NEGATIVE -> "Tilt phone left"
        GestureType.GYRO_Z_POSITIVE -> "Rotate phone clockwise"
        GestureType.GYRO_Z_NEGATIVE -> "Rotate phone counter-clockwise"
    }
}
