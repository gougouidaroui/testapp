package com.example.testapp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.UserHandle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.WindowRecomposerFactory.Companion
import androidx.compose.ui.platform.compositionContext
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs

class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {
    private val NOTIFICATION_ID = 1 // Or any unique integer
    private val CHANNEL_ID = "OverlayServiceChannel"
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val notificationChannelId = "OverlayServiceChannel"

    // For LifecycleOwner
    private lateinit var lifecycleRegistry: LifecycleRegistry

    // For SavedStateRegistryOwner
    private lateinit var savedStateRegistryController: SavedStateRegistryController

    // Use repository instead of direct scanner
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var shortcutRepository: ShortcutRepository

    // Add gyroscope manager
    private lateinit var gyroscopeManager: GyroscopeManager

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        // Initialize preferences and repository
        preferencesManager = PreferencesManager(this)
        shortcutRepository = ShortcutRepository.getInstance(this)

        // Initialize gyroscope manager
        gyroscopeManager = GyroscopeManager(this)
        gyroscopeManager.setListener(object : GyroscopeManager.GyroscopeListener {
            override fun onGyroscopeMotion(gestureType: GestureType, intensity: Float) {
                Log.d("OverlayService", "Gyroscope motion detected: $gestureType (intensity: $intensity)")
                executeGestureAction(gestureType)
            }
        })

        // Load gyroscope sensitivity settings
        val sensitivity = preferencesManager.getGyroSensitivity()
        gyroscopeManager.setSensitivity(sensitivity)

        // Initialize LifecycleOwner
        lifecycleRegistry = LifecycleRegistry(this)
        savedStateRegistryController = SavedStateRegistryController.create(this)
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        Log.d("OverlayService", "OverlayService created successfully")
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel() // Ensure channel is created (Android 8.0+)
        val notification = createNotification() // Your method to build a Notification
        startForeground(NOTIFICATION_ID, notification)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        if (overlayView == null) {
            showOverlay()

            // Start gyroscope listening
            if (gyroscopeManager.isGyroscopeAvailable()) {
                gyroscopeManager.startListening()
                Log.d("OverlayService", "Gyroscope listening started")
            } else {
                Log.w("OverlayService", "Gyroscope not available on this device")
            }
        }
        return START_STICKY
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                notificationChannelId,
                "Overlay Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }
    private fun createNotification(): Notification {
        val gyroStatus = if (gyroscopeManager.isGyroscopeAvailable()) "with gyroscope" else "touch only"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Overlay Active")
            .setContentText("Use your configured gestures ($gyroStatus) to launch shortcuts.")
            .setSmallIcon(R.drawable.ic_notification) // Use the new notification icon
            .build()
    }
    private fun executeGestureAction(gestureType: GestureType) {
        val shortcutId = preferencesManager.getGestureMapping(gestureType)

        if (shortcutId == null) {
            Log.d("OverlayService", "No shortcut configured for $gestureType")
            // For gyroscope gestures, don't dismiss overlay if no shortcut is configured
            if (gestureType == GestureType.DOUBLE_TAP || gestureType == GestureType.DOUBLE_SWIPE_UP) {
                stopSelf()
            }
            return
        }

        // Get shortcut from repository (fast cache lookup)
        val shortcut = shortcutRepository.getShortcutById(shortcutId)
        if (shortcut == null) {
            Log.w("OverlayService", "Configured shortcut not found: $shortcutId")
            return // Don't dismiss for missing shortcuts
        }

        Log.d("OverlayService", "Executing ${gestureType.name} -> ${shortcut.label}")

        try {
            when (shortcut.type) {
                ShortcutType.DIRECT_LAUNCH -> {
                    launchApp(shortcut)
                }
                ShortcutType.APP_SHORTCUT -> {
                    launchAppShortcut(shortcut)
                }
                ShortcutType.LEGACY_SHORTCUT -> {
                    launchLegacyShortcut(shortcut)
                }
            }

            // Always dismiss overlay after successful action
            stopSelf()
        } catch (e: Exception) {
            Log.e("OverlayService", "Error executing gesture action", e)
        }
    }

