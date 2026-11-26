package com.novum.tv.ui.screen.detail

import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.tv.material3.Text
import com.novum.tv.core.state.UiState
import com.novum.tv.model.MediaDetail
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Rule
import org.junit.Test
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

    private fun detail(type: String): MediaDetail = MediaDetail(
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
        played = false,
        resumePositionMs = 60_000L,
        childCount = null,
        premiereDate = null,
        status = null,
        cast = emptyList(),
        studios = emptyList(),
    )

    private fun mountActionRow(type: String) {
        val detail = detail(type)
        composeRule.setContent {
            NovumTheme {
                ActionRow(
                    data = detail,
                    state = DetailUiState(detail = UiState.Success(detail)),
                    selectedSeason = null,
                    episode = null,
                    playFocus = remember { FocusRequester() },
                    onPlay = {},
                    onPlayLongClick = null,
                    onReplayLongClick = null,
                    onToggleFavorite = {},
                    onTogglePlayed = {},
                    onMarkSeasonMenuOpen = {},
                    onOpenSeasonMenu = {},
                )
            }
        }
    }
}
