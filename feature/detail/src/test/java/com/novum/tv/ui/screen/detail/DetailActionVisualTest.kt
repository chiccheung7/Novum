package com.novum.tv.ui.screen.detail

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.tv.material3.Text
import com.novum.tv.model.Episode
import com.novum.tv.model.MediaDetail
import com.novum.tv.testing.RepoPaths
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumTheme
import com.novum.tv.ui.theme.NovumType
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders actual detail actions with offline fixtures and checks their content budget. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DetailActionVisualTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun renderMovieActions() = render(episode = null, filename = "detail-movie-actions.png")

    @Test
    fun renderEpisodeActions() = render(
        episode = Episode("offline-episode", "新的旅程", 2, 7, false, 60_000L, 45, null, overview = null),
        filename = "detail-episode-actions.png",
    )

    private fun render(episode: Episode?, filename: String) {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                NovumTheme {
                    Column(
                        modifier = Modifier.requiredSize(1280.dp, 720.dp)
                            .background(NovumPalette.Ink)
                            .padding(horizontal = NovumDimens.SafeHorizontal, vertical = 160.dp),
                        verticalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        Text("星海来信", style = NovumType.Title, color = NovumPalette.Paper)
                        Text("2026 · 剧情 · 4K · 8.7", style = NovumType.Body, color = NovumPalette.PaperMuted)
                        ActionRow(
                            data = previewDetail.copy(type = if (episode == null) "Movie" else "Series"),
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
        val playLabel = if (episode == null) "继续播放" else "播放 S02E07"
        composeRule.onNodeWithText(playLabel).performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.mainClock.advanceTimeBy(800L)
        composeRule.waitForIdle()
        val play = composeRule.onNodeWithText(playLabel).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val replay = composeRule.onNodeWithText("从头播放").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val favorite = composeRule.onNodeWithContentDescription("收藏").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithContentDescription("标为已看").assertIsDisplayed()
        assertTrue("Movie and episode playback actions must be narrower than 280dp", play.width < 280f)
        assertTrue("Replay should fit the arrow and its four-character label", replay.width in 160f..200f)
        assertTrue("Focused play must remain separate from replay", play.right < replay.left)
        assertTrue("Replay must remain separate from the favorite action", replay.right < favorite.left)
        println("ACTION_BOUNDS $filename play=${play.width}x${play.height}; replay=${replay.width}x${replay.height}")
        listOf(playLabel, "从头播放").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertFalse("Action label must not be clipped: $label", layouts.any { it.hasVisualOverflow })
        }

        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(32L, TimeUnit.MILLISECONDS)
        composeRule.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            assertEquals(1280, view.width)
            assertEquals(720, view.height)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.invalidate()
            view.draw(Canvas(bitmap))
            val output = RepoPaths.root.resolve("build/reports/ui-preview/$filename")
            val directory = requireNotNull(output.parentFile)
            assertTrue(directory.isDirectory || directory.mkdirs())
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(output.length() > 0L)
            println("UI_PREVIEW ${output.absolutePath}")
        }
    }

    private val previewDetail = MediaDetail(
        id = "offline-detail",
        title = "星海来信",
        type = "Movie",
        year = 2026,
        rating = 8.7f,
        officialRating = null,
        runtimeMinutes = 118,
        overview = "一封来自远方的信，让年轻人重新踏上旅程。",
        genres = listOf("剧情"),
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
}
