package com.novum.tv.data.search

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchHistoryStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun CoroutineScope.createStore(): SearchHistoryStore {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = this,
            produceFile = { File(temporaryFolder.root, "novum_settings.preferences_pb") },
        )
        return SearchHistoryStore(dataStore)
    }

    @Test
    fun recordsUniqueMostRecentQueriesAndClearsHistory() = runTest {
        val store = createStore()

        store.record("  Dune  ")
        store.record("Alien")
        store.record("dune")

        assertEquals(listOf("dune", "Alien"), store.recentSearches.first())

        store.clear()

        assertEquals(emptyList<String>(), store.recentSearches.first())
    }

    @Test
    fun retainsOnlyTenRecentSearches() = runTest {
        val store = createStore()

        (1..12).forEach { store.record("query $it") }

        assertEquals(
            (12 downTo 3).map { "query $it" },
            store.recentSearches.first(),
        )
    }

    @Test
    fun readIoFailureProducesEmptyHistory() = runTest {
        val store = SearchHistoryStore(FailingDataStore(readFailure = IOException("read failed")))

        assertEquals(emptyList<String>(), store.recentSearches.first())
    }

    @Test
    fun writeIoFailuresDoNotEscapeSearchHistoryActions() = runTest {
        val store = SearchHistoryStore(FailingDataStore(writeFailure = IOException("write failed")))

        store.record("Dune")
        store.clear()
    }

    private class FailingDataStore(
        private val readFailure: IOException? = null,
        private val writeFailure: IOException? = null,
    ) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow {
            readFailure?.let { throw it }
            emit(emptyPreferences())
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            writeFailure?.let { throw it }
            return transform(emptyPreferences())
        }
    }
}
