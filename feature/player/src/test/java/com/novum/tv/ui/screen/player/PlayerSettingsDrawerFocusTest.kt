package com.novum.tv.ui.screen.player

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.model.Episode
import com.novum.tv.model.PlayMethod
import com.novum.tv.model.PlaybackPlan
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-xhdpi")
class PlayerSettingsDrawerFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun drawerKeepsFocusInsideUntilClosedAndAllowsTabToContentNavigation() {
        val drawerVisible = mutableStateOf(true)
        val controlsFocus = FocusRequester()
        var tabsFocused = false
        composeRule.setContent {
            NovumTheme {
                // 与 PlayerStage 的 Back 交接时序一致：隐藏抽屉后等一帧再交还焦点。
                LaunchedEffect(drawerVisible.value) {
                    if (!drawerVisible.value) {
                        withFrameNanos { }
                        controlsFocus.requestFocus()
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    // 模拟抽屉后方仍可聚焦的控制条。首个 tab 按左不能逃回它。
                    Text(
                        text = "outside controls",
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(160.dp)
                            .fillMaxHeight()
                            .focusRequester(controlsFocus)
                            .focusable(),
                    )
                    PlayerSettingsDrawer(
                        visible = drawerVisible.value,
                        initialTab = DrawerTab.Episodes,
                        onTabsFocusChanged = { tabsFocused = it },
                        modifier = Modifier.align(Alignment.CenterEnd),
                        audioTracks = emptyList(),
                        textTracks = emptyList(),
                        passthrough = false,
                        plan = PlaybackPlan(
                            url = "https://example.test/video",
                            method = PlayMethod.DirectPlay,
                            audioTracks = emptyList(),
                            subtitleTracks = emptyList(),
                            subtitles = emptyList(),
                            durationMs = null,
                            startPositionMs = 0,
                        ),
                        subtitle = "",
                        audioLabel = "",
                        subtitleLabel = "",
                        chapters = emptyList(),
                        onSelectAudio = {},
                        onSelectText = {},
                        onTogglePassthrough = {},
                        onSelectChapter = {},
                        episodes = listOf(
                            Episode(
                                id = "episode-100",
                                title = "test episode",
                                seasonIndex = 1,
                                index = 100,
                                played = false,
                                progressMs = 0,
                                runtimeMinutes = null,
                                imageUrl = null,
                                overview = null,
                            ),
                        ),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("选集").assertIsFocused()

        press("选集", NativeKeyEvent.KEYCODE_DPAD_LEFT)
        composeRule.onNodeWithText("选集").assertIsFocused()
        composeRule.onNodeWithText("outside controls").assertIsNotFocused()

        // onExit 只取消离开整块抽屉；不能把 tab 行到内容区的移动一起取消。
        press("选集", NativeKeyEvent.KEYCODE_DPAD_DOWN)
        composeRule.onNodeWithText("S01E100 · test episode").assertIsFocused()

        press("S01E100 · test episode", NativeKeyEvent.KEYCODE_DPAD_UP)
        // 宽内容行向上会按几何位置选一个 tab，不要求恰好回到第一个 tab。
        composeRule.runOnIdle { assertTrue("内容区按上应返回 tab 行", tabsFocused) }
        composeRule.onNodeWithText("S01E100 · test episode").assertIsNotFocused()
        composeRule.onNodeWithText("outside controls").assertIsNotFocused()

        composeRule.runOnIdle { drawerVisible.value = false }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("outside controls").assertIsFocused()
    }

    private fun press(text: String, keyCode: Int) {
        composeRule.onNodeWithText(text).performKeyPress(
            ComposeKeyEvent(NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_DOWN, keyCode, 0)),
        )
        composeRule.waitForIdle()
    }
}
