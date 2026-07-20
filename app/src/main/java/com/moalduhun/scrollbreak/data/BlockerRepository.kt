package com.moalduhun.scrollbreak.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.dataStore by preferencesDataStore(name = "scrollbreak_settings")

/**
 * Single source of truth for blocking state and stats, read by the UI
 * and written by both the UI (toggle) and the accessibility service (block events).
 */
class BlockerRepository(private val context: Context) {

    private object Keys {
        val BLOCKING_ENABLED = booleanPreferencesKey("blocking_enabled")
        val TOTAL_BLOCKED = intPreferencesKey("total_blocked")
        val TODAY_BLOCKED = intPreferencesKey("today_blocked")
        val TODAY_EPOCH_DAY = longPreferencesKey("today_epoch_day")
    }

    val isBlockingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BLOCKING_ENABLED] ?: true
    }

    val totalBlockedCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.TOTAL_BLOCKED] ?: 0
    }

    val todayBlockedCount: Flow<Int> = context.dataStore.data.map { prefs ->
        val storedDay = prefs[Keys.TODAY_EPOCH_DAY]
        if (storedDay != LocalDate.now().toEpochDay()) 0 else prefs[Keys.TODAY_BLOCKED] ?: 0
    }

    suspend fun setBlockingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.BLOCKING_ENABLED] = enabled }
    }

    suspend fun recordBlock() {
        val today = LocalDate.now().toEpochDay()
        context.dataStore.edit { prefs ->
            val storedDay = prefs[Keys.TODAY_EPOCH_DAY]
            val todayCount = if (storedDay == today) prefs[Keys.TODAY_BLOCKED] ?: 0 else 0
            prefs[Keys.TODAY_EPOCH_DAY] = today
            prefs[Keys.TODAY_BLOCKED] = todayCount + 1
            prefs[Keys.TOTAL_BLOCKED] = (prefs[Keys.TOTAL_BLOCKED] ?: 0) + 1
        }
    }
}
