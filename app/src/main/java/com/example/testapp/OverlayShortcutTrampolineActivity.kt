package com.example.testapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

class OverlayShortcutTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("TrampolineActivity", "Shortcut activated, sending broadcast")

        // Create an explicit Intent for the BroadcastReceiver
        val broadcastIntent = Intent(this, OverlayShortcutReceiver::class.java).apply {
            action = "com.example.testapp.ACTION_SHOW_OVERLAY"
            // You can pass additional data here if needed
            // For example: putExtra("shortcut_source", "app_shortcut")
        }

        try {
            sendBroadcast(broadcastIntent)
            Log.d("TrampolineActivity", "Broadcast sent successfully")
        } catch (e: Exception) {
            Log.e("TrampolineActivity", "Error sending broadcast", e)
        }

        finish() // Immediately close this trampoline activity
    }
}
