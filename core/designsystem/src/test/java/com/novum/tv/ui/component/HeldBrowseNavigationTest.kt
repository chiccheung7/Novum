package com.novum.tv.ui.component

import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Held key events arrive while bring-into-view is still scrolling, unlike settled single presses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1280dp-h720dp-xhdpi")
class HeldBrowseNavigationTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var view: View

    @Test
    fun horizontalRepeatsKeepFocusAcrossSeveralUncomposedPages() {
        val first = FocusRequester()
        var latestFocus = -1
        var focusedIndex = -1
        composeRule.setContent {
            view = LocalView.current
            NovumTheme {
                NovumInitialFocus(first)
                NovumRow(elements = (0 until 40).toList(), key = { it }) { index, _ ->
                    Text(
                        "item:$index",
                        Modifier.size(220.dp, 160.dp)
                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged {
                                if (it.isFocused) {
                                    latestFocus = index
                                    focusedIndex = index
                                } else if (focusedIndex == index) {
                                    focusedIndex = -1
                                }
                            }
                            .focusable(),
                    )
                }
            }
        }
        composeRule.onNodeWithText("item:0").assertIsFocused()
        hold(KeyEvent.KEYCODE_DPAD_RIGHT, 24)
        composeRule.runOnIdle {
            assertTrue("Focus was lost; last focused item was $latestFocus", focusedIndex >= 0)
            assertTrue("Held right must cross several viewports", focusedIndex >= 18)
        }
        composeRule.onNodeWithText("item:$focusedIndex").assertIsFocused().assertIsDisplayed()
        hold(KeyEvent.KEYCODE_DPAD_LEFT, 40)
        composeRule.onNodeWithText("item:0").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun verticalRepeatsKeepFocusAcrossNestedLazyRows() {
        val first = FocusRequester()
        composeRule.setContent {
            view = LocalView.current
            NovumTheme {
                NovumBrowseScaffold(firstFocus = first) {
                    rows(count = 30, key = { it }) { _, row ->
                        NovumRow(elements = (0 until 12).toList(), key = { it }) { column, _ ->
                            Box(Modifier.size(220.dp, 200.dp)) {
                                Text(
                                    "$row:$column",
                                    Modifier.size(220.dp, 180.dp)
                                        .then(if (row == 0 && column == 0) Modifier.focusRequester(first) else Modifier)
                                        .focusable(),
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.onNodeWithText("0:0").assertIsFocused()
        hold(KeyEvent.KEYCODE_DPAD_DOWN, 20)
        composeRule.onNodeWithText("20:0").assertIsFocused().assertIsDisplayed()
        hold(KeyEvent.KEYCODE_DPAD_UP, 20)
        composeRule.onNodeWithText("0:0").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun realCardsKeepVisibleFocusAtBothEdgesAfterRepeatedPagingAndReversal() {
        val first = FocusRequester()
        var focusedIndex = -1
        composeRule.setContent {
            view = LocalView.current
            NovumTheme {
                NovumInitialFocus(first)
                NovumRow(elements = (0 until 40).toList(), key = { it }) { index, _ ->
                    NovumCard(
                        title = "card:$index",
                        width = 220.dp,
                        onClick = {},
                        modifier = Modifier
                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                            .onFocusChanged {
                                if (it.hasFocus) focusedIndex = index
                                else if (focusedIndex == index) focusedIndex = -1
                            },
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("card:0").assertIsFocused()
        hold(KeyEvent.KEYCODE_DPAD_RIGHT, 90) {
            assertTrue("Every frame must retain a focused card while paging right", focusedIndex >= 0)
            composeRule.onNodeWithContentDescription("card:$focusedIndex").assertIsDisplayed()
        }
        composeRule.onNodeWithContentDescription("card:39").assertIsFocused().assertIsDisplayed()
        hold(KeyEvent.KEYCODE_DPAD_LEFT, 90) {
            assertTrue("Every frame must retain a focused card after reversal", focusedIndex >= 0)
            composeRule.onNodeWithContentDescription("card:$focusedIndex").assertIsDisplayed()
        }
        composeRule.onNodeWithContentDescription("card:0").assertIsFocused().assertIsDisplayed()
    }

    private fun hold(keyCode: Int, count: Int, onFrame: () -> Unit = {}) {
        composeRule.mainClock.autoAdvance = false
        repeat(count) { repeat ->
            composeRule.runOnUiThread {
                view.dispatchKeyEvent(KeyEvent(0L, repeat * 32L, KeyEvent.ACTION_DOWN, keyCode, repeat))
            }
            // Do not wait for scrolling to settle between the native repeat events.
            composeRule.mainClock.advanceTimeBy(32)
            // Flush the Android measure/layout for this frame while auto-advance remains off.
            composeRule.waitForIdle()
            onFrame()
        }
        composeRule.runOnUiThread {
            view.dispatchKeyEvent(KeyEvent(0L, count * 32L, KeyEvent.ACTION_UP, keyCode, 0))
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }
}
