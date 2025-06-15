package com.example.testapp

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class ShortcutRepository private constructor(private val context: Context) {
    companion object {
        @Volatile
        private var INSTANCE: ShortcutRepository? = null

        private const val PREFS_NAME = "shortcut_cache"
        private const val KEY_SHORTCUTS = "cached_shortcuts"
        private const val KEY_LAST_SCAN = "last_scan_time"

        fun getInstance(context: Context): ShortcutRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ShortcutRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val shortcutScanner = ShortcutScanner(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cachePrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Cache for shortcuts
    private val shortcutsCache = ConcurrentHashMap<String, AppShortcutInfo>()
    private var lastScanTime = 0L
    private val cacheValidityMs = 30 * 60 * 1000L // 30 minutes (increased for better performance)

    // State flows for UI
    private val _shortcuts = MutableStateFlow<List<AppShortcutInfo>>(emptyList())
    val shortcuts: StateFlow<List<AppShortcutInfo>> = _shortcuts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        // Load shortcuts from persistent cache first
        loadShortcutsFromPersistentCache()
    }

    fun fetchShortcuts(): StateFlow<List<AppShortcutInfo>> {
        // If cache is empty or expired, refresh in background
        if (shouldRefreshCache() && !_isLoading.value) {
            scope.launch {
                refreshShortcuts()
            }
        }
        return shortcuts
    }

    fun getShortcutById(id: String): AppShortcutInfo? {
        return shortcutsCache[id]
    }

    private suspend fun refreshShortcuts() {
        if (_isLoading.value) {
            Log.d("ShortcutRepository", "Already loading shortcuts, skipping")
            return
        }

        try {
            _isLoading.value = true
            _error.value = null

            Log.d("ShortcutRepository", "Starting background shortcut scan...")
            val startTime = System.currentTimeMillis()

            // Scan shortcuts in background
            val newShortcuts = shortcutScanner.getAllAvailableShortcuts()

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
            saveShortcutsToPersistentCache(newShortcuts)

        } catch (e: Exception) {
            Log.e("ShortcutRepository", "Error loading shortcuts", e)
            _error.value = e.message
        } finally {
            _isLoading.value = false
        }
    }

    private fun shouldRefreshCache(): Boolean {
        return System.currentTimeMillis() - lastScanTime > cacheValidityMs || shortcutsCache.isEmpty()
    }

    private fun loadShortcutsFromPersistentCache() {
        scope.launch {
            try {
                val cachedJson = cachePrefs.getString(KEY_SHORTCUTS, null)
                lastScanTime = cachePrefs.getLong(KEY_LAST_SCAN, 0L)

                if (cachedJson != null && !shouldRefreshCache()) {
                    Log.d("ShortcutRepository", "Loading shortcuts from persistent cache")
                    val cachedShortcuts = parseShortcutsFromJson(cachedJson)

                    // Update memory cache
                    shortcutsCache.clear()
                    cachedShortcuts.forEach { shortcut ->
                        shortcutsCache[shortcut.id] = shortcut
                    }

                    // Update state
                    _shortcuts.value = cachedShortcuts
                    Log.d("ShortcutRepository", "Loaded ${cachedShortcuts.size} shortcuts from cache")
                } else {
                    Log.d("ShortcutRepository", "Cache expired or empty, will refresh")
                }

                // Always do a background refresh if cache is old
                if (shouldRefreshCache()) {
                    delay(500) // Small delay to let UI load first
                    refreshShortcuts()
                }

            } catch (e: Exception) {
                Log.e("ShortcutRepository", "Error loading from persistent cache", e)
                // Fallback to refresh
                refreshShortcuts()
            }
        }
    }

    private fun saveShortcutsToPersistentCache(shortcuts: List<AppShortcutInfo>) {
        scope.launch {
            try {
                val jsonArray = JSONArray()

                shortcuts.forEach { shortcut ->
                    val jsonObject = JSONObject().apply {
                        put("id", shortcut.id)
                        put("packageName", shortcut.packageName)
                        put("label", shortcut.label)
                        put("longLabel", shortcut.longLabel ?: "")
                        put("type", shortcut.type.name)
                        // Don't cache icons or intents - they can cause issues and take up space
                    }
                    jsonArray.put(jsonObject)
                }

                cachePrefs.edit()
                    .putString(KEY_SHORTCUTS, jsonArray.toString())
                    .putLong(KEY_LAST_SCAN, lastScanTime)
                    .apply()

                Log.d("ShortcutRepository", "Saved ${shortcuts.size} shortcuts to persistent cache")

            } catch (e: Exception) {
                Log.e("ShortcutRepository", "Error saving to persistent cache", e)
            }
        }
    }

    private fun parseShortcutsFromJson(json: String): List<AppShortcutInfo> {
        val shortcuts = mutableListOf<AppShortcutInfo>()

        try {
            val jsonArray = JSONArray(json)

            for (i in 0 until jsonArray.length()) {
                val jsonObject = jsonArray.getJSONObject(i)

                val shortcut = AppShortcutInfo(
                    id = jsonObject.getString("id"),
                    packageName = jsonObject.getString("packageName"),
                    label = jsonObject.getString("label"),
                    longLabel = jsonObject.getString("longLabel").takeIf { it.isNotEmpty() },
                    icon = null, // Will be loaded fresh when needed
                    intent = context.packageManager.getLaunchIntentForPackage(jsonObject.getString("packageName"))
                        ?: android.content.Intent(), // Fallback intent
                    type = ShortcutType.valueOf(jsonObject.getString("type"))
                )

                shortcuts.add(shortcut)
            }

        } catch (e: Exception) {
            Log.e("ShortcutRepository", "Error parsing shortcuts from JSON", e)
        }

        return shortcuts
    }

    fun clearCache() {
        scope.launch {
            cachePrefs.edit().clear().apply()
            shortcutsCache.clear()
            _shortcuts.value = emptyList()
            lastScanTime = 0L
            refreshShortcuts()
        }
    }

    fun cleanup() {
        scope.cancel()
    }
}