package com.novum.tv.ui.component

import android.view.KeyEvent as NativeKeyEvent
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performKeyPress
import com.novum.tv.model.MediaItem
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-xhdpi")
class PosterGridRepeatNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun nativeVerticalRepeatsMoveFocusByRowsAndKeepTheTargetVisible() {
        composeRule.setContent {
            NovumTheme {
                Box(Modifier.fillMaxSize()) {
                    PosterGrid(
                        items = List(60) { MediaItem("$it", "条目 $it", "Movie", null, null, null, null) },
                        onOpenDetail = {},
                        onLoadMore = {},
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("条目 0").assertIsFocused()
        // 1280dp 可用宽 1144dp，共六列；包含跨越已组合视口的目标行。
        repeat(4) { row ->
            press(NativeKeyEvent.KEYCODE_DPAD_DOWN, repeatCount = row)
            composeRule.onNodeWithContentDescription("条目 ${(row + 1) * 6}")
                .assertIsFocused().assertIsDisplayed()
        }
        repeat(4) { row ->
            press(NativeKeyEvent.KEYCODE_DPAD_UP, repeatCount = row)
            composeRule.onNodeWithContentDescription("条目 ${(3 - row) * 6}")
                .assertIsFocused().assertIsDisplayed()
        }
    }

    @Test
    fun continuousRepeatsKeepFocusWhenMoreResultsAreAppended() {
        lateinit var view: View
        var items by mutableStateOf(mediaItems(36))
        var loadedPages = 1
        composeRule.setContent {
            view = LocalView.current
            NovumTheme {
                PosterGrid(
                    items = items,
                    onOpenDetail = {},
                    onLoadMore = {
                        if (items.size < 144) {
                            loadedPages++
                            items = mediaItems(items.size + 36)
                        }
                    },
                )
            }
        }
        composeRule.onNodeWithContentDescription("条目 0").assertIsFocused()
        hold(view, NativeKeyEvent.KEYCODE_DPAD_DOWN, 70)
        composeRule.onNodeWithContentDescription("条目 138").assertIsFocused().assertIsDisplayed()
        hold(view, NativeKeyEvent.KEYCODE_DPAD_UP, 70)
        composeRule.onNodeWithContentDescription("条目 0").assertIsFocused().assertIsDisplayed()
        composeRule.runOnIdle { assertTrue("Must cross a server pagination boundary", loadedPages > 1) }
    }

    private fun mediaItems(count: Int) =
        List(count) { MediaItem("$it", "条目 $it", "Movie", null, null, null, null) }

    private fun hold(view: View, keyCode: Int, count: Int) {
        composeRule.mainClock.autoAdvance = false
        repeat(count) { repeat ->
            composeRule.runOnUiThread {
                view.dispatchKeyEvent(NativeKeyEvent(0L, repeat * 32L, NativeKeyEvent.ACTION_DOWN, keyCode, repeat))
            }
            composeRule.mainClock.advanceTimeBy(32)
            composeRule.waitForIdle()
            composeRule.onNode(isFocused()).assertIsDisplayed()
        }
        composeRule.runOnUiThread {
            view.dispatchKeyEvent(NativeKeyEvent(NativeKeyEvent.ACTION_UP, keyCode))
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    private fun press(keyCode: Int, repeatCount: Int) {
        composeRule.onNode(isFocused()).performKeyPress(
            ComposeKeyEvent(NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_DOWN, keyCode, repeatCount)),
        )
        composeRule.waitForIdle()
    }
}
