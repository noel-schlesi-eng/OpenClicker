package de.nfcmanager.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Hell"), DARK("Dunkel") }

class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val themeState = MutableStateFlow(
        ThemeMode.values().firstOrNull { it.name == prefs.getString("theme", null) } ?: ThemeMode.SYSTEM,
    )
    private val dynamicState = MutableStateFlow(prefs.getBoolean("dynamic", true))

    val themeMode: StateFlow<ThemeMode> = themeState.asStateFlow()
    val dynamicColor: StateFlow<Boolean> = dynamicState.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString("theme", mode.name).apply()
        themeState.value = mode
    }

    fun setDynamicColor(value: Boolean) {
        prefs.edit().putBoolean("dynamic", value).apply()
        dynamicState.value = value
    }
}
