package com.novum.tv.ui.component

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.novum.tv.ui.theme.NovumTheme
import com.novum.tv.ui.theme.NovumType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        composeRule.onNodeWithText("退出").assertWidthIsEqualTo(132.dp)
        val cancelBounds = composeRule.onNodeWithText("取消").fetchSemanticsNode().boundsInRoot
        val confirmBounds = composeRule.onNodeWithText("退出").fetchSemanticsNode().boundsInRoot
        assertTrue("Focused actions must retain a visible gap", confirmBounds.left - cancelBounds.right >= 16f)
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("退出", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        composeRule.runOnIdle {
            assertEquals(NovumType.Button.fontSize, layouts.single().layoutInput.style.fontSize)
            assertTrue("Compact dialog label must fit", !layouts.single().hasVisualOverflow)
        }
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
        // 312dp panel minus 64dp padding and a 24dp action gap.
        composeRule.onNodeWithText("退出").assertWidthIsEqualTo(112.dp)
        composeRule.onNodeWithText("取消").assertIsFocused()
    }
}
