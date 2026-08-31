package com.mekromn.taskmanager.data

import android.content.Context

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("task_manager_settings", Context.MODE_PRIVATE)

    var themeMode: ThemeMode
        get() = prefs.getString(KEY_THEME, ThemeMode.DARK.name)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.DARK
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    var refreshIntervalMs: Long
        get() = prefs.getLong(KEY_REFRESH_MS, 2_000L)
        set(value) = prefs.edit().putLong(KEY_REFRESH_MS, value).apply()

    var autoRefresh: Boolean
        get() = prefs.getBoolean(KEY_AUTO_REFRESH, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_REFRESH, value).apply()

    var accessPreference: AccessPreference
        get() = prefs.getString(KEY_ACCESS, AccessPreference.AUTO.name)
            ?.let { runCatching { AccessPreference.valueOf(it) }.getOrNull() }
            ?: AccessPreference.AUTO
        set(value) = prefs.edit().putString(KEY_ACCESS, value.name).apply()

    var rootEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROOT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ROOT_ENABLED, value).apply()

    companion object {
        private const val KEY_THEME = "theme"
        private const val KEY_REFRESH_MS = "refresh_ms"
        private const val KEY_AUTO_REFRESH = "auto_refresh"
        private const val KEY_ACCESS = "access_preference"
        private const val KEY_ROOT_ENABLED = "root_enabled"
    }
}
