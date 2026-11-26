package com.novum.tv.ui.screen.detail

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import com.novum.tv.model.Episode
import com.novum.tv.model.Season
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-xhdpi")
class DetailSectionsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun refreshingEpisodeProgressDoesNotScrollBackToTheEntryAnchor() {
        val episodes = mutableStateOf(
            (1..12).map { index ->
                Episode("ep-$index", "测试分集$index", 1, index, false, 0L, 45, null, overview = null)
            },
        )
        composeRule.setContent {
            NovumTheme {
                EpisodeStrip(
                    episodes = episodes.value,
                    episodesEmpty = false,
                    episodesFailed = false,
                    firstUnplayedId = "ep-1",
                    anchorEpisodeId = "ep-1",
                    selectedEpisodeId = "ep-10",
                    logoUrl = null,
                    episodeFocus = remember { FocusRequester() },
                    contextFocus = remember { FocusRequester() },
                    onCardClick = {},
                    onRetry = {},
                )
            }
        }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(9)
        composeRule.onNodeWithContentDescription("S01E10 · 测试分集10")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            episodes.value = episodes.value.map { it.copy(progressMs = if (it.id == "ep-10") 90_000L else 0L) }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("S01E10 · 测试分集10").assertIsDisplayed().assertIsFocused()
    }

    @Test
    fun episodeCardsPreserveNumbersAbove99() {
        composeRule.setContent {
            NovumTheme {
                EpisodeStrip(
                    episodes = listOf(
                        Episode(
                            id = "ep-101",
                            title = "测试分集",
                            seasonIndex = 1,
                            index = 101,
                            played = false,
                            progressMs = 0L,
                            runtimeMinutes = 45,
                            imageUrl = null,
                            overview = null,
                        ),
                    ),
                    episodesEmpty = false,
                    episodesFailed = false,
                    firstUnplayedId = "ep-101",
                    anchorEpisodeId = null,
                    selectedEpisodeId = null,
                    logoUrl = null,
                    episodeFocus = remember { FocusRequester() },
                    contextFocus = remember { FocusRequester() },
                    onCardClick = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("S01E101 · 测试分集").assertIsDisplayed()
    }

    @Test
    fun episodeStripKeepsOnlyTheCardOverviewAndSeasonSelectorBesideTheHeading() {
        var seasonMenuOpened = false
        composeRule.setContent {
            NovumTheme {
                EpisodeStrip(
                    episodes = listOf(
                        Episode(
                            id = "ep-1",
                            title = "分集标题",
                            seasonIndex = 1,
                            index = 1,
                            played = false,
                            progressMs = 90_000L,
                            runtimeMinutes = 45,
                            imageUrl = null,
                            overview = "这是完整的分集简介，不应受卡片两行省略限制。",
                        ),
                    ),
                    episodesEmpty = false,
                    episodesFailed = false,
                    firstUnplayedId = "ep-1",
                    anchorEpisodeId = null,
                    selectedEpisodeId = "ep-1",
                    selectedSeason = Season("season-1", "第一季", 1, 10, 3),
                    onOpenSeasonMenu = { seasonMenuOpened = true },
                    logoUrl = null,
                    episodeFocus = remember { FocusRequester() },
                    contextFocus = remember { FocusRequester() },
                    onCardClick = {},
                    onRetry = {},
                )
            }
        }

        composeRule.onAllNodesWithText("这是完整的分集简介，不应受卡片两行省略限制。")
            .assertCountEquals(1)
        composeRule.onNodeWithText("当前集").assertDoesNotExist()
        composeRule.onNodeWithText("3%", substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("未看", useUnmergedTree = true).assertExists()
        // TV 按钮由遥控器语义动作触发，不走触摸注入。
        composeRule.onNode(hasText("第 1 季") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assert(seasonMenuOpened) }
    }
}
