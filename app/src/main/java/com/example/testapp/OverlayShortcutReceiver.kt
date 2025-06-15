package com.example.testapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class OverlayShortcutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("OverlayShortcutReceiver", "Received intent: ${intent.action}")

        if (intent.action == "com.example.testapp.ACTION_SHOW_OVERLAY") {
            // Start the OverlayService
            val serviceIntent = Intent(context, OverlayService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d("OverlayShortcutReceiver", "Overlay service started successfully")
            } catch (e: Exception) {
                Log.e("OverlayShortcutReceiver", "Error starting overlay service", e)
            }
        }
    }
}
