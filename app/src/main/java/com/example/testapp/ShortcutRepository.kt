package com.example.testapp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class ShortcutRepository private constructor(private val context: Context) {
    companion object {
        @Volatile
        private var INSTANCE: ShortcutRepository? = null

        fun getInstance(context: Context): ShortcutRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ShortcutRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val shortcutScanner = ShortcutScanner(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Cache for shortcuts
    private val shortcutsCache = ConcurrentHashMap<String, AppShortcutInfo>()
    private var lastScanTime = 0L
    private val cacheValidityMs = 5 * 60 * 1000L // 5 minutes

    // State flows for UI
    private val _shortcuts = MutableStateFlow<List<AppShortcutInfo>>(emptyList())
    val shortcuts: StateFlow<List<AppShortcutInfo>> = _shortcuts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        // Load shortcuts from cache if available
        loadShortcutsFromCache()
    }

    fun getShortcuts(): StateFlow<List<AppShortcutInfo>> {
        if (shouldRefreshCache()) {
            refreshShortcuts()
        }
        return shortcuts
    }

    fun getShortcutById(id: String): AppShortcutInfo? {
        return shortcutsCache[id]
    }

    fun refreshShortcuts() {
        if (_isLoading.value) {
            Log.d("ShortcutRepository", "Already loading shortcuts, skipping")
            return
        }

        scope.launch {
            try {
                _isLoading.value = true
                _error.value = null

                Log.d("ShortcutRepository", "Starting background shortcut scan...")
                val startTime = System.currentTimeMillis()

                // Scan shortcuts in background
                val newShortcuts = withContext(Dispatchers.IO) {
                    shortcutScanner.getAllAvailableShortcuts()
                }

                val endTime = System.currentTimeMillis()
                Log.d("ShortcutRepository", "Shortcut scan completed in ${endTime - startTime}ms, found ${newShortcuts.size} shortcuts")

                // Update cache
                shortcutsCache.clear()
                newShortcuts.forEach { shortcut ->
                    shortcutsCache[shortcut.id] = shortcut
                }

                // Update state
                _shortcuts.value = newShortcuts
                lastScanTime = System.currentTimeMillis()

                // Save to persistent cache
                saveShortcutsToCache(newShortcuts)

            } catch (e: Exception) {
                Log.e("ShortcutRepository", "Error loading shortcuts", e)
                _error.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun shouldRefreshCache(): Boolean {
        return System.currentTimeMillis() - lastScanTime > cacheValidityMs || shortcutsCache.isEmpty()
    }

    private fun loadShortcutsFromCache() {
        // For now, just trigger a refresh
        // In a production app, you might want to implement persistent caching
        scope.launch {
            delay(100) // Small delay to avoid blocking app startup
            refreshShortcuts()
        }
    }

    private fun saveShortcutsToCache(shortcuts: List<AppShortcutInfo>) {
        // Implementation for persistent caching if needed
        // For now, we just keep them in memory
    }

    fun cleanup() {
        scope.cancel()
    }
}
