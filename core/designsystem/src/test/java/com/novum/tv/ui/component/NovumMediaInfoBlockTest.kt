package com.novum.tv.ui.component

import android.content.Context
import android.graphics.Color
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
    fun logoCanKeepASmallerReadableTitleBelowTheImage() {
        mountInfoBlock(VALID_LOGO_URL, showTitleWithLogo = true, titleMaxLines = 2)

        val logo = awaitLogoDisplayed()
        val title = composeRule.onNodeWithText("测试作品").assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        title.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }

        composeRule.runOnIdle {
            val layout = layouts.single().layoutInput
            assertEquals(NovumType.BodyStrong.fontSize, layout.style.fontSize)
            assertEquals(FontFamily.Default, layout.style.fontFamily)
            assertEquals(2, layout.maxLines)
        }
        assertTrue(title.fetchSemanticsNode().boundsInRoot.top > logo.fetchSemanticsNode().boundsInRoot.bottom)
    }

    @Test
    fun logoStillReplacesTheTextTitleByDefaultForHero() {
        mountInfoBlock(VALID_LOGO_URL)

        awaitLogoDisplayed()
        composeRule.onNodeWithText("测试作品").assertDoesNotExist()
    }

    @Test
    fun failedLogoWithSupplementaryTitleFallsBackToOneDisplayTitle() {
        val missingImage = temporaryFolder.newFile("missing-detail-logo.png")
        check(missingImage.delete())
        mountInfoBlock(missingImage.toURI().toString(), showTitleWithLogo = true)
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithContentDescription("测试作品").fetchSemanticsNodes().isEmpty()
        }
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("测试作品").assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }

        composeRule.runOnIdle {
            assertEquals(NovumType.Display.fontSize, layouts.single().layoutInput.style.fontSize)
        }
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

    private fun mountInfoBlock(logoUrl: String, showTitleWithLogo: Boolean = false, titleMaxLines: Int = 1) {
        composeRule.setContent {
            NovumTheme {
                NovumMediaInfoBlock(
                    title = "测试作品",
                    logoUrl = logoUrl,
                    metaParts = emptyList(),
                    genreTags = emptyList(),
                    formatBadges = emptyList(),
                    overview = null,
                    showTitleWithLogo = showTitleWithLogo,
                    titleMaxLines = titleMaxLines,
                )
            }
        }
    }
}

private const val VALID_LOGO_URL = "test://clear-logo"
