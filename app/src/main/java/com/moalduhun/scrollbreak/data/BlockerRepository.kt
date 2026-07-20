package com.moalduhun.scrollbreak.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.dataStore by preferencesDataStore(name = "scrollbreak_settings")

/** One day's block count, used to draw the weekly graph. */
data class DailyBlocks(val date: LocalDate, val count: Int)

/**
 * Single source of truth for blocking state and stats, read by the UI
 * and written by both the UI (toggle) and the accessibility service (block events).
 */
class BlockerRepository(private val context: Context) {

    private object Keys {
        val BLOCKING_ENABLED = booleanPreferencesKey("blocking_enabled")
        val COVER_INSTAGRAM = booleanPreferencesKey("cover_instagram")
        val COVER_YOUTUBE = booleanPreferencesKey("cover_youtube")
        val TOTAL_BLOCKED = intPreferencesKey("total_blocked")
        val TODAY_BLOCKED = intPreferencesKey("today_blocked")
        val TODAY_EPOCH_DAY = longPreferencesKey("today_epoch_day")
        // Compact per-day history "epochDay:count,epochDay:count,…" backing the weekly graph.
        val DAILY_HISTORY = stringPreferencesKey("daily_history")
    }

    val isBlockingEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.BLOCKING_ENABLED] ?: true
    }

    val coverInstagram: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.COVER_INSTAGRAM] ?: true
    }

    val coverYouTube: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[Keys.COVER_YOUTUBE] ?: true
    }

    val totalBlockedCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[Keys.TOTAL_BLOCKED] ?: 0
    }

    val todayBlockedCount: Flow<Int> = context.dataStore.data.map { prefs ->
        val storedDay = prefs[Keys.TODAY_EPOCH_DAY]
        if (storedDay != LocalDate.now().toEpochDay()) 0 else prefs[Keys.TODAY_BLOCKED] ?: 0
    }

    /** The last 7 days ending today, oldest first — always 7 entries, zero-filled. */
    val weeklyBlocks: Flow<List<DailyBlocks>> = context.dataStore.data.map { prefs ->
        val history = parseHistory(prefs[Keys.DAILY_HISTORY])
        val today = LocalDate.now().toEpochDay()
        (6 downTo 0).map { offset ->
            val epochDay = today - offset
            DailyBlocks(LocalDate.ofEpochDay(epochDay), history[epochDay] ?: 0)
        }
    }

    suspend fun setBlockingEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.BLOCKING_ENABLED] = enabled }
    }

    suspend fun setCoverInstagram(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.COVER_INSTAGRAM] = enabled }
    }

    suspend fun setCoverYouTube(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.COVER_YOUTUBE] = enabled }
    }

    suspend fun recordBlock() {
        val today = LocalDate.now().toEpochDay()
        context.dataStore.edit { prefs ->
            val storedDay = prefs[Keys.TODAY_EPOCH_DAY]
            val todayCount = if (storedDay == today) prefs[Keys.TODAY_BLOCKED] ?: 0 else 0
            prefs[Keys.TODAY_EPOCH_DAY] = today
            prefs[Keys.TODAY_BLOCKED] = todayCount + 1
            prefs[Keys.TOTAL_BLOCKED] = (prefs[Keys.TOTAL_BLOCKED] ?: 0) + 1

            // Update the per-day history, keeping only the last two weeks so the string
            // can't grow without bound.
            val history = parseHistory(prefs[Keys.DAILY_HISTORY]).toMutableMap()
            history[today] = (history[today] ?: 0) + 1
            val cutoff = today - 13
            history.keys.filter { it < cutoff }.toList().forEach { history.remove(it) }
            prefs[Keys.DAILY_HISTORY] = history.entries.joinToString(",") { "${it.key}:${it.value}" }
        }
    }

    private fun parseHistory(raw: String?): Map<Long, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",").mapNotNull { entry ->
            val parts = entry.split(":")
            val day = parts.getOrNull(0)?.toLongOrNull()
            val count = parts.getOrNull(1)?.toIntOrNull()
            if (day != null && count != null) day to count else null
        }.toMap()
    }
}
