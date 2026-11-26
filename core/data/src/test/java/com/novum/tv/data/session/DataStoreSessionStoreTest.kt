package com.novum.tv.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DataStoreSessionStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val cipher = TestSecretCipher()

    private fun CoroutineScope.createDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = this,
            produceFile = { File(temporaryFolder.newFolder(), "session.preferences_pb") },
        )

    @Test
    fun missingRememberChoiceDefaultsToEnabledWithoutOverwritingStorage() = runTest {
        val dataStore = createDataStore()
        val store = DataStoreSessionStore(dataStore, cipher)

        assertTrue(store.shouldRememberCredentials())
        assertNull("Reading the default does not force a saved choice", dataStore.data.first()[booleanPreferencesKey("session_remember_credentials")])
    }

    @Test
    fun savesTokenAndPasswordOnlyAsCiphertextAndRestoresThem() = runTest {
        val dataStore = createDataStore()
        val store = DataStoreSessionStore(dataStore, cipher)

        store.save(
            server = "https://tv.local",
            token = "private-token",
            userId = "uid",
            userName = "kid",
            serverName = "TV",
            rememberedPassword = "private-password",
        )

        val storedValues = dataStore.data.first().asMap().values.map { it.toString() }
        assertFalse(storedValues.any { it.contains("private-token") })
        assertFalse(storedValues.any { it.contains("private-password") })
        assertEquals("private-token", store.load()?.token)
        assertEquals("private-password", store.rememberedPassword())
        assertTrue(store.shouldRememberCredentials())
    }

    @Test
    fun clearingRememberChoiceRemovesSecretsButKeepsFormPrefill() = runTest {
        val store = DataStoreSessionStore(createDataStore(), cipher)
        store.save(
            server = "https://tv.local",
            token = "private-token",
            userId = "uid",
            userName = "kid",
            serverName = "TV",
            rememberedPassword = "private-password",
        )

        store.setRememberCredentials(false)

        assertNull(store.load())
        assertNull(store.rememberedPassword())
        assertFalse(store.shouldRememberCredentials())
        assertEquals("https://tv.local", store.lastServer())
        assertEquals("kid", store.lastUsername())
    }

    @Test
    fun rememberChoicePersistsAcrossStoreInstances() = runTest {
        val dataStore = createDataStore()
        val firstStore = DataStoreSessionStore(dataStore, cipher)

        firstStore.setRememberCredentials(false)

        val restored = DataStoreSessionStore(dataStore, cipher)
        assertFalse("An explicit false overrides the enabled default", restored.shouldRememberCredentials())
        restored.setRememberCredentials(true)
        assertTrue(firstStore.shouldRememberCredentials())
    }

    @Test
    fun logoutKeepsPasswordOnlyWhenRememberWasEnabled() = runTest {
        val rememberedStore = DataStoreSessionStore(createDataStore(), cipher)
        rememberedStore.save(
            server = "https://tv.local",
            token = "private-token",
            userId = "uid",
            userName = "kid",
            serverName = "TV",
            rememberedPassword = "private-password",
        )

        rememberedStore.clear()

        assertNull(rememberedStore.load())
        assertEquals("private-password", rememberedStore.rememberedPassword())
        assertTrue(rememberedStore.shouldRememberCredentials())

        val unrememberedStore = DataStoreSessionStore(createDataStore(), cipher)
        unrememberedStore.save(
            server = "https://tv.local",
            token = "private-token",
            userId = "uid",
            userName = "kid",
            serverName = "TV",
            rememberedPassword = null,
        )

        unrememberedStore.clear()

        assertNull(unrememberedStore.rememberedPassword())
        assertFalse(unrememberedStore.shouldRememberCredentials())
    }

    @Test
    fun migratesLegacyPlaintextTokenBeforeRestoringSession() = runTest {
        val dataStore = createDataStore()
        dataStore.edit { preferences ->
            preferences[stringPreferencesKey("session_server")] = "https://tv.local"
            preferences[stringPreferencesKey("session_token")] = "legacy-token"
            preferences[stringPreferencesKey("session_uid")] = "uid"
            preferences[stringPreferencesKey("session_uname")] = "kid"
        }
        val store = DataStoreSessionStore(dataStore, cipher)

        assertEquals("legacy-token", store.load()?.token)
        val storedToken = dataStore.data.first()[stringPreferencesKey("session_token")]
        assertTrue(cipher.isEncrypted(requireNotNull(storedToken)))
        assertFalse(requireNotNull(storedToken).contains("legacy-token"))
    }

    private class TestSecretCipher : SessionSecretCipher {
        override fun encrypt(plaintext: String): String = PREFIX + plaintext.reversed()

        override fun decrypt(ciphertext: String): String? =
            ciphertext.takeIf(::isEncrypted)?.removePrefix(PREFIX)?.reversed()

        override fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

        private companion object {
            const val PREFIX = "test:v1:"
        }
    }
}
