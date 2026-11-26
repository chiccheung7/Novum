package com.novum.tv.ui.screen.settings

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.novum.tv.data.line.LineProbe
import com.novum.tv.BuildConfig
import com.novum.tv.data.remote.emby.EmbyApi
import com.novum.tv.data.remote.emby.EmbyApiProvider
import com.novum.tv.data.remote.emby.SessionHolder
import com.novum.tv.data.settings.AppSettings
import com.novum.tv.testing.FakeSettingsRepository
import com.novum.tv.testing.NoopAuthRepository
import com.novum.tv.testing.NoopNovumLogger
import com.novum.tv.testing.RecordingToastSink
import com.novum.tv.testing.RepoPaths
import com.novum.tv.ui.LinePickerViewModel
import com.novum.tv.ui.component.LocalNovumToast
import com.novum.tv.ui.component.NovumToastController
import com.novum.tv.ui.theme.NovumTheme
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

/** Real settings rows, with offline PNGs under app/build/reports/ui-preview/. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenLayoutTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun accountInfoAndActionShareLabelAndValueEdges() {
        showSettings()

        val label = textBounds("退出登录")
        assertEquals("右栏标题与选项标签同列", label.left, textBounds("账号").left, 0.5f)
        assertEquals("只读行与动作行标签同列", label.left, textBounds("用户名").left, 0.5f)
        assertEquals("只读行与动作行尾值同列", textBounds("退出").right, textBounds("未登录").right, 0.5f)

        savePreview("settings-account.png")
    }

    @Test
    fun aboutShowsTheInstalledBuildVersion() {
        showSettings()
        selectSection("关于")

        composeRule.onNodeWithText("Novum").assertIsDisplayed()
        composeRule.onNodeWithText("版本号").assertIsDisplayed()
        composeRule.onNodeWithText("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").assertIsDisplayed()
        savePreview("settings-about.png")
    }

    @Test
    fun appearanceKeepsScaleControlAndRemovesExtraHint() {
        showSettings()
        selectSection("外观与界面")

        composeRule.onNodeWithText("界面大小").assertIsDisplayed()
        composeRule.onNodeWithText("100%", useUnmergedTree = true).assertIsDisplayed()
        assertEquals("标题和调节行标签同列", textBounds("界面大小").left, textBounds("外观与界面").left, 0.5f)
        assertHintAbsent("画面显示不全就调小，字太小就调大")

        savePreview("settings-appearance.png")
    }

    @Test
    fun playbackActionsSwitchesSteppersAndGroupsShareTheSameColumns() {
        showSettings(AppSettings(skipIntro = true))
        selectSection("播放与续播")

        val actionLabel = textBounds("优先视频质量")
        listOf("播放与续播", "播放线路", "优先直连播放", "片头时长").forEach { text ->
            assertEquals("$text 应与动作行标签同列", actionLabel.left, textBounds(text).left, 0.5f)
        }
        assertEquals("调节行与动作行尾值同列", textBounds("默认").right, textBounds("90 秒").right, 0.5f)
        val actionRow = composeRule.onNode(hasText("优先视频质量") and hasClickAction())
            .fetchSemanticsNode().boundsInRoot
        val switchRow = composeRule.onNode(hasText("优先直连播放") and hasClickAction())
            .fetchSemanticsNode().boundsInRoot
        assertEquals("开关行保持同一行尾边界", actionRow.right, switchRow.right, 0.5f)
        assertEquals("行尾值保留20dp内边距", actionRow.right - 20f, textBounds("默认").right, 0.5f)
        assertHintAbsent("播放行为")
        assertHintAbsent("弹幕显示")
        assertHintAbsent("这些选项影响后续播放；音频直通还取决于电视和音响支持。")
        assertHintAbsent("弹幕可用时应用这些默认样式，播放侧栏也可随时调整。")

        savePreview("settings-playback.png")
    }

    @Test
    fun assBurnInStartsEnabledAndKeepsAnExplicitOptOutAcrossSections() {
        showSettings()
        selectSection("字幕与音轨")
        val row = composeRule.onNode(hasText("ASS 字幕烧录保样式") and hasClickAction())
        row.performScrollTo().assertIsDisplayed()
        assertEquals("开", row.fetchSemanticsNode().config[SemanticsProperties.StateDescription])

        row.performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        selectSection("账号")
        selectSection("字幕与音轨")

        assertEquals("关", row.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    @Test
    fun savedAssBurnInOptOutStaysOffWhenOpeningSettings() {
        showSettings(AppSettings(assBurnIn = false))
        selectSection("字幕与音轨")
        val row = composeRule.onNode(hasText("ASS 字幕烧录保样式") and hasClickAction())

        row.performScrollTo().assertIsDisplayed()

        assertEquals("关", row.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }

    private fun showSettings(snapshot: AppSettings = AppSettings()) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = FakeSettingsRepository(snapshot)
        val session = SessionHolder()
        val viewModel = SettingsViewModel(
            settingsRepository = settings,
            authRepository = NoopAuthRepository,
            sessionHolder = session,
            toast = RecordingToastSink(),
            logger = NoopNovumLogger,
            appContext = context,
        )
        val lines = LinePickerViewModel(
            sessionHolder = session,
            lineProbe = LineProbe(
                clients = object : EmbyApiProvider {
                    override fun api(baseUrl: String): EmbyApi = error("Layout tests must not connect to a server")
                },
                sessionHolder = session,
                logger = NoopNovumLogger,
            ),
            settings = settings,
        )
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale = 1f),
                LocalNovumToast provides NovumToastController(),
            ) {
                NovumTheme {
                    Box(Modifier.requiredSize(1280.dp, 720.dp)) {
                        SettingsScreen({}, {}, viewModel = viewModel, linePickerViewModel = lines)
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun selectSection(label: String) {
        composeRule.onNode(hasText(label) and hasClickAction())
            .performSemanticsAction(SemanticsActions.RequestFocus)
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
    }

    /** The section title can also occur in the navigation; choose the right-column text. */
    private fun textBounds(text: String): Rect = composeRule
        .onAllNodesWithText(text, useUnmergedTree = true)
        .fetchSemanticsNodes()
        .maxBy { it.boundsInRoot.left }
        .boundsInRoot

    private fun assertHintAbsent(text: String) {
        composeRule.onAllNodesWithText(text).assertCountEquals(0)
    }

    private fun savePreview(filename: String) {
        composeRule.mainClock.advanceTimeBy(800L)
        composeRule.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(32L, TimeUnit.MILLISECONDS)
        val output = RepoPaths.root.resolve("app/build/reports/ui-preview/$filename")
        assertTrue(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
        composeRule.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            assertEquals(1280, view.width)
            assertEquals(720, view.height)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.invalidate()
            view.draw(Canvas(bitmap))
            output.outputStream().use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            bitmap.recycle()
        }
        assertTrue("Preview must contain pixel data", output.length() > 0L)
        println("UI_PREVIEW ${output.absolutePath}")
    }
}
