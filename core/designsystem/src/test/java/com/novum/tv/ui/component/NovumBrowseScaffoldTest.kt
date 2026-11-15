package com.novum.tv.ui.component

import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 浏览页骨架测试：覆盖行下标自动分配、firstFocus=null 容错、空行组和首焦重试。
 * 真实滚动行为需在设备上验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-xhdpi")
class NovumBrowseScaffoldTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun rowIndicesAreAssignedAcrossMixedRegistration() {
        composeRule.setContent {
            NovumTheme {
                NovumBrowseScaffold {
                    row(key = "a") { r -> Text("row:$r") }
                    row(key = "b") { r -> Text("row:$r") }
                    rows(count = 2, key = { "c$it" }) { r, index -> Text("group:$r:$index") }
                }
            }
        }

        // 单行与行组混排：绝对下标连续分配（0/1/2/3），组内下标独立从 0 起
        composeRule.onNodeWithText("row:0").assertIsDisplayed()
        composeRule.onNodeWithText("row:1").assertIsDisplayed()
        composeRule.onNodeWithText("group:2:0").assertIsDisplayed()
        composeRule.onNodeWithText("group:3:1").assertIsDisplayed()
    }

    @Test
    fun emptyGroupAndNullFirstFocusRenderWithoutCrash() {
        composeRule.setContent {
            NovumTheme {
                NovumBrowseScaffold(firstFocus = null, topInset = 36.dp) {
                    rows(count = 0, key = { "x$it" }) { _, _ -> }
                    row { Text("only") }
                }
            }
        }

        // 空行组不占下标：后续行仍是 0 号；firstFocus=null 时骨架不抓焦不崩溃
        composeRule.onNodeWithText("only").assertIsDisplayed()
    }

    @Test
    fun firstFocusGrabsFocusAfterMount() {
        val focus = FocusRequester()
        composeRule.setContent {
            NovumTheme {
                NovumBrowseScaffold(firstFocus = focus) {
                    row(key = "target") {
                        Text(
                            text = "target",
                            modifier = Modifier.focusRequester(focus).focusable(),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // 首焦重试机制（NovumInitialFocus）在目标组合完成后抓到焦点
        composeRule.onNodeWithText("target").assertIsFocused()
    }
}
