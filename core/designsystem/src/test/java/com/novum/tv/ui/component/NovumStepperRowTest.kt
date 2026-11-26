package com.novum.tv.ui.component

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
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
class NovumStepperRowTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun threeOptionsUseOkToCycleWithoutAControlBarOrArrowAdjustment() {
        val level = mutableStateOf(1)
        showRow(level, count = 3)
        composeRule.onAllNodes(progressMatcher, useUnmergedTree = true).assertCountEquals(0)

        composeRule.onNodeWithText("setting").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.runOnIdle { assertEquals(1, level.value) }
        composeRule.onNodeWithText("setting").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(2, level.value) }
        composeRule.onNodeWithText("setting").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(0, level.value) }
    }

    @Test
    fun fourOptionsExposeAControlBarAndArrowAdjustment() {
        val level = mutableStateOf(1)
        showRow(level, count = 4)
        composeRule.onAllNodes(progressMatcher, useUnmergedTree = true).assertCountEquals(1)

        composeRule.onNodeWithText("setting").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.runOnIdle { assertEquals(2, level.value) }
        composeRule.onNodeWithText("setting").performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.runOnIdle { assertEquals(1, level.value) }
    }

    private fun showRow(level: androidx.compose.runtime.MutableState<Int>, count: Int) {
        val focus = FocusRequester()
        composeRule.setContent {
            val inputMode = LocalInputModeManager.current
            LaunchedEffect(Unit) {
                inputMode.requestInputMode(InputMode.Keyboard)
                focus.requestFocus()
            }
            NovumTheme {
                NovumStepperRow(
                    label = "setting", value = level.value.toString(), level = level.value,
                    levelCount = count, onLevelChange = { level.value = it }, focusRequester = focus,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private val progressMatcher = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
}
