package com.example.testapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class OverlayShortcutTrampolineActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create an explicit Intent for the BroadcastReceiver
        val broadcastIntent = Intent(this, OverlayShortcutReceiver::class.java).apply {
            action = "com.example.testapp.ACTION_SHOW_OVERLAY"
            // If you need to pass data from the shortcut to the receiver,
            // you can get it from this Activity's intent and put it here.
            // For example: intent.getStringExtra("some_key")
        }
        sendBroadcast(broadcastIntent)

        finish() // Immediately close this trampoline activity
    }
}