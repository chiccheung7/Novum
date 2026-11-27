package com.novum.tv.ui.screen.person

import androidx.lifecycle.SavedStateHandle
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.model.MediaItem
import com.novum.tv.testing.MainDispatcherRule
import com.novum.tv.testing.NoopMediaRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun noWorksShowsTheEmptyState() = runTest {
        val viewModel = PersonViewModel(NoopMediaRepository, route())
        advanceUntilIdle()

        assertTrue("没有作品时应显示空态说明，而不是空白网格", viewModel.state.value.detail is UiState.Empty)
    }

    @Test
    fun failedWorksRequestShowsRetryState() = runTest {
        val repository = object : MediaRepository by NoopMediaRepository {
            override suspend fun personWorks(personId: String): NovumResult<List<MediaItem>> =
                NovumResult.Failure(NovumError.NotFound())
        }
        val viewModel = PersonViewModel(repository, route())
        advanceUntilIdle()

        assertTrue(viewModel.state.value.detail is UiState.Error)
    }

    @Test
    fun lateInitialRequestCannotReplaceTheLatestRetry() = runTest {
        val first = CompletableDeferred<NovumResult<List<MediaItem>>>()
        val second = CompletableDeferred<NovumResult<List<MediaItem>>>()
        var calls = 0
        val repository = object : MediaRepository by NoopMediaRepository {
            override suspend fun personWorks(personId: String): NovumResult<List<MediaItem>> {
                val response = if (calls++ == 0) first else second
                return withContext(NonCancellable) { response.await() }
            }
        }
        val viewModel = PersonViewModel(repository, route())
        runCurrent()
        viewModel.load()
        runCurrent()
        assertEquals(2, calls)

        second.complete(NovumResult.Success(emptyList()))
        runCurrent()
        assertTrue(viewModel.state.value.detail is UiState.Empty)
        first.complete(NovumResult.Failure(NovumError.NotFound()))
        advanceUntilIdle()
        assertTrue("An older request must not overwrite the successful retry", viewModel.state.value.detail is UiState.Empty)
    }

    private fun route() = SavedStateHandle(mapOf("personId" to "person-1", "personName" to "人物"))
}
