package com.novum.tv.ui

import com.novum.tv.core.error.NovumError
import com.novum.tv.data.repository.AuthRepository
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.session.Session
import com.novum.tv.data.settings.AppSettings
import com.novum.tv.testing.FakeSettingsRepository
import com.novum.tv.testing.MainDispatcherRule
import com.novum.tv.testing.NoopAuthRepository
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppEntryViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun storageFailureShowsRetryableErrorAndRetryCanRestoreLoginRoute() {
        val repository = object : AuthRepository by NoopAuthRepository {
            var failRestore = true

            override suspend fun restore(): Session? {
                if (failRestore) throw IOException("storage unavailable")
                return null
            }
        }
        val viewModel = AppEntryViewModel(repository, FakeSettingsRepository(), SessionHolder())

        val error = viewModel.entry.value as AppEntryViewModel.Entry.RestoreFailed
        assertTrue(error.error is NovumError.Unknown)

        repository.failRestore = false
        viewModel.retryRestore()

        assertEquals(AppEntryViewModel.Entry.LoggedOut, viewModel.entry.value)
    }

    @Test
    fun restoresConfirmedPinnedHttpPermissionBeforeEnteringHome() {
        val sessionHolder = SessionHolder()
        val session = Session(
            server = "https://media.example",
            token = "fixture-token",
            userId = "fixture-user",
            userName = "Fixture",
            serverName = "Fixture server",
            deviceId = "fixture-device",
        )
        val repository = object : AuthRepository by NoopAuthRepository {
            override suspend fun restore(): Session = session.also(sessionHolder::update)
        }
        val settings = FakeSettingsRepository(
            AppSettings(
                pinnedLineUrl = "http://line.example:8096",
                pinnedLineName = "HTTP line",
                pinnedLineAccountKey = requireNotNull(sessionHolderIdentity(session)),
                pinnedLineCleartextConfirmed = true,
            ),
        )
        val viewModel = AppEntryViewModel(repository, settings, sessionHolder)

        assertEquals(AppEntryViewModel.Entry.LoggedIn, viewModel.entry.value)
        assertTrue(sessionHolder.isCleartextAllowed("http://line.example:8096/Videos/one"))
        assertEquals(false, sessionHolder.isCleartextAllowed("http://other.example/Videos/one"))
    }

    private fun sessionHolderIdentity(session: Session): String? =
        SessionHolder().apply { update(session) }.playbackPrefsNamespace()
}