    private fun launchApp(shortcut: AppShortcutInfo) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(shortcut.packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d("OverlayService", "Launched app: ${shortcut.label}")
            } else {
                Log.w("OverlayService", "No launch intent for package: ${shortcut.packageName}")
            }
        } catch (e: ActivityNotFoundException) {
            Log.e("OverlayService", "App not found: ${shortcut.packageName}", e)
        } catch (e: Exception) {
            Log.e("OverlayService", "Error launching app", e)
        }
    }

    private fun launchAppShortcut(shortcut: AppShortcutInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            try {
                val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

                val packageName = shortcut.packageName
                val shortcutIdFromSystem = shortcut.id.removePrefix("shortcut_${packageName}_")

                val queryFlags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED

                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                val shortcutInfoList = launcherApps.getShortcuts(
                    LauncherApps.ShortcutQuery()
                        .setPackage(packageName)
                        .setQueryFlags(queryFlags),
                    UserHandle.getUserHandleForUid(appInfo.uid)
                )

                val targetShortcut = shortcutInfoList?.find { it.id == shortcutIdFromSystem }
                if (targetShortcut != null) {
                    launcherApps.startShortcut(targetShortcut, null, null)
                    Log.d("OverlayService", "Launched app shortcut: ${shortcut.label}")
                } else {
                    Log.w("OverlayService", "App shortcut not found: $shortcutIdFromSystem")
                    launchApp(shortcut)
                }
            } catch (e: SecurityException) {
                Log.e("OverlayService", "Security exception launching shortcut, trying app launch", e)
                launchApp(shortcut)
            } catch (e: Exception) {
                Log.e("OverlayService", "Error launching app shortcut", e)
                launchApp(shortcut)
            }
        } else {
            launchApp(shortcut)
        }
    }

    private fun launchLegacyShortcut(shortcut: AppShortcutInfo) {
        try {
            shortcut.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(shortcut.intent)
            Log.d("OverlayService", "Launched legacy shortcut: ${shortcut.label}")
        } catch (e: ActivityNotFoundException) {
            Log.e("OverlayService", "Legacy shortcut not found", e)
            launchApp(shortcut)
        } catch (e: Exception) {
            Log.e("OverlayService", "Error launching legacy shortcut", e)
        }
    }

    // Keep all your existing UI code (showOverlay, OverlayScreen, detectDoubleSwipeUp)...
    @OptIn(InternalComposeUiApi::class)
    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        val context: Context = this
        overlayView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayService)
            setViewTreeSavedStateRegistryOwner(this@OverlayService)

            val recomposer = Companion.LifecycleAware.createRecomposer(this)
            compositionContext = recomposer

            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)

            setContent {
                OverlayScreen(
                    onDoubleTap = {
                        executeGestureAction(GestureType.DOUBLE_TAP)
                    },
                    onDoubleSwipeUp = {
                        executeGestureAction(GestureType.DOUBLE_SWIPE_UP)
                    }
                )
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        try {
            windowManager.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf()
        }
    }

    @Composable
    fun OverlayScreen(onDoubleTap: () -> Unit, onDoubleSwipeUp: () -> Unit) {
        val scope = rememberCoroutineScope()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            onDoubleTap()
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDoubleSwipeUp(
                        onDoubleSwipeUp = {
                            onDoubleSwipeUp()
                        }
                    )
                }
        )
    }

    // Keep your existing detectDoubleSwipeUp function...
    suspend fun PointerInputScope.detectDoubleSwipeUp(
        onDoubleSwipeUp: () -> Unit,
        swipeThresholdMillis: Long = 1000,
        minSwipeDistancePx: Float = 100f
    ) {
        var lastSwipeUpTime = 0L
        var swipeUpCount = 0

        coroutineScope {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var pointerId = down.id

                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)

                    var isPotentialSwipe = true
                    var dragConsumed = false

                    var currentEvent: PointerEvent = awaitPointerEvent()
                    while (isPotentialSwipe && currentEvent.changes.any { it.pressed && it.id == pointerId }) {
                        val change = currentEvent.changes.firstOrNull { it.id == pointerId }
                        if (change != null) {
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            val touchSlop = 1
                            if (abs(change.position.y - down.position.y) > touchSlop) {
                                change.consume()
                                dragConsumed = true
                            }
                        } else {
                            isPotentialSwipe = false
                        }
                        if(isPotentialSwipe) currentEvent = awaitPointerEvent() else break
                    }

                    if (dragConsumed) {
                        val (velocityX, velocityY) = velocityTracker.calculateVelocity()

                        if (velocityY < -minSwipeDistancePx && abs(velocityY) > abs(velocityX)) {
                            val currentTime = System.currentTimeMillis()
                            if (swipeUpCount == 0 || (currentTime - lastSwipeUpTime) < swipeThresholdMillis) {
                                swipeUpCount++
                                lastSwipeUpTime = currentTime
                                if (swipeUpCount == 2) {
                                    onDoubleSwipeUp()
                                    swipeUpCount = 0
                                    lastSwipeUpTime = 0L
                                }
                            } else {
                                swipeUpCount = 1
                                lastSwipeUpTime = currentTime
                            }
                        } else {
                            if (swipeUpCount == 1 && (System.currentTimeMillis() - lastSwipeUpTime) >= swipeThresholdMillis) {
                                swipeUpCount = 0
                                lastSwipeUpTime = 0L
                            }
                        }
                    } else {
                        if (swipeUpCount == 1 && (System.currentTimeMillis() - lastSwipeUpTime) >= swipeThresholdMillis) {
                            swipeUpCount = 0
                            lastSwipeUpTime = 0L
                        }
                    }

                    currentEvent.changes.forEach { if (it.pressed) it.consume() }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Stop gyroscope listening
        gyroscopeManager.stopListening()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        overlayView?.let {
            (it as? ComposeView)?.disposeComposition()
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
}
