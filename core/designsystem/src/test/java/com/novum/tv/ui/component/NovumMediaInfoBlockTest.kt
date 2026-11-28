package com.novum.tv.ui.component

import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil3.ColorImage
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.annotation.DelicateCoilApi
import coil3.decode.DataSource
import coil3.intercept.Interceptor
import coil3.request.ImageResult
import coil3.request.SuccessResult
import com.novum.tv.ui.theme.NovumTheme
import com.novum.tv.ui.theme.NovumType
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(DelicateCoilApi::class)
class NovumMediaInfoBlockTest {
    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var originalImageLoader: ImageLoader
    private lateinit var fixtureImageLoader: ImageLoader

    @Before
    fun installImageFixtures() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        originalImageLoader = SingletonImageLoader.get(context)
        // Android 的 file URI 在 Windows JVM 下会保留盘符前的斜杠。
        // 成功夹具通过 Coil 拦截器返回内存图片；失败夹具仍走真实文件加载链。
        fixtureImageLoader = ImageLoader.Builder(context)
            .components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
                        when (chain.request.data) {
                            VALID_LOGO_URL -> SuccessResult(
                                image = ColorImage(color = Color.WHITE, width = 320, height = 100),
                                request = chain.request,
                                dataSource = DataSource.MEMORY,
                            )
                            else -> chain.proceed()
                        }
                })
            }
            .build()
        SingletonImageLoader.setUnsafe(fixtureImageLoader)
    }

    @After
    fun restoreImageLoader() {
        SingletonImageLoader.setUnsafe(originalImageLoader)
        fixtureImageLoader.shutdown()
    }

    @Test
    fun blankLogoKeepsTheTextTitleVisible() {
        mountInfoBlock(" ")

        composeRule.onNodeWithText("测试作品").assertIsDisplayed()
    }

    @Test
    fun missingLogoUsesTheSystemFontForTheTitle() {
        mountInfoBlock("")
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("测试作品")
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }

        composeRule.runOnIdle {
            assertTrue(layouts.isNotEmpty())
            assertEquals(FontFamily.Default, layouts.single().layoutInput.style.fontFamily)
        }
    }

    @Test
    fun logoKeepsTheReadableTitleInTheMetadataLineForHeroAndDetail() {
        mountInfoBlock(VALID_LOGO_URL, titleMaxLines = 2, metaParts = listOf("2026", "电影", "8.5"))

        val logo = awaitLogoDisplayed()
        val title = composeRule.onNodeWithText("测试作品").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }

        composeRule.runOnIdle {
            val layout = layouts.single().layoutInput
            assertEquals(NovumType.Body.fontSize, layout.style.fontSize)
            assertEquals(FontFamily.Default, layout.style.fontFamily)
            assertEquals(1, layout.maxLines)
        }
        assertTrue(title.fetchSemanticsNode().boundsInRoot.top > logo.fetchSemanticsNode().boundsInRoot.bottom)
        val titleBounds = title.fetchSemanticsNode().boundsInRoot
        val metadataBounds = composeRule.onNodeWithText(" · 2026 · 电影 · 8.5")
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertEquals(titleBounds.center.y, metadataBounds.center.y, 1f)
        assertTrue(titleBounds.right <= metadataBounds.left)
    }

    @Test
    fun logoWithoutOtherMetadataStillKeepsOneReadableTitle() {
        mountInfoBlock(VALID_LOGO_URL)

        awaitLogoDisplayed()
        composeRule.onNodeWithText("测试作品").assertIsDisplayed()
        assertEquals(1, composeRule.onAllNodesWithText("测试作品").fetchSemanticsNodes().size)
    }

    @Test
    fun longTitleEllipsizesBeforeItCanHideTheMetadata() {
        val longTitle = "这是一个很长很长的作品标题".repeat(8)
        mountInfoBlock(
            VALID_LOGO_URL,
            title = longTitle,
            metaParts = listOf("2026", "剧集", "8.5"),
            width = 420.dp,
        )
        composeRule.waitForIdle()
        val titleNode = composeRule.onNodeWithText(longTitle).assertIsDisplayed()
        val metadataNode = composeRule.onNodeWithText(" · 2026 · 剧集 · 8.5").assertIsDisplayed()
        val titleLayouts = mutableListOf<TextLayoutResult>()
        val metadataLayouts = mutableListOf<TextLayoutResult>()
        titleNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(titleLayouts) }
        metadataNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(metadataLayouts) }

        composeRule.runOnIdle {
            assertTrue(titleLayouts.single().isLineEllipsized(0))
            assertTrue(!metadataLayouts.single().hasVisualOverflow)
        }
        val titleBounds = titleNode.fetchSemanticsNode().boundsInRoot
        val metadataBounds = metadataNode.fetchSemanticsNode().boundsInRoot
        assertTrue(titleBounds.width > 0f)
        assertTrue(titleBounds.right <= metadataBounds.left)
        assertEquals(titleBounds.center.y, metadataBounds.center.y, 1f)
    }

    @Test
    fun oversizedMetadataStillLeavesRoomForTheTitle() {
        val metadata = listOf("2026", "剧集", "非常长的分级与版本信息".repeat(12))
        mountInfoBlock(VALID_LOGO_URL, metaParts = metadata, width = 420.dp)

        awaitLogoDisplayed()
        val titleBounds = composeRule.onNodeWithText("测试作品").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val metadataNode = composeRule.onNodeWithText(" · " + metadata.joinToString(" · "))
            .assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        metadataNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }

        composeRule.runOnIdle { assertTrue(layouts.single().isLineEllipsized(0)) }
        val metadataBounds = metadataNode.fetchSemanticsNode().boundsInRoot
        assertTrue(titleBounds.width > 0f)
        assertTrue(titleBounds.right <= metadataBounds.left)
        assertEquals(titleBounds.center.y, metadataBounds.center.y, 1f)
    }

    @Test
    fun failedLogoWithSupplementaryTitleFallsBackToOneDisplayTitle() {
        val missingImage = temporaryFolder.newFile("missing-detail-logo.png")
        check(missingImage.delete())
        mountInfoBlock(missingImage.toURI().toString(), metaParts = listOf("2026", "剧集"))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("测试作品").fetchSemanticsNodes().isEmpty()
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("测试作品").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }

        composeRule.runOnIdle {
            assertEquals(NovumType.Display.fontSize, layouts.single().layoutInput.style.fontSize)
        }
        composeRule.onNodeWithText("2026 · 剧集").assertIsDisplayed()
        assertEquals(1, composeRule.onAllNodesWithText("测试作品").fetchSemanticsNodes().size)
    }

    @Test
    fun failedLogoFallsBackToTheTextTitle() {
        val missingImage = temporaryFolder.newFile("missing-logo.png")
        check(missingImage.delete())
        mountInfoBlock(missingImage.toURI().toString())
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("测试作品").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText("测试作品").assertIsDisplayed()
    }

    private fun awaitLogoDisplayed(): SemanticsNodeInteraction {
        val logo = composeRule.onNodeWithContentDescription("测试作品")
        // 等待真实 AsyncImage 成功回调驱动组件的可见状态。
        composeRule.waitUntil(timeoutMillis = 5_000) {
            runCatching { logo.assertIsDisplayed() }.isSuccess
        }
        return logo.assertIsDisplayed()
    }

    private fun mountInfoBlock(
        logoUrl: String,
        titleMaxLines: Int = 1,
        title: String = "测试作品",
        metaParts: List<String> = emptyList(),
        width: Dp = 640.dp,
    ) {
        composeRule.setContent {
            NovumTheme {
                Box(Modifier.width(width)) {
                    NovumMediaInfoBlock(
                        title = title,
                        logoUrl = logoUrl,
                        metaParts = metaParts,
                        genreTags = emptyList(),
                        formatBadges = emptyList(),
                        overview = null,
                        titleMaxLines = titleMaxLines,
                    )
                }
            }
        }
    }
}

private const val VALID_LOGO_URL = "test://clear-logo"
