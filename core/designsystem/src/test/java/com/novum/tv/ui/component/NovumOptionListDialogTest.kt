package com.novum.tv.ui.component

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-xhdpi")
class NovumOptionListDialogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectedRowBeyondFirstScreenScrollsIntoViewAndReceivesFocus() {
        composeRule.setContent {
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(inputMode) { inputMode.requestInputMode(InputMode.Keyboard) }
            NovumTheme {
                NovumOptionListDialog(
                    title = "版本",
                    options = List(20) { "选项 $it" },
                    selectedIndex = 19,
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("选项 19").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun arrivingOptionsScrollTheSelectedRowIntoViewAndReceiveFocus() {
        val options = mutableStateOf(emptyList<String>())
        composeRule.setContent {
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(inputMode) { inputMode.requestInputMode(InputMode.Keyboard) }
            NovumTheme {
                NovumOptionListDialog(
                    title = "版本",
                    options = options.value,
                    selectedIndex = 19,
                    onSelect = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { options.value = List(20) { "选项 $it" } }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("选项 19").assertIsFocused().assertIsDisplayed()
    }
}
