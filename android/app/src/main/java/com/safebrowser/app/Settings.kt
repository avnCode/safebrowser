package com.safebrowser.app

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "safebrowser_settings")

enum class Mode { Strict, Lenient }
enum class ThemeChoice { System, Light, Dark }

data class Settings(
    val mode: Mode = Mode.Strict,
    val adblock: Boolean = true,
    val theme: ThemeChoice = ThemeChoice.System,
)

object SettingsStore {
    private val KEY_MODE     = stringPreferencesKey("mode")
    private val KEY_ADBLOCK  = booleanPreferencesKey("adblock")
    private val KEY_THEME    = stringPreferencesKey("theme")

    fun flow(ctx: Context): Flow<Settings> = ctx.dataStore.data.map { p ->
        Settings(
            mode    = runCatching { Mode.valueOf(p[KEY_MODE] ?: "Strict") }.getOrDefault(Mode.Strict),
            adblock = p[KEY_ADBLOCK] ?: true,
            theme   = runCatching { ThemeChoice.valueOf(p[KEY_THEME] ?: "System") }.getOrDefault(ThemeChoice.System),
        )
    }

    suspend fun set(ctx: Context, transform: (Settings) -> Settings) {
        ctx.dataStore.edit { p ->
            val current = Settings(
                mode    = runCatching { Mode.valueOf(p[KEY_MODE] ?: "Strict") }.getOrDefault(Mode.Strict),
                adblock = p[KEY_ADBLOCK] ?: true,
                theme   = runCatching { ThemeChoice.valueOf(p[KEY_THEME] ?: "System") }.getOrDefault(ThemeChoice.System),
            )
            val next = transform(current)
            p[KEY_MODE]    = next.mode.name
            p[KEY_ADBLOCK] = next.adblock
            p[KEY_THEME]   = next.theme.name
        }
    }
}
