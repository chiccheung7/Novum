package com.novum.tv.ui.screen.search

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.data.repository.PersonRef
import com.novum.tv.data.search.SearchHistoryStore
import com.novum.tv.model.MediaItem
import com.novum.tv.model.MediaPage
import com.novum.tv.testing.NoopMediaRepository
import com.novum.tv.ui.screen.grid.GridViewModel
import com.novum.tv.ui.theme.NovumTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-xhdpi")
class SearchScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun automaticSearchPreservesInputAndCategoryFocusButImeCanRepeatTheQuery() {
        val repository = SearchScreenRepository()
        val grid = GridViewModel(repository)
        val history = historyViewModel()
        composeRule.setContent {
            NovumTheme {
                SearchScreen({}, viewModel = grid, historyViewModel = history)
            }
        }
        enterQueryAndWait()
        composeRule.onNodeWithText("人物").assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(0, repository.personCalls) }
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        composeRule.runOnIdle { assertEquals(1, repository.itemCalls) }

        composeRule.onNode(hasSetTextAction()).performImeAction()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals("IME 可强制重复相同关键词", 2, repository.itemCalls) }

        composeRule.onNodeWithText("电影")
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performSemanticsAction(SemanticsActions.OnClick)
        awaitDebouncedSearch()
        composeRule.onNodeWithText("电影").assertIsFocused()
        composeRule.runOnIdle { assertEquals(3, repository.itemCalls) }
    }

    @Test
    fun returningToSavedSearchKeepsLoadedPagesAndGridPosition() {
        val repository = SearchScreenRepository()
        val grid = GridViewModel(repository)
        val history = historyViewModel()
        val visible = mutableStateOf(true)
        composeRule.setContent {
            val saved = rememberSaveableStateHolder()
            if (visible.value) {
                saved.SaveableStateProvider("search") {
                    NovumTheme {
                        SearchScreen({}, viewModel = grid, historyViewModel = history)
                    }
                }
            }
        }
        enterQueryAndWait()
        composeRule.runOnIdle { grid.loadMore() }
        composeRule.waitForIdle()
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(90)
        composeRule.onNodeWithContentDescription("条目 90")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()

        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { visible.value = true }
        awaitDebouncedSearch()

        composeRule.onNodeWithContentDescription("条目 90").assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("返回不能重新查询第一页", 2, repository.itemCalls)
            assertEquals(120, (grid.state.value as UiState.Success).data.size)
            assertEquals("搜索不调用服务器不支持的人物接口", 0, repository.personCalls)
        }
    }

    @Test
    fun restoredQueryWithNewViewModelsStillLoadsResults() {
        val repository = SearchScreenRepository()
        val grid = mutableStateOf(GridViewModel(repository))
        val history = historyViewModel()
        val visible = mutableStateOf(true)
        composeRule.setContent {
            val saved = rememberSaveableStateHolder()
            if (visible.value) {
                saved.SaveableStateProvider("search") {
                    NovumTheme {
                        SearchScreen({}, viewModel = grid.value, historyViewModel = history)
                    }
                }
            }
        }
        enterQueryAndWait()
        composeRule.runOnIdle { visible.value = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            grid.value = GridViewModel(repository)
            visible.value = true
        }
        awaitDebouncedSearch()

        composeRule.onNodeWithContentDescription("条目 0").assertIsDisplayed()
        composeRule.runOnIdle {
            assertTrue(grid.value.state.value is UiState.Success)
            assertEquals("新 VM 不能因恢复的查询状态而跳过请求", 2, repository.itemCalls)
            assertEquals(0, repository.personCalls)
        }
    }

    private fun enterQueryAndWait() {
        composeRule.onNode(hasSetTextAction()).performTextInput("测试")
        awaitDebouncedSearch()
    }

    private fun awaitDebouncedSearch() {
        // 先完成状态变更对应的重组，让 LaunchedEffect 开始500ms防抖，再推进时钟。
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(600)
        composeRule.waitForIdle()
    }

    private fun historyViewModel(): SearchHistoryViewModel {
        val dataStore = object : DataStore<Preferences> {
            override val data = MutableStateFlow<Preferences>(emptyPreferences())
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                transform(data.value).also { data.value = it }
        }
        return SearchHistoryViewModel(SearchHistoryStore(dataStore))
    }
}

private class SearchScreenRepository : MediaRepository by NoopMediaRepository {
    var itemCalls = 0
    var personCalls = 0

    override suspend fun items(
        parentId: String?, startIndex: Int, limit: Int, sortBy: String, ascending: Boolean,
        filters: String?, searchTerm: String?, includeItemTypes: String?,
    ): NovumResult<MediaPage> {
        itemCalls++
        return NovumResult.Success(
            MediaPage(
                (startIndex until (startIndex + limit).coerceAtMost(120)).map { index ->
                    MediaItem("item-$index", "条目 $index", "Movie", 2026, null, null, "2026")
                },
                total = 120,
            ),
        )
    }

    override suspend fun searchPersons(query: String, limit: Int): List<PersonRef> {
        personCalls++
        return emptyList()
    }
}
