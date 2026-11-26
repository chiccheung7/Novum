package com.novum.tv.data.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Recent search terms, stored with the app's private DataStore preferences. */
@Singleton
class SearchHistoryStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val recentSearches: Flow<List<String>> = dataStore.data
        .map { preferences -> decode(preferences[KEY]) }

    suspend fun record(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return

        dataStore.edit { preferences ->
            val updated = buildList {
                add(normalized)
                decode(preferences[KEY])
                    .filterNot { it.equals(normalized, ignoreCase = true) }
                    .forEach(::add)
            }.take(MAX_RECENT_SEARCHES)
            preferences[KEY] = JsonArray(updated.map { JsonPrimitive(it) }).toString()
        }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            runCatching { element.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
        }.distinctBy { it.lowercase() }
            .take(MAX_RECENT_SEARCHES)
    }

    private companion object {
        val KEY = stringPreferencesKey("recent_searches")
        const val MAX_RECENT_SEARCHES = 10
    }
}
