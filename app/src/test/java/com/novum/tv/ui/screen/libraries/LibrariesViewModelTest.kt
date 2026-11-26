package com.novum.tv.ui.screen.libraries

import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.model.MediaLibrary
import com.novum.tv.testing.MainDispatcherRule
import com.novum.tv.testing.NoopMediaRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
class LibrariesViewModelTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun recentUpdatesWithoutACoverAreHiddenWithoutRemovingOtherLibraries() = runTest {
        val viewModel = viewModel(
            library("movies", "电影", image = null),
            library("recent", "🌐全库追新", image = null),
            library("recent-blank", "全库追新", image = "  "),
            library("series", "剧集", kind = "tvshows"),
            library("music", "音乐", kind = "music"),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect() }
        advanceUntilIdle()

        val libraries = (viewModel.state.value as UiState.Success).data
        assertEquals(listOf("movies", "series"), libraries.map { it.id })
    }

    @Test
    fun recentUpdatesWithStaleCoverMetadataDoNotLeaveABlankCategoryCard() = runTest {
        val ordinary = library("movies", "电影")
        val viewModel = viewModel(
            ordinary,
            library("recent", "🌐全库追新", image = "https://media.example/missing-cover.jpg"),
            library("recent-mixed", "全库追新", kind = null, image = "https://media.example/stale-cover.jpg"),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect() }
        advanceUntilIdle()

        assertEquals(listOf(ordinary), (viewModel.state.value as UiState.Success).data)
    }

    @Test
    fun onlyAnUncoveredRecentUpdatesLibraryShowsTheEmptyState() = runTest {
        val viewModel = viewModel(library("recent", "全库追新", image = null))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.state.collect() }
        advanceUntilIdle()

        assertTrue(viewModel.state.value is UiState.Empty)
    }

    private fun viewModel(vararg libraries: MediaLibrary) = LibrariesViewModel(
        object : MediaRepository by NoopMediaRepository {
            override suspend fun libraries(): NovumResult<List<MediaLibrary>> =
                NovumResult.Success(libraries.toList())
        },
    )

    private fun library(
        id: String,
        name: String,
        kind: String? = "movies",
        image: String? = "https://media.example/$id.jpg",
    ) = MediaLibrary(id, name, kind, 10, image)
}
