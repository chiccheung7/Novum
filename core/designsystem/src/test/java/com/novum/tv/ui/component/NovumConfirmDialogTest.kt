package com.novum.tv.ui.component

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
        composeRule.onNodeWithText("取消").assertIsFocused()
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.runOnIdle {
            assertEquals(1, cancellations)
            assertEquals(0, confirmations)
        }
    }
}
