package com.novum.tv.ui.component

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-land-mdpi")
class NovumConfirmDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun initialRemoteActionCancelsRatherThanConfirms() {
        var confirmations = 0
        var cancellations = 0
        composeRule.setContent {
            NovumTheme {
                NovumConfirmDialog(
                    title = "退出播放？",
                    message = "确认后将停止播放。",
                    confirmText = "退出",
                    cancelText = "取消",
                    onConfirm = { confirmations++ },
                    onCancel = { cancellations++ },
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        // 520dp 面板减去 64dp 内边距与 12dp 按钮间距，两颗按钮各 222dp。
        composeRule.onNodeWithText("退出").assertWidthIsEqualTo(222.dp)
        composeRule.onNodeWithText("取消").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle {
            assertEquals(1, cancellations)
            assertEquals(0, confirmations)
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-port-mdpi")
    fun narrowWindowKeepsBothActionsInsideTheScreen() {
        composeRule.setContent {
            NovumTheme {
                NovumConfirmDialog(
                    title = "退出播放？",
                    message = "确认后将停止播放。",
                    confirmText = "退出",
                    cancelText = "取消",
                    onConfirm = {},
                    onCancel = {},
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        // 360dp 窗口两边各留 24dp，面板宽 312dp，按钮各 118dp。
        composeRule.onNodeWithText("退出").assertWidthIsEqualTo(118.dp)
        composeRule.onNodeWithText("取消").assertIsFocused()
    }
}
