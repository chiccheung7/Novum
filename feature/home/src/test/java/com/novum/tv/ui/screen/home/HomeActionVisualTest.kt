package com.novum.tv.ui.screen.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.novum.tv.model.HomeEntry
import com.novum.tv.testing.RepoPaths
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumTheme
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

/** Offline pixel preview of the real Hero, with fictional metadata and no network assets. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeActionVisualTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun heroPlaybackIconAndContentSizedButtonRenderWithoutClipping() {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                NovumTheme {
                    Box(Modifier.requiredSize(1280.dp, 720.dp).background(NovumPalette.Ink).padding(top = 120.dp)) {
                        HeroSection(
                            entry = HomeEntry(
                                id = "offline-hero",
                                title = "星海来信",
                                type = "Movie",
                                year = 2026,
                                rating = 8.7f,
                                runtimeMinutes = 118,
                                genres = listOf("剧情", "科幻"),
                                resolution = "4K",
                                overview = "一封来自远方的信，让年轻人重新踏上旅程，在相遇与告别中寻找答案。",
                            ),
                            onPlay = {},
                            onDetail = {},
                            firstFocus = remember { FocusRequester() },
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithText("立即播放").performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.mainClock.advanceTimeBy(800L)
        composeRule.waitForIdle()
        val play = composeRule.onNodeWithText("立即播放").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val detail = composeRule.onNodeWithText("详情").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val textLayouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("立即播放", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(textLayouts) }
        assertTrue("Playback CTA should fit its icon and four characters", play.width in 160f..200f)
        assertEquals("Hero actions should keep equal height", detail.height, play.height, 1f)
        assertTrue("Focused playback action should retain a gap to details", play.right < detail.left)
        assertTrue(textLayouts.isNotEmpty())
        assertFalse("Hero playback text should remain complete", textLayouts.any { it.hasVisualOverflow })
        println("ACTION_BOUNDS hero-play=${play.width}x${play.height}; hero-detail=${detail.width}x${detail.height}")

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
            val output = RepoPaths.root.resolve("build/reports/ui-preview/hero-play-actions.png")
            val directory = requireNotNull(output.parentFile)
            assertTrue(directory.isDirectory || directory.mkdirs())
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertTrue(output.length() > 0L)
            println("UI_PREVIEW ${output.absolutePath}")
        }
    }
}
