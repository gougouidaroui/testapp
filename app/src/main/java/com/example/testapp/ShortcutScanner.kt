package com.example.testapp

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutInfo
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
            // Get all shortcuts in parallel
            val launchableApps = getLaunchableApps()
            yield() // Allow other coroutines to run

            val legacyShortcuts = getLegacyShortcuts()
            yield()

            val appShortcuts = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                getAppShortcuts()
            } else {
                emptyList()
            }
            yield()

            // Combine all shortcuts
            shortcuts.addAll(launchableApps)
            shortcuts.addAll(legacyShortcuts)
            shortcuts.addAll(appShortcuts)

            // Remove duplicates efficiently
            shortcuts.distinctBy { it.id }

        } catch (e: Exception) {
            Log.e("ShortcutScanner", "Error scanning shortcuts", e)
            emptyList()
        }
    }

    private suspend fun getLaunchableApps(): List<AppShortcutInfo> = withContext(Dispatchers.IO) {
        val shortcuts = mutableListOf<AppShortcutInfo>()
        val packageManager = context.packageManager

        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val apps = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)

            for (app in apps) {
                try {
                    val launchIntent = packageManager.getLaunchIntentForPackage(app.activityInfo.packageName)

                    if (launchIntent != null) {
                        shortcuts.add(
                            AppShortcutInfo(
                                id = "app_${app.activityInfo.packageName}",
                                packageName = app.activityInfo.packageName,
                                label = app.loadLabel(packageManager).toString(),
                                icon = app.loadIcon(packageManager),
                                intent = launchIntent,
                                type = ShortcutType.DIRECT_LAUNCH
                            )
                        )
                    }

                    // Yield periodically to avoid blocking
                    if (shortcuts.size % 10 == 0) {
                        yield()
                    }

                } catch (e: Exception) {
                    Log.w("ShortcutScanner", "Error loading app: ${app.activityInfo.packageName}", e)
                }
            }
        } catch (e: Exception) {
            Log.e("ShortcutScanner", "Error getting launchable apps", e)
        }

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
                            icon = resolveInfo.loadIcon(packageManager),
                            intent = intent,
                            type = ShortcutType.LEGACY_SHORTCUT
                        )
                    )

                    // Yield periodically
                    if (shortcuts.size % 5 == 0) {
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

            // Get only user apps to avoid system app issues
            val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || (it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }

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
                                shortcuts.add(
                                    AppShortcutInfo(
                                        id = "shortcut_${appInfo.packageName}_${shortcutInfo.id}",
                                        packageName = appInfo.packageName,
                                        label = shortcutInfo.shortLabel?.toString() ?: "Unknown",
                                        longLabel = shortcutInfo.longLabel?.toString(),
                                        icon = try {
                                            launcherApps.getShortcutIconDrawable(shortcutInfo,
                                                context.resources.displayMetrics.densityDpi)
                                        } catch (e: Exception) {
                                            null
                                        },
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
                    // Expected for apps without launcher permission
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
