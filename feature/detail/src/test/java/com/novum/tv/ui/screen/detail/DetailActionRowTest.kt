package com.novum.tv.ui.screen.detail

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.tv.material3.Text
import com.novum.tv.model.Episode
import com.novum.tv.model.MediaDetail
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-xhdpi")
class DetailActionRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun seriesReplayIsDisabledUntilAnEpisodeTargetExists() {
        mountActionRow("Series")

        composeRule.onNodeWithText("从头播放").assertIsNotEnabled()
    }

    @Test
    fun movieReplayRemainsEnabledWithoutAnEpisode() {
        mountActionRow("Movie")

        composeRule.onNodeWithText("从头播放").assertIsEnabled()
        composeRule.onNodeWithText("继续播放").assertIsDisplayed()
    }

    @Test
    fun seriesPlayNamesTheCurrentEpisodeEvenWhenItHasResumeProgress() {
        mountActionRow(
            type = "Series",
            episode = Episode("episode", "第七集", 2, 7, false, 60_000L, 45, null, overview = null),
        )

        composeRule.onNodeWithText("播放 S02E07").assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithText("从头播放").assertIsEnabled()
        composeRule.onNodeWithText("立即播放").assertDoesNotExist()
        composeRule.onNodeWithText("继续播放").assertDoesNotExist()
    }

    @Test
    fun seriesPlayButtonDoesNotEllipsizeLongEpisodeNumbers() {
        mountActionRow(
            type = "Series",
            episode = Episode("episode", "测试分集", 100, 101, false, 0L, 45, null, overview = null),
        )

        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("播放 S100E101", useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        composeRule.runOnIdle {
            assertTrue("应能获取播放按钮文字的布局结果", layouts.isNotEmpty())
            assertFalse("播放目标集号必须完整显示", layouts.any { it.hasVisualOverflow })
        }
    }

    @Test
    fun playButtonKeepsItsWidthAsMovieAndEpisodeLabelsChange() {
        val currentEpisode = mutableStateOf<Episode?>(null)
        val isSeries = mutableStateOf(false)
        composeRule.setContent {
            NovumTheme {
                ActionRow(
                    data = detail(if (isSeries.value) "Series" else "Movie"),
                    episode = currentEpisode.value,
                    playFocus = remember { FocusRequester() },
                    onPlay = {},
                    onPlayLongClick = null,
                    onReplayLongClick = null,
                    onToggleFavorite = {},
                    onTogglePlayed = {},
                    onMarkSeasonMenuOpen = {},
                )
            }
        }
        val movieWidth = composeRule.onNodeWithText("继续播放").fetchSemanticsNode().boundsInRoot.width
        composeRule.runOnIdle {
            isSeries.value = true
            currentEpisode.value = Episode("episode", "测试", Int.MAX_VALUE, Int.MAX_VALUE, false, 0L, 45, null, overview = null)
        }
        val label = "播放 S2147483647E2147483647"
        val episodeWidth = composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot.width
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(label, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }

        composeRule.runOnIdle {
            assertEquals("文字变长时按钮外框应保持固定宽度", movieWidth, episodeWidth, 1f)
            assertTrue(layouts.isNotEmpty())
            assertFalse("极端长集号也必须完整显示", layouts.any { it.hasVisualOverflow })
        }
    }

    @Test
    fun maximumSystemFontScaleKeepsTheResumeActionRowInsideTheTvViewport() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                NovumTheme {
                    ActionRow(
                        data = detail("Series"),
                        episode = Episode("episode", "测试", 100, 101, false, 60_000L, 45, null, overview = null),
                        playFocus = remember { FocusRequester() },
                        onPlay = {},
                        onPlayLongClick = null,
                        onReplayLongClick = null,
                        onToggleFavorite = {},
                        onTogglePlayed = {},
                        onMarkSeasonMenuOpen = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("播放 S100E101").assertIsDisplayed()
        composeRule.onNodeWithText("从头播放").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("收藏").assertIsDisplayed()
        val finalButton = composeRule.onNodeWithContentDescription("标为已看").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("播放 S100E101", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }

        composeRule.runOnIdle {
            assertTrue("最大字体下续播操作行不能越出屏幕", finalButton.right <= root.right)
            assertTrue(layouts.isNotEmpty())
            assertFalse(layouts.any { it.hasVisualOverflow })
        }
    }

    @Test
    fun unwatchedActionClearlySaysItWillMarkTheItemWatched() {
        mountActionRow("Movie", played = false)

        composeRule.onNodeWithContentDescription("标为已看").assertIsDisplayed()
    }

    @Test
    fun watchedActionClearlySaysItWillMarkTheItemUnwatched() {
        mountActionRow("Movie", played = true)

        composeRule.onNodeWithContentDescription("标为未看").assertIsDisplayed()
    }

    @Test
    fun episodeMetadataPreservesNumbersAbove99() {
        val detail = detail("Episode").copy(seasonIndex = 1, index = 101)
        composeRule.setContent {
            NovumTheme {
                Text(detailMetaParts(detail, seasonCount = 0).joinToString(" · "))
            }
        }

        composeRule.onNodeWithText("S01E101", substring = true).assertIsDisplayed()
    }

    private fun detail(type: String, played: Boolean = false): MediaDetail = MediaDetail(
        id = "item",
        title = "测试条目",
        type = type,
        year = null,
        rating = null,
        officialRating = null,
        runtimeMinutes = 45,
        overview = null,
        genres = emptyList(),
        posterUrl = null,
        backdropUrl = null,
        isFavorite = false,
        played = played,
        resumePositionMs = 60_000L,
        childCount = null,
        premiereDate = null,
        status = null,
        cast = emptyList(),
        studios = emptyList(),
    )

    private fun mountActionRow(type: String, played: Boolean = false, episode: Episode? = null) {
        val detail = detail(type, played)
        composeRule.setContent {
            NovumTheme {
                ActionRow(
                    data = detail,
                    episode = episode,
                    playFocus = remember { FocusRequester() },
                    onPlay = {},
                    onPlayLongClick = null,
                    onReplayLongClick = null,
                    onToggleFavorite = {},
                    onTogglePlayed = {},
                    onMarkSeasonMenuOpen = {},
                )
            }
        }
    }
}
