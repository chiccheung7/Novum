package com.novum.tv.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.tv.material3.Text
import com.novum.tv.model.HOME_ROW_RESUME
import com.novum.tv.model.HomeEntry
import com.novum.tv.model.HomeFeed
import com.novum.tv.model.HomeLatestRow
import com.novum.tv.model.HomeRow
import com.novum.tv.model.MediaItem
import com.novum.tv.testing.RepoPaths
import com.novum.tv.ui.component.NovumChip
import com.novum.tv.ui.component.NovumSkeletonPosterGrid
import com.novum.tv.ui.component.PosterGrid
import com.novum.tv.ui.screen.home.HomeContent
import com.novum.tv.ui.screen.home.HomeSkeleton
import com.novum.tv.ui.theme.NovumDimens
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumTheme
import com.novum.tv.ui.theme.NovumType
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Offline previews rendered by the real Compose components and Robolectric's native renderer.
 * Each scene gets a fresh Activity/Compose tree and produces a complete 1280 x 720 PNG.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaVisualPreviewTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun renderHomeLoading() = render(Scene.HomeLoading, "01-home-loading.png")

    @Test
    fun renderHomeContentAndSynopsis() = render(Scene.Home, "02-home-content-and-synopsis.png")

    @Test
    fun renderPosterLoading() = render(Scene.PosterLoading, "03-posters-loading.png")

    @Test
    fun renderPosterContent() = render(Scene.Posters, "04-posters-content.png")

    private fun render(scene: Scene, filename: String) {
        val output = RepoPaths.root.resolve("app/build/reports/ui-preview")
        assertTrue("Cannot create preview directory: $output", output.isDirectory || output.mkdirs())
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale = 1f)) {
                NovumTheme {
                    Box(
                        modifier = Modifier.requiredSize(1280.dp, 720.dp)
                            .background(NovumPalette.Ink)
                            .testTag(scene.name),
                    ) {
                        when (scene) {
                            Scene.HomeLoading -> HomeSkeleton()
                            Scene.Home -> HomeContent(
                                feed = previewHomeFeed,
                                onOpenDetail = {},
                                onPlay = {},
                                onOpenLibrary = { _, _ -> },
                                isPageVisible = false,
                            )
                            Scene.PosterLoading -> PreviewGrid(loading = true)
                            Scene.Posters -> PreviewGrid(loading = false)
                        }
                    }
                }
            }
        }

        // Finish entry/focus animations before checking displayed semantics or drawing.
        composeRule.mainClock.advanceTimeBy(800L)
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(32L, TimeUnit.MILLISECONDS)
        var frame: Bitmap? = null
        composeRule.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            assertEquals("Preview window width", 1280, view.width)
            assertEquals("Preview window height", 720, view.height)
            val target = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.invalidate()
            // Paint the real current Compose/View tree, avoiding PixelCopy's cached frame.
            view.draw(Canvas(target))
            frame = target
        }
        val bitmap = requireNotNull(frame)
        assertEquals("Preview canvas width", 1280, bitmap.width)
        assertEquals("Preview canvas height", 720, bitmap.height)
        composeRule.onNodeWithTag(scene.name).assertIsDisplayed()
        when (scene) {
            Scene.HomeLoading -> composeRule.onAllNodesWithText("播放").assertCountEquals(0)
            Scene.Home -> {
                composeRule.onNodeWithText("播放").assertIsDisplayed()
                composeRule.onAllNodesWithText("星海来信").onFirst().assertIsDisplayed()
            }
            Scene.PosterLoading -> {
                composeRule.onNodeWithText("全部影片").assertIsDisplayed()
                composeRule.onAllNodesWithContentDescription("星海来信").assertCountEquals(0)
            }
            Scene.Posters -> {
                composeRule.onNodeWithText("全部影片").assertIsDisplayed()
                composeRule.onAllNodesWithContentDescription("星海来信").onFirst().assertIsDisplayed()
            }
        }
        save(bitmap, File(output, filename))
    }

    @Composable
    private fun PreviewGrid(loading: Boolean) {
        Box(modifier = Modifier.fillMaxSize().padding(top = NovumDimens.SafeVertical)) {
            if (loading) {
                NovumSkeletonPosterGrid(rows = 2, header = { PreviewGridHeader() })
            } else {
                PosterGrid(
                    items = previewItems,
                    onOpenDetail = {},
                    onLoadMore = {},
                    focusOnEnter = false,
                    header = { PreviewGridHeader() },
                )
            }
        }
    }

    @Composable
    private fun PreviewGridHeader() {
        Column(verticalArrangement = Arrangement.spacedBy(NovumDimens.Gutter)) {
            Text(text = "全部影片", style = NovumType.Title, color = NovumPalette.Paper)
            Row(horizontalArrangement = Arrangement.spacedBy(NovumDimens.Gutter)) {
                NovumChip(label = "年份 ↓", selected = true, onClick = {})
                NovumChip(label = "片名", selected = false, onClick = {})
                NovumChip(label = "评分", selected = false, onClick = {})
            }
        }
    }

    private fun save(bitmap: Bitmap, path: File) {
        path.outputStream().use { stream ->
            assertTrue("PNG encoding failed: $path", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        assertTrue("Preview must contain pixel data", path.length() > 0L)
        println("UI_PREVIEW ${path.absolutePath}")
    }

    private enum class Scene { HomeLoading, Home, PosterLoading, Posters }

    private val previewTitles = listOf("星海来信", "远山之旅", "雨后的城市", "第二次出发", "四季之间", "明日航线")

    private val previewItems = List(18) { index ->
        MediaItem(
            id = "preview-$index",
            title = previewTitles[index % previewTitles.size],
            type = "Movie",
            year = 2026 - index % 6,
            rating = 8.1f + index % 4 * 0.2f,
            imageUrl = null,
            subtitle = "${2026 - index % 6} · 剧情",
        )
    }

    private val previewHomeFeed = HomeFeed(
        hero = listOf(
            HomeEntry(
                id = "preview-hero",
                title = "星海来信",
                type = "Movie",
                year = 2026,
                rating = 8.7f,
                runtimeMinutes = 118,
                genres = listOf("剧情", "科幻"),
                resolution = "4K",
                videoFormats = listOf("HDR10"),
                overview = "一封来自远方的信，让久未归乡的年轻人重新踏上旅程。沿途的相遇与告别，" +
                    "让他慢慢理解家人与自己的选择。这是一段关于寻找、理解与重新出发的故事。",
            ),
        ),
        resume = HomeRow(
            HOME_ROW_RESUME,
            previewItems.take(4).mapIndexed { index, item ->
                HomeEntry(
                    id = "resume-$index",
                    title = item.title,
                    subtitle = "S01E0${index + 1} · 新的旅程",
                    progress = 0.3f + index * 0.1f,
                    remainingMinutes = 24,
                )
            },
        ),
        nextUp = null,
        latest = listOf(
            HomeLatestRow(
                libraryId = "preview-library",
                libraryName = "最新入库",
                items = previewItems.map { item ->
                    HomeEntry(id = item.id, title = item.title, year = item.year, rating = item.rating)
                },
            ),
        ),
    )
}
