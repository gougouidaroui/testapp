package com.example.testapp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
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


class OverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner { // Implement LifecycleOwner and SavedStateRegistryOwner

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private val notificationChannelId = "OverlayServiceChannel"
    private val notificationId = 123

    // For LifecycleOwner
    private lateinit var lifecycleRegistry: LifecycleRegistry

    // For SavedStateRegistryOwner
    private lateinit var savedStateRegistryController: SavedStateRegistryController


    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry


    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()

        // Initialize LifecycleOwner
        lifecycleRegistry = LifecycleRegistry(this)
        savedStateRegistryController = SavedStateRegistryController.create(this)
        savedStateRegistryController.performRestore(null) // Restore state, null for new
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START) // Move to active state
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) // Services don't have a paused state like Activities

        if (overlayView == null) {
            showOverlay()
            startForeground(notificationId, createNotification())
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
        return NotificationCompat.Builder(this, notificationChannelId)
            .setContentTitle("Overlay Active")
            .setContentText("Double tap overlay to dismiss.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    private fun launchInstagram() {
        val instagramPackage = "com.instagram.android"
        val intent = packageManager.getLaunchIntentForPackage(instagramPackage)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                Log.d("OverlayService", "Launched Instagram app.")
            } catch (e: ActivityNotFoundException) {
                Log.e("OverlayService", "Instagram app not found, trying web.", e)
            }
        } else {
            Log.w("OverlayService", "Instagram package not found, trying web.")
        }
    }

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
                        stopSelf()
                    },
                    onDoubleSwipeUp = {
                        // Action for double swipe up
                        // For example, also stop the service or do something else
                        Log.d("OverlayService", "Double swipe up detected!")
                        launchInstagram()
                        stopSelf()
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
        val scope = rememberCoroutineScope() // For launching the gesture detector

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            onDoubleTap()
                        }
                        // You can keep other tap gestures if needed
                    )
                }
                .pointerInput(Unit) { // A separate pointerInput for the swipe gesture
                    detectDoubleSwipeUp(
                        onDoubleSwipeUp = {
                            onDoubleSwipeUp()
                        }
                    )
                }
        )
    }

    // Custom gesture detector for double swipe up
    suspend fun PointerInputScope.detectDoubleSwipeUp(
        onDoubleSwipeUp: () -> Unit,
        swipeThresholdMillis: Long = 1000, // Max time between swipes for it to be "double"
        minSwipeDistancePx: Float = 100f // Min distance for a swipe to be considered
    ) {
        var lastSwipeUpTime = 0L
        var swipeUpCount = 0

        coroutineScope { // Use coroutineScope to manage child jobs
            awaitPointerEventScope {
                while (true) { // Loop to continuously listen for pointer events
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var pointerId = down.id

                    // Velocity tracker to determine swipe direction and speed
                    val velocityTracker = VelocityTracker()
                    velocityTracker.addPosition(down.uptimeMillis, down.position)

                    var isPotentialSwipe = true
                    var dragConsumed = false

                    // Main drag loop
                    var currentEvent: PointerEvent = awaitPointerEvent()
                    while (isPotentialSwipe && currentEvent.changes.any { it.pressed && it.id == pointerId }) {
                        val change = currentEvent.changes.firstOrNull { it.id == pointerId }
                        if (change != null) {
                            velocityTracker.addPosition(change.uptimeMillis, change.position)
                            // Consume change to prevent other gestures if needed
                            val touchSlop = 1
                            if (abs(change.position.y - down.position.y) > touchSlop) { // touchSlop can be viewConfiguration.touchSlop
                                change.consume()
                                dragConsumed = true
                            }
                        } else {
                            isPotentialSwipe = false // Lost the pointer we were tracking
                        }
                        if(isPotentialSwipe) currentEvent = awaitPointerEvent() else break
                    }


                    if (dragConsumed) { // Only process if some dragging occurred
                        val (velocityX, velocityY) = velocityTracker.calculateVelocity()

                        // Check for swipe up
                        // Negative velocityY means upward movement
                        // Ensure y-velocity is dominant over x-velocity for a clear "up" swipe
                        if (velocityY < -minSwipeDistancePx && abs(velocityY) > abs(velocityX)) {
                            val currentTime = System.currentTimeMillis()
                            if (swipeUpCount == 0 || (currentTime - lastSwipeUpTime) < swipeThresholdMillis) {
                                swipeUpCount++
                                lastSwipeUpTime = currentTime
                                if (swipeUpCount == 2) {
                                    onDoubleSwipeUp()
                                    swipeUpCount = 0 // Reset for next double swipe
                                    lastSwipeUpTime = 0L
                                }
                            } else {
                                // First swipe of a potential double swipe, or time expired
                                swipeUpCount = 1
                                lastSwipeUpTime = currentTime
                            }
                        } else {
                            // Not a swipe up or not strong enough, reset if it was part of a sequence
                            if (swipeUpCount == 1 && (System.currentTimeMillis() - lastSwipeUpTime) >= swipeThresholdMillis) {
                                swipeUpCount = 0
                                lastSwipeUpTime = 0L
                            }
                        }
                    } else {
                        // No significant drag, reset if it was part of a sequence
                        if (swipeUpCount == 1 && (System.currentTimeMillis() - lastSwipeUpTime) >= swipeThresholdMillis) {
                            swipeUpCount = 0
                            lastSwipeUpTime = 0L
                        }
                    }

                    // If any pointers are still down, consume them to prevent interference
                    // This part might need adjustment based on desired interaction
                    currentEvent.changes.forEach { if (it.pressed) it.consume() }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) // Services don't have a paused state
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)

        overlayView?.let {
            // Important: Dispose the composition before removing the view
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