package com.novum.tv.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.novum.tv.core.result.NovumResult
import com.novum.tv.data.line.LineProbe
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.data.settings.AppSettings
import com.novum.tv.data.settings.SettingsRepository
import com.novum.tv.model.MediaItem
import com.novum.tv.model.MediaPage
import com.novum.tv.testing.NoopAuthRepository
import com.novum.tv.testing.NoopMediaRepository
import com.novum.tv.testing.NoopNovumLogger
import com.novum.tv.testing.RecordingToastSink
import com.novum.tv.ui.component.LocalNovumToast
import com.novum.tv.ui.component.NovumToastController
import com.novum.tv.ui.screen.favorites.FavoritesScreen
import com.novum.tv.ui.screen.grid.GridScreen
import com.novum.tv.ui.screen.grid.GridViewModel
import com.novum.tv.ui.screen.settings.SettingsScreen
import com.novum.tv.ui.screen.settings.SettingsViewModel
import com.novum.tv.ui.theme.NovumTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-xhdpi")
class FocusRecoveryTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun librarySortRestoresChipAfterDelayedResults() {
        val repository = DelayedSortedRepository()
        val viewModel = GridViewModel(repository)
        composeRule.setContent {
            NovumTheme {
                GridScreen(libraryId = "library", title = "媒体库", onOpenDetail = {}, viewModel = viewModel)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("测试条目").assertIsFocused()

        sortByNameAndWaitForResult(repository)

        composeRule.onNodeWithText("片名 ↑").assertIsFocused()
    }

    @Test
    fun favoritesSortDoesNotLoseChipFocusWhenCardsReappear() {
        val repository = DelayedSortedRepository()
        val viewModel = GridViewModel(repository)
        composeRule.setContent {
            NovumTheme { FavoritesScreen(onOpenDetail = {}, viewModel = viewModel) }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("测试条目").assertIsFocused()

        sortByNameAndWaitForResult(repository)

        composeRule.onNodeWithText("片名 ↑").assertIsFocused()
    }

    private fun sortByNameAndWaitForResult(repository: DelayedSortedRepository) {
        composeRule.onNodeWithText("片名").performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.onNodeWithText("片名").performSemanticsAction(SemanticsActions.OnClick)
        // 超过原来芯片内部重试窗口，模拟真实服务器的慢排序响应。
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle { repository.sortedReply.complete(repository.page) }
        composeRule.waitForIdle()
    }

    @Test
    fun delayedSettingsReceiveInitialFocusWhenMenuFinallyMounts() {
        val snapshots = MutableSharedFlow<AppSettings>(replay = 1)
        val settings = object : SettingsRepository {
            override val settings = snapshots
            override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
        }
        val viewModel = SettingsViewModel(
            settingsRepository = settings,
            authRepository = NoopAuthRepository,
            sessionHolder = SessionHolder(),
            toast = RecordingToastSink(),
            logger = NoopNovumLogger,
            appContext = ApplicationProvider.getApplicationContext<Context>(),
        )
        val lines = LinePickerViewModel(
            sessionHolder = SessionHolder(),
            lineProbe = LineProbe(
                clients = object : EmbyApiProvider {
                    override fun api(baseUrl: String): EmbyApi = error("本用例不得访问服务器")
                },
                sessionHolder = SessionHolder(),
                logger = NoopNovumLogger,
            ),
            settings = settings,
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalNovumToast provides NovumToastController()) {
                NovumTheme {
                    SettingsScreen(
                        onBack = {},
                        onLoggedOut = {},
                        viewModel = viewModel,
                        linePickerViewModel = lines,
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.runOnIdle { snapshots.tryEmit(AppSettings()) }
        composeRule.waitForIdle()

        composeRule.onNode(hasText("账号") and hasClickAction()).assertIsFocused()

        // 设置写回仍是 Success；用户已进入右栏时，快照变化不能重跑页面首焦。
        composeRule.onNode(hasText("退出登录") and hasClickAction())
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.runOnIdle { snapshots.tryEmit(AppSettings(preferDirect = false)) }
        composeRule.waitForIdle()

        composeRule.onNode(hasText("退出登录") and hasClickAction()).assertIsFocused()
    }
}

private class DelayedSortedRepository : MediaRepository by NoopMediaRepository {
    val sortedReply = CompletableDeferred<MediaPage>()
    val page = MediaPage(
        items = listOf(MediaItem("item", "测试条目", "Movie", 2026, null, null, "2026")),
        total = 1,
    )

    override suspend fun items(
        parentId: String?, startIndex: Int, limit: Int, sortBy: String, ascending: Boolean,
        filters: String?, searchTerm: String?, includeItemTypes: String?,
    ): NovumResult<MediaPage> = NovumResult.Success(if (sortBy == "SortName") sortedReply.await() else page)
}
