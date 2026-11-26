package com.novum.tv.ui.screen.detail

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.repository.ItemPlaybackPrefsStore
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.model.Episode
import com.novum.tv.model.MediaDetail
import com.novum.tv.model.Season
import com.novum.tv.testing.FakeSettingsRepository
import com.novum.tv.testing.NoopMediaRepository
import com.novum.tv.testing.NoopNovumLogger
import com.novum.tv.testing.RecordingToastSink
import com.novum.tv.ui.theme.NovumTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-xhdpi")
class DetailScreenFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun resumeRefreshesMovieWithoutRecompositionRequestsOrStealingFavoriteFocus() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        var detailCalls = 0
        var positionMs = 0L
        val repository = object : MediaRepository by NoopMediaRepository {
            override suspend fun detail(itemId: String): NovumResult<MediaDetail> {
                detailCalls++
                return NovumResult.Success(series().copy(type = "Movie", resumePositionMs = positionMs))
            }
            override suspend fun setFavorite(itemId: String, favorite: Boolean) = NovumResult.Success(favorite)
        }
        val viewModel = DetailViewModel(
            repository = repository,
            toast = RecordingToastSink(),
            settings = FakeSettingsRepository(),
            playbackPrefs = ItemPlaybackPrefsStore(temporaryFolder.newFile(), Dispatchers.Unconfined),
            logger = NoopNovumLogger,
            appContext = ApplicationProvider.getApplicationContext<Context>(),
            savedStateHandle = SavedStateHandle(mapOf("itemId" to "series")),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                NovumTheme {
                    DetailScreen(onBack = {}, onPlay = { _, _, _ -> }, onOpenPerson = { _, _ -> }, viewModel = viewModel)
                }
            }
        }
        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("收藏")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("已收藏").assertIsFocused()
        composeRule.runOnIdle { assertEquals("首次进入和普通重组均不重复请求", 1, detailCalls) }

        composeRule.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        composeRule.runOnIdle {
            positionMs = 90_000L
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("继续播放").assertExists()
        composeRule.onNodeWithContentDescription("已收藏").assertIsFocused()
        composeRule.runOnIdle { assertEquals(2, detailCalls) }
    }

    @Test
    fun slowSeriesEpisodesReceiveTheInitialPlayFocusAfterLoading() {
        val episodes = CompletableDeferred<NovumResult<List<Episode>>>()
        val repository = object : MediaRepository by NoopMediaRepository {
            override suspend fun detail(itemId: String) = NovumResult.Success(series())
            override suspend fun seasons(seriesId: String) = NovumResult.Success(
                listOf(Season("season", "第一季", 1, 1, 1)),
            )
            override suspend fun episodes(seriesId: String, seasonId: String?) = episodes.await()
        }
        val viewModel = DetailViewModel(
            repository = repository,
            toast = RecordingToastSink(),
            settings = FakeSettingsRepository(),
            playbackPrefs = ItemPlaybackPrefsStore(temporaryFolder.newFile(), Dispatchers.Unconfined),
            logger = NoopNovumLogger,
            appContext = ApplicationProvider.getApplicationContext<Context>(),
            savedStateHandle = SavedStateHandle(mapOf("itemId" to "series")),
        )
        composeRule.setContent {
            NovumTheme {
                DetailScreen(onBack = {}, onPlay = { _, _, _ -> }, onOpenPerson = { _, _ -> }, viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()
        // TV按钮禁用时仍可获焦；等待分集期间焦点已停在将要启用的主操作上。
        composeRule.onNodeWithText("…").assertIsNotEnabled().assertIsFocused()
        // 分集晚于原始约500ms首焦重试窗口到达。
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle {
            episodes.complete(
                NovumResult.Success(
                    listOf(Episode("episode", "第一集", 1, 1, false, 0L, 45, null, overview = null)),
                ),
            )
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("立即播放").assertIsFocused()
    }

    private fun series() = MediaDetail(
        id = "series",
        title = "测试剧集",
        type = "Series",
        year = null,
        rating = null,
        officialRating = null,
        runtimeMinutes = 45,
        overview = null,
        genres = emptyList(),
        posterUrl = null,
        backdropUrl = null,
        isFavorite = false,
        played = false,
        resumePositionMs = 0L,
        childCount = 1,
        premiereDate = null,
        status = null,
        cast = emptyList(),
        studios = emptyList(),
    )
}
