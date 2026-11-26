package com.novum.tv.ui.component

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
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

    private fun press(keyCode: Int, repeatCount: Int) {
        composeRule.onNode(isFocused()).performKeyPress(
            ComposeKeyEvent(NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_DOWN, keyCode, repeatCount)),
        )
        composeRule.waitForIdle()
    }
}
