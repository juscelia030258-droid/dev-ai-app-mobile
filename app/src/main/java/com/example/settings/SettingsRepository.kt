package com.example.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.extensions.ExtensionIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "local_dev_ai_settings")

data class AppSettings(
    val serverPort: Int = 8080,
    val ramLimitMb: Long = 4096L,
    val lanOnlyMode: Boolean = true,
    val rateLimitPerMinute: Int = 60,
    val maxMultiAgentRevisions: Int = 2,
    val multiAgentTimeoutSec: Int = 180,
    val autoUnloadIdleModels: Boolean = true,
    val idleUnloadMinutes: Int = 5,
    val thermalGuardEnabled: Boolean = true,
    val defaultMaxTokens: Int = 1024,
    val darkThemeEnabled: Boolean = true,
    val autoStartServerOnLaunch: Boolean = false,
    val enabledExtensions: Set<String> = ExtensionIds.ALL
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SERVER_PORT = intPreferencesKey("server_port")
        val RAM_LIMIT_MB = longPreferencesKey("ram_limit_mb_active_model_v2")
        val LAN_ONLY_MODE = booleanPreferencesKey("lan_only_mode")
        val RATE_LIMIT_PER_MIN = intPreferencesKey("rate_limit_per_min")
        val MAX_REVISIONS = intPreferencesKey("max_revisions")
        val MULTIAGENT_TIMEOUT_SEC = intPreferencesKey("multiagent_timeout_sec")
        val AUTO_UNLOAD = booleanPreferencesKey("auto_unload_idle")
        val IDLE_UNLOAD_MINUTES = intPreferencesKey("idle_unload_minutes")
        val THERMAL_GUARD_ENABLED = booleanPreferencesKey("thermal_guard_enabled")
        val DEFAULT_MAX_TOKENS = intPreferencesKey("default_max_tokens")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val AUTO_START_SERVER = booleanPreferencesKey("auto_start_server")
        val SMART_CONTEXT_MEMORY = stringPreferencesKey("smart_context_memory_v1")
    }

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            serverPort = prefs[Keys.SERVER_PORT] ?: 8080,
            ramLimitMb = prefs[Keys.RAM_LIMIT_MB] ?: 4096L,
            lanOnlyMode = prefs[Keys.LAN_ONLY_MODE] ?: true,
            rateLimitPerMinute = prefs[Keys.RATE_LIMIT_PER_MIN] ?: 60,
            maxMultiAgentRevisions = prefs[Keys.MAX_REVISIONS] ?: 2,
            multiAgentTimeoutSec = prefs[Keys.MULTIAGENT_TIMEOUT_SEC] ?: 180,
            autoUnloadIdleModels = prefs[Keys.AUTO_UNLOAD] ?: true,
            idleUnloadMinutes = (prefs[Keys.IDLE_UNLOAD_MINUTES] ?: 5).coerceIn(1, 30),
            thermalGuardEnabled = prefs[Keys.THERMAL_GUARD_ENABLED] ?: true,
            defaultMaxTokens = (prefs[Keys.DEFAULT_MAX_TOKENS] ?: 1024).coerceIn(256, 4096),
            darkThemeEnabled = prefs[Keys.DARK_THEME] ?: true,
            autoStartServerOnLaunch = prefs[Keys.AUTO_START_SERVER] ?: false,
            enabledExtensions = ExtensionIds.ALL.filterTo(mutableSetOf()) { id -> prefs[stringPreferencesKey("extension_" + id)] != "disabled" }
        )
    }

    suspend fun getSmartContextMemory(): String = context.settingsDataStore.data
        .map { it[Keys.SMART_CONTEXT_MEMORY].orEmpty() }
        .first()

    suspend fun saveSmartContextMemory(memory: String) {
        context.settingsDataStore.edit { it[Keys.SMART_CONTEXT_MEMORY] = memory.take(7000) }
    }

    suspend fun clearSmartContextMemory() {
        context.settingsDataStore.edit { it.remove(Keys.SMART_CONTEXT_MEMORY) }
    }

    suspend fun updateServerPort(port: Int) {
        context.settingsDataStore.edit { it[Keys.SERVER_PORT] = port.coerceIn(1024, 65535) }
    }

    suspend fun updateRamLimitMb(limitMb: Long) {
        context.settingsDataStore.edit { it[Keys.RAM_LIMIT_MB] = limitMb.coerceIn(1024L, 4096L) }
    }

    suspend fun updateLanOnlyMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.LAN_ONLY_MODE] = enabled }
    }

    suspend fun updateRateLimit(perMinute: Int) {
        context.settingsDataStore.edit { it[Keys.RATE_LIMIT_PER_MIN] = perMinute.coerceIn(5, 600) }
    }

    suspend fun updateMaxRevisions(revisions: Int) {
        context.settingsDataStore.edit { it[Keys.MAX_REVISIONS] = revisions.coerceIn(0, 4) }
    }

    suspend fun updateMultiAgentTimeoutSec(seconds: Int) {
        context.settingsDataStore.edit { it[Keys.MULTIAGENT_TIMEOUT_SEC] = seconds.coerceIn(30, 600) }
    }

    suspend fun updateAutoUnload(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_UNLOAD] = enabled }
    }

    suspend fun updateIdleUnloadMinutes(minutes: Int) {
        context.settingsDataStore.edit { it[Keys.IDLE_UNLOAD_MINUTES] = minutes.coerceIn(1, 30) }
    }

    suspend fun updateThermalGuard(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.THERMAL_GUARD_ENABLED] = enabled }
    }

    suspend fun updateDefaultMaxTokens(tokens: Int) {
        context.settingsDataStore.edit { it[Keys.DEFAULT_MAX_TOKENS] = tokens.coerceIn(256, 4096) }
    }

    suspend fun updateDarkTheme(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.DARK_THEME] = enabled }
    }

    suspend fun updateAutoStartServer(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTO_START_SERVER] = enabled }
    }

    suspend fun updateExtensionEnabled(id: String, enabled: Boolean) {
        require(id in ExtensionIds.ALL) { "Extensão desconhecida." }
        context.settingsDataStore.edit { it[stringPreferencesKey("extension_" + id)] = if (enabled) "enabled" else "disabled" }
    }

    suspend fun resetToDefaults() {
        context.settingsDataStore.edit { it.clear() }
    }
}
