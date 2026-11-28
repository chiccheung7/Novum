package com.novum.tv.ui.component

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumPalette
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-land-mdpi")
class NovumButtonContrastTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun retryLabelFollowsTheButtonsOwnFocusColors() {
        val retryFocus = FocusRequester()
        val otherFocus = FocusRequester()
        var labelColor = Color.Unspecified
        composeRule.setContent {
            NovumTheme {
                NovumInitialFocus(otherFocus)
                Row {
                    NovumButton(onClick = {}, modifier = Modifier.focusRequester(retryFocus).testTag("retry")) {
                        val color = LocalContentColor.current
                        SideEffect { labelColor = color }
                        Text("重试")
                    }
                    NovumButton(onClick = {}, modifier = Modifier.focusRequester(otherFocus).testTag("other")) {
                        Text("返回")
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.onNodeWithTag("other").assertIsFocused()
        composeRule.runOnIdle { assertEquals(NovumPalette.Paper, labelColor) }

        repeat(2) {
            composeRule.runOnIdle { retryFocus.requestFocus() }
            composeRule.mainClock.advanceTimeBy(500)
            composeRule.onNodeWithTag("retry").assertIsFocused()
            composeRule.runOnIdle { assertEquals(NovumPalette.Ink, labelColor) }
            composeRule.runOnIdle { otherFocus.requestFocus() }
            composeRule.mainClock.advanceTimeBy(500)
            composeRule.runOnIdle { assertEquals(NovumPalette.Paper, labelColor) }
        }
    }

    @Test
    fun disabledRetryKeepsALightLabelOnItsDarkContainer() {
        var labelColor = Color.Unspecified
        composeRule.setContent {
            NovumTheme {
                NovumButton(onClick = {}, enabled = false) {
                    val color = LocalContentColor.current
                    SideEffect { labelColor = color }
                    Text("重试")
                }
            }
        }
        composeRule.runOnIdle { assertEquals(NovumPalette.PaperMuted, labelColor) }
    }
}
