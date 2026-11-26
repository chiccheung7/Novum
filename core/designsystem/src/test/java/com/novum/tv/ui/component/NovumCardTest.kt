package com.novum.tv.ui.component

import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 卡片组件测试：标题渲染、点击回调、可聚焦性。
 *
 * 使用 1920×1080dp 的 TV 画布，避免默认手机尺寸裁掉卡片。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-xhdpi")
class NovumCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsTitleAndSubtitle() {
        composeRule.setContent {
            NovumTheme {
                NovumCard(title = "星际穿越", subtitle = "2014 · 169 分钟", onClick = {})
            }
        }

        composeRule.onNodeWithText("星际穿越").assertIsDisplayed()
        composeRule.onNodeWithText("2014 · 169 分钟").assertIsDisplayed()
    }

    @Test
    fun clickInvokesCallback() {
        var clicked = false
        composeRule.setContent {
            NovumTheme {
                NovumCard(title = "沙丘 2", onClick = { clicked = true })
            }
        }

        // 官方标准卡：点击/索焦语义锚在图区 Card 节点（contentDescription = 片名）。
        // tv-material Surface 无 pointer input（TV 靠 D-pad Enter），点击必须走语义动作
        composeRule.onNodeWithContentDescription("沙丘 2")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertTrue("卡片点击回调未触发", clicked)
    }

    @Test
    fun canTakeFocus() {
        composeRule.setContent {
            NovumTheme {
                NovumCard(title = "焦点测试", onClick = {})
            }
        }

        // 官方卡基座的语义树自带 RequestFocus 动作（官方 focusTarget 合并上来）
        composeRule.onNodeWithContentDescription("焦点测试")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("焦点测试").assertIsFocused()
    }

    @Test
    fun forwardsModifierFocusRequesterToTextCard() {
        lateinit var requester: FocusRequester
        composeRule.setContent {
            NovumTheme {
                requester = remember { FocusRequester() }
                NovumCard(
                    title = "修饰符焦点测试",
                    modifier = Modifier.focusRequester(requester),
                    onClick = {},
                )
                LaunchedEffect(requester) { requester.requestFocus() }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("修饰符焦点测试").assertIsFocused()
    }

    @Test
    fun forwardsModifierFocusRequesterToImageOnlyCard() {
        lateinit var requester: FocusRequester
        composeRule.setContent {
            NovumTheme {
                requester = remember { FocusRequester() }
                NovumCard(
                    title = "纯图焦点测试",
                    showText = false,
                    modifier = Modifier.focusRequester(requester),
                    onClick = {},
                )
                LaunchedEffect(requester) { requester.requestFocus() }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("纯图焦点测试").assertIsFocused()
    }
}
