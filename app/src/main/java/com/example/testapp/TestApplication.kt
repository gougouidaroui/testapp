package com.example.testapp

import android.app.Application

class TestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize repository early
        ShortcutRepository.getInstance(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        // Cleanup repository
        ShortcutRepository.getInstance(this).cleanup()
    }
}
