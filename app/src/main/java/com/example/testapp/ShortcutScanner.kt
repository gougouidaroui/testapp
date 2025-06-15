package com.example.testapp

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.UserHandle
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class ShortcutScanner(private val context: Context) {

    suspend fun getAllAvailableShortcuts(): List<AppShortcutInfo> = withContext(Dispatchers.IO) {
        val shortcuts = mutableListOf<AppShortcutInfo>()

        try {
            Log.d("ShortcutScanner", "=== Starting comprehensive app and shortcut scan ===")

            // 1. Get ALL launchable apps (main priority)
            val launchableApps = getAllLaunchableApps()
            Log.d("ShortcutScanner", "Found ${launchableApps.size} launchable apps")
            shortcuts.addAll(launchableApps)
            yield() // Allow other coroutines to run

            // 2. Get legacy shortcuts
            val legacyShortcuts = getLegacyShortcuts()
            Log.d("ShortcutScanner", "Found ${legacyShortcuts.size} legacy shortcuts")
            shortcuts.addAll(legacyShortcuts)
            yield()

            // 3. Get modern app shortcuts (if available)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                val appShortcuts = getAppShortcuts()
                Log.d("ShortcutScanner", "Found ${appShortcuts.size} app shortcuts")
                shortcuts.addAll(appShortcuts)
            }
            yield()

            // Remove duplicates efficiently by ID
            val uniqueShortcuts = shortcuts.distinctBy { it.id }
            Log.d("ShortcutScanner", "=== Scan complete: ${uniqueShortcuts.size} total shortcuts ===")

            uniqueShortcuts

        } catch (e: Exception) {
            Log.e("ShortcutScanner", "Error scanning shortcuts", e)
            emptyList()
        }
    }

    private suspend fun getAllLaunchableApps(): List<AppShortcutInfo> = withContext(Dispatchers.IO) {
        val shortcuts = mutableListOf<AppShortcutInfo>()
        val packageManager = context.packageManager

        try {
            // Method 1: Query using MAIN/LAUNCHER intent
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
            Log.d("ShortcutScanner", "Found ${resolveInfos.size} activities with MAIN/LAUNCHER")

            for (resolveInfo in resolveInfos) {
                try {
                    val packageName = resolveInfo.activityInfo.packageName
                    val appInfo = resolveInfo.activityInfo.applicationInfo

                    // Get launch intent
                    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

                    if (launchIntent != null) {
                        // Get app icon and label
                        val icon: Drawable? = try {
                            resolveInfo.loadIcon(packageManager)
                        } catch (e: Exception) {
                            Log.w("ShortcutScanner", "Failed to load icon for $packageName", e)
                            null
                        }

                        val label = try {
                            resolveInfo.loadLabel(packageManager).toString()
                        } catch (e: Exception) {
                            packageName // Fallback to package name
                        }

                        shortcuts.add(
                            AppShortcutInfo(
                                id = "app_$packageName",
                                packageName = packageName,
                                label = label,
                                icon = icon,
                                intent = launchIntent,
                                type = ShortcutType.DIRECT_LAUNCH
                            )
                        )
                    }

                    // Yield every 20 apps to prevent blocking
                    if (shortcuts.size % 20 == 0) {
                        yield()
                    }

                } catch (e: Exception) {
                    Log.w("ShortcutScanner", "Error processing app: ${resolveInfo.activityInfo?.packageName}", e)
                }
            }

            // Method 2: Also get installed apps that might not have LAUNCHER category
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            val existingPackages = shortcuts.map { it.packageName }.toSet()

            for (appInfo in installedApps) {
                try {
                    // Skip if we already have this app
                    if (existingPackages.contains(appInfo.packageName)) continue

                    // Skip system apps that aren't updated (to reduce clutter)
                    val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isUpdatedSystemApp = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                    if (isSystemApp && !isUpdatedSystemApp) continue

                    // Try to get launch intent
                    val launchIntent = packageManager.getLaunchIntentForPackage(appInfo.packageName)

                    if (launchIntent != null) {
                        val icon: Drawable? = try {
                            appInfo.loadIcon(packageManager)
                        } catch (e: Exception) {
                            null
                        }

                        val label = try {
                            appInfo.loadLabel(packageManager).toString()
                        } catch (e: Exception) {
                            appInfo.packageName
                        }

                        shortcuts.add(
                            AppShortcutInfo(
                                id = "app_${appInfo.packageName}",
                                packageName = appInfo.packageName,
                                label = label,
                                icon = icon,
                                intent = launchIntent,
                                type = ShortcutType.DIRECT_LAUNCH
                            )
                        )
                    }

                    if (shortcuts.size % 20 == 0) {
                        yield()
                    }

                } catch (e: Exception) {
                    Log.w("ShortcutScanner", "Error processing installed app: ${appInfo.packageName}", e)
                }
            }

        } catch (e: Exception) {
            Log.e("ShortcutScanner", "Error getting launchable apps", e)
        }

        Log.d("ShortcutScanner", "Collected ${shortcuts.size} launchable apps")
        shortcuts
    }

    private suspend fun getLegacyShortcuts(): List<AppShortcutInfo> = withContext(Dispatchers.IO) {
        val shortcuts = mutableListOf<AppShortcutInfo>()
        val packageManager = context.packageManager

        try {
            val shortcutsIntent = Intent(Intent.ACTION_CREATE_SHORTCUT)
            val resolveInfos = packageManager.queryIntentActivities(shortcutsIntent, 0)

            for (resolveInfo in resolveInfos) {
                try {
                    val label = resolveInfo.loadLabel(packageManager).toString()
                    val icon = try {
                        resolveInfo.loadIcon(packageManager)
                    } catch (e: Exception) {
                        null
                    }

                    val intent = Intent().apply {
                        component = resolveInfo.activityInfo.let {
                            android.content.ComponentName(it.packageName, it.name)
                        }
                        action = Intent.ACTION_CREATE_SHORTCUT
                    }

                    shortcuts.add(
                        AppShortcutInfo(
                            id = "legacy_${resolveInfo.activityInfo.packageName}_${resolveInfo.activityInfo.name}",
                            packageName = resolveInfo.activityInfo.packageName,
                            label = label,
                            icon = icon,
                            intent = intent,
                            type = ShortcutType.LEGACY_SHORTCUT
                        )
                    )

                    // Yield periodically
                    if (shortcuts.size % 10 == 0) {
                        yield()
                    }

                } catch (e: Exception) {
                    Log.w("ShortcutScanner", "Error loading legacy shortcut", e)
                }
            }
        } catch (e: Exception) {
            Log.e("ShortcutScanner", "Error getting legacy shortcuts", e)
        }

        shortcuts
    }

    @RequiresApi(Build.VERSION_CODES.N_MR1)
    private suspend fun getAppShortcuts(): List<AppShortcutInfo> = withContext(Dispatchers.IO) {
        val shortcuts = mutableListOf<AppShortcutInfo>()

        try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val packageManager = context.packageManager

            // Get all installed apps (both user and system)
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)

            for (appInfo in installedApps) {
                try {
                    val queryFlags = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED

                    val shortcutInfoList = launcherApps.getShortcuts(
                        LauncherApps.ShortcutQuery()
                            .setPackage(appInfo.packageName)
                            .setQueryFlags(queryFlags),
                        UserHandle.getUserHandleForUid(appInfo.uid)
                    )

                    if (shortcutInfoList != null) {
                        for (shortcutInfo in shortcutInfoList) {
                            if (shortcutInfo.isEnabled) {
                                val icon: Drawable? = try {
                                    launcherApps.getShortcutIconDrawable(shortcutInfo,
                                        context.resources.displayMetrics.densityDpi)
                                } catch (e: Exception) {
                                    null
                                }

                                shortcuts.add(
                                    AppShortcutInfo(
                                        id = "shortcut_${appInfo.packageName}_${shortcutInfo.id}",
                                        packageName = appInfo.packageName,
                                        label = shortcutInfo.shortLabel?.toString() ?: "Unknown",
                                        longLabel = shortcutInfo.longLabel?.toString(),
                                        icon = icon,
                                        intent = shortcutInfo.intent ?: Intent(),
                                        type = ShortcutType.APP_SHORTCUT
                                    )
                                )
                            }
                        }
                    }

                    // Yield every few apps
                    yield()

                } catch (e: SecurityException) {
                    // Expected for apps without launcher permission - skip silently
                } catch (e: Exception) {
                    Log.w("ShortcutScanner", "Error accessing shortcuts for ${appInfo.packageName}", e)
                }
            }
        } catch (e: Exception) {
            Log.e("ShortcutScanner", "Error accessing LauncherApps service", e)
        }

        shortcuts
    }
}