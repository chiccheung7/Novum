package com.novum.tv.ui.screen.player

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.novum.tv.model.DanmuConfig
import com.novum.tv.model.DanmuArea
import com.novum.tv.model.Episode
import com.novum.tv.model.PlayMethod
import com.novum.tv.model.PlaybackPlan
import com.novum.tv.testing.RepoPaths
import com.novum.tv.ui.theme.NovumTheme
import com.novum.tv.ui.theme.NovumPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w1280dp-h720dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerDrawerControlsTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val audioDelay = mutableStateOf(0L)
    private val subtitleDelay = mutableStateOf(0L)
    private val subtitleHeight = mutableStateOf(0)
    private val subtitleWeight = mutableStateOf(1)
    private val danmu = mutableStateOf(DanmuSession(config = DanmuConfig(enabled = true)))
    private var adjusting = false

    @Test
    fun audioBarTracksBothDirectionsAndKeepsFocusAtTheLimits() {
        showDrawer(DrawerTab.Audio)
        focus("音频延迟")
        assertProgress("音频延迟", 0.5f)
        press("音频延迟", NativeKeyEvent.KEYCODE_DPAD_LEFT)
        composeRule.runOnIdle { assertEquals(-100L, audioDelay.value); assertTrue(adjusting) }
        assertProgress("音频延迟", 0.475f)
        composeRule.onNodeWithText("-100ms").assertExists()
        composeRule.runOnIdle { audioDelay.value = 2_000L }
        press("音频延迟", NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.runOnIdle { assertEquals(2_000L, audioDelay.value) }
        assertProgress("音频延迟", 1f)
        composeRule.onNodeWithText("音频延迟").assertIsFocused()
        composeRule.runOnIdle { audioDelay.value = -2_000L }
        press("音频延迟", NativeKeyEvent.KEYCODE_DPAD_LEFT)
        composeRule.runOnIdle { assertEquals(-2_000L, audioDelay.value) }
        assertProgress("音频延迟", 0f)
        savePreview("drawer-audio.png")
    }

    @Test
    fun subtitleBarRespectsDelayFloorAndHeightMovesInTheDisplayedDirection() {
        showDrawer(DrawerTab.Subtitle, fontScale = 1.3f)
        focus("字幕延迟")
        press("字幕延迟", NativeKeyEvent.KEYCODE_DPAD_LEFT)
        composeRule.runOnIdle { assertEquals(0L, subtitleDelay.value) }
        press("字幕延迟", NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.runOnIdle { assertEquals(100L, subtitleDelay.value) }
        assertProgress("字幕延迟", 0.05f)
        focus("字幕高度")
        assertProgress("字幕高度", 0.5f, steps = 3)
        press("字幕高度", NativeKeyEvent.KEYCODE_DPAD_LEFT)
        composeRule.runOnIdle { assertEquals(3, subtitleHeight.value) }
        assertProgress("字幕高度", 0.25f, steps = 3)
        press("字幕高度", NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        press("字幕高度", NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.runOnIdle { assertEquals(1, subtitleHeight.value) }
        assertProgress("字幕高度", 0.75f, steps = 3)
        savePreview("drawer-subtitles-large-font.png")
    }

    @Test
    fun missingSubtitlesDisableControlsAndDanmakuOffsetKeepsItsFullRange() {
        showDrawer(DrawerTab.Subtitle, withSubtitles = false)
        composeRule.onNodeWithText("字幕开关").assertDoesNotExist()
        composeRule.onNodeWithText("字幕延迟").assertDoesNotExist()
        composeRule.onNodeWithText("字幕字号").assertDoesNotExist()
        composeRule.onNodeWithText("字幕粗细").assertDoesNotExist()
        composeRule.onNodeWithText("字幕高度").assertDoesNotExist()
        composeRule.onNodeWithText("此片源不支持字幕操作").assertExists()
        focus("弹幕")
        composeRule.onNodeWithText("弹幕").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        savePreview("drawer-danmaku.png")
        focus("时间偏移")
        assertProgress("时间偏移", 0.5f)
        press("时间偏移", NativeKeyEvent.KEYCODE_DPAD_LEFT)
        composeRule.runOnIdle { assertEquals(-100L, danmu.value.offsetMs) }
        composeRule.onNodeWithText("-0.1s").assertExists()
        composeRule.runOnIdle { danmu.value = danmu.value.copy(offsetMs = DANMU_OFFSET_LIMIT_MS) }
        press("时间偏移", NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.runOnIdle { assertEquals(DANMU_OFFSET_LIMIT_MS, danmu.value.offsetMs) }
        assertProgress("时间偏移", 1f)
        savePreview("drawer-danmaku-offset.png")
    }

    @Test
    fun missingAudioHidesAudioOperationsAndShowsAnExplanation() {
        showDrawer(DrawerTab.Audio, withAudioTracks = false)
        composeRule.onNodeWithText("音频直通（杜比 / DTS）").assertDoesNotExist()
        composeRule.onNodeWithText("音频延迟").assertDoesNotExist()
        composeRule.onNodeWithText("此片源不支持音轨操作").assertExists()
    }

    @Test
    fun threeChoiceRowsCycleWithOkWithoutBarsOrArrowAdjustments() {
        showDrawer(DrawerTab.Subtitle)
        focus("字幕粗细")
        composeRule.onNodeWithText("字幕粗细").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo),
        )
        press("字幕粗细", NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.runOnIdle { assertEquals(1, subtitleWeight.value) }
        press("字幕粗细", NativeKeyEvent.KEYCODE_DPAD_CENTER)
        composeRule.runOnIdle { assertEquals(2, subtitleWeight.value) }
        press("字幕粗细", NativeKeyEvent.KEYCODE_DPAD_CENTER)
        composeRule.runOnIdle { assertEquals(0, subtitleWeight.value) }
        savePreview("drawer-subtitle-weight.png")

        focus("弹幕")
        composeRule.onNodeWithText("弹幕").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        focus("显示区域")
        composeRule.onNodeWithText("显示区域").assert(
            SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo),
        )
        press("显示区域", NativeKeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.runOnIdle { assertEquals(DanmuArea.Half, danmu.value.config.area) }
        press("显示区域", NativeKeyEvent.KEYCODE_DPAD_CENTER)
        composeRule.runOnIdle { assertEquals(DanmuArea.Full, danmu.value.config.area) }
        press("显示区域", NativeKeyEvent.KEYCODE_DPAD_CENTER)
        composeRule.runOnIdle { assertEquals(DanmuArea.Top, danmu.value.config.area) }
        composeRule.onNodeWithText("仅顶部").assertExists()
        savePreview("drawer-three-choice.png")
    }

    @Test
    fun watchedEpisodesUseAnIconInsteadOfVisibleStatusText() {
        showDrawer(DrawerTab.Episodes)
        composeRule.onNodeWithContentDescription("已看完").assertExists()
        composeRule.onAllNodesWithText("已看完").assertCountEquals(0)
        focus("S01E01")
        press("S01E01", NativeKeyEvent.KEYCODE_DPAD_DOWN)
        composeRule.onNodeWithText("S01E02").assertIsFocused()
        savePreview("drawer-watched.png")
    }

    @Test
    fun unsupportedAudioRowsAreDisabledEvenWhenMultipleTracksExist() {
        showDrawer(DrawerTab.Audio, audioOptions = listOf(
            TrackOption("0:0", "Supported audio", true),
            TrackOption("0:1", "Unsupported audio", false, supported = false),
        ))
        composeRule.onNodeWithText("Unsupported audio").assertIsNotEnabled()
    }

    private fun showDrawer(
        tab: DrawerTab,
        fontScale: Float = 1f,
        withSubtitles: Boolean = true,
        withAudioTracks: Boolean = true,
        audioOptions: List<TrackOption>? = null,
    ) {
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.enableEdgeToEdge()
            WindowCompat.getInsetsController(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.systemBars())
        }
        composeRule.setContent {
            val inputMode = LocalInputModeManager.current
            // 遥控器使用键盘模式；Robolectric 的窗口默认处于触摸模式。
            LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                NovumTheme {
                    Box(Modifier.requiredSize(1280.dp, 720.dp).background(NovumPalette.Ink)) {
                        PlayerSettingsDrawer(
                            visible = true,
                            initialTab = tab,
                            onTabsFocusChanged = {},
                            onAdjustFocusChanged = { adjusting = it },
                            modifier = Modifier.align(Alignment.CenterEnd),
                            audioTracks = audioOptions ?: if (withAudioTracks) {
                                listOf(TrackOption("audio", "English · 6 channels", true, codec = "AC-3"))
                            } else {
                                emptyList()
                            },
                            textTracks = if (withSubtitles) listOf(
                                TrackOption(TEXT_OFF_ID, "关闭字幕", false),
                                TrackOption("text", "简体中文", true),
                            ) else emptyList(),
                            passthrough = false,
                            plan = PlaybackPlan(
                                url = "https://example.test/video", method = PlayMethod.DirectPlay,
                                audioTracks = emptyList(), subtitleTracks = emptyList(), subtitles = emptyList(),
                                durationMs = null, startPositionMs = 0,
                            ),
                            subtitle = "", audioLabel = "", subtitleLabel = "", chapters = emptyList(),
                            episodes = listOf(
                                Episode("ep-1", "第一集", 1, 1, true, 0L, 45, null, overview = null),
                                Episode("ep-2", "第二集", 1, 2, false, 0L, 45, null, overview = null),
                            ),
                            onSelectAudio = {}, onSelectText = {}, onTogglePassthrough = {}, onSelectChapter = {},
                            audioDelayMs = audioDelay.value,
                            onAudioDelayChanged = { audioDelay.value = it },
                            subtitleDelayMs = subtitleDelay.value,
                            onSubtitleDelayChanged = { subtitleDelay.value = it },
                            subtitleOffsetLevel = subtitleHeight.value,
                            onSubtitleOffsetLevelChanged = { subtitleHeight.value = it },
                            subtitleWeight = subtitleWeight.value,
                            onSubtitleWeightChanged = { subtitleWeight.value = it },
                            danmu = danmu.value,
                            danmuActions = DanmuActions(
                                setEnabled = { enabled -> danmu.value = danmu.value.copy(config = danmu.value.config.copy(enabled = enabled)) },
                                updateConfig = { update -> danmu.value = danmu.value.copy(config = update(danmu.value.config)) },
                                adjustOffset = { delta -> danmu.value = danmu.value.copy(offsetMs = (danmu.value.offsetMs + delta).coerceIn(-DANMU_OFFSET_LIMIT_MS, DANMU_OFFSET_LIMIT_MS)) },
                                resetOffset = { danmu.value = danmu.value.copy(offsetMs = 0L) },
                                retry = {}, searchAnime = {}, openAnime = {}, selectEpisode = {}, closeSearch = {}, useAutoMatch = {},
                            ),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun focus(text: String) {
        composeRule.onNodeWithText(text).performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(text).assertIsFocused()
    }

    private fun press(text: String, key: Int) {
        composeRule.onNodeWithText(text).performKeyPress(
            ComposeKeyEvent(NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_DOWN, key, 0)),
        )
        composeRule.onNodeWithText(text).performKeyPress(
            ComposeKeyEvent(NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_UP, key, 0)),
        )
        composeRule.waitForIdle()
    }

    private fun assertProgress(text: String, current: Float, steps: Int = 0) {
        composeRule.onNodeWithText(text).assert(SemanticsMatcher.expectValue(
            SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(current, 0f..1f, steps),
        ))
    }

    private fun savePreview(filename: String) {
        composeRule.mainClock.advanceTimeBy(800L)
        composeRule.waitForIdle()
        val output = RepoPaths.root.resolve("feature/player/build/reports/ui-preview/$filename")
        assertTrue(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
        composeRule.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }
}
