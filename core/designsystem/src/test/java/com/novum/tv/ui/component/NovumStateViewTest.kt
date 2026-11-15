package com.novum.tv.ui.component

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.state.UiState
import androidx.tv.material3.Text
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 状态组件测试：错误文案映射与重试回调。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-xhdpi")
class NovumStateViewTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsNetworkErrorMessage() {
        composeRule.setContent {
            NovumTheme {
                NovumStateView(state = UiState.Error(NovumError.Network()), onRetry = {})
            }
        }

        composeRule.onNodeWithText("网络连接失败，请检查网络后重试").assertIsDisplayed()
        composeRule.onNodeWithText("重试").assertIsDisplayed()
    }

    @Test
    fun retryInvokesCallback() {
        var retried = false
        composeRule.setContent {
            NovumTheme {
                NovumStateView(
                    state = UiState.Error(NovumError.Server(code = 500)),
                    onRetry = { retried = true },
                )
            }
        }

        composeRule.onNodeWithText("重试").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertTrue("重试回调未触发", retried)
    }

    @Test
    fun loadingRendersSkeletonInsteadOfText() {
        composeRule.setContent {
            NovumTheme { NovumStateView(state = UiState.Loading, onRetry = {}) }
        }

        // 加载态统一使用骨架屏，不显示「正在加载…」文字。
        composeRule.onNodeWithText("正在加载…").assertDoesNotExist()
    }

    @Test
    fun loadingUsesCustomSkeletonSlot() {
        composeRule.setContent {
            NovumTheme {
                NovumStateView(
                    state = UiState.Loading,
                    onRetry = {},
                    loading = { Text("grid-skeleton") },
                )
            }
        }

        composeRule.onNodeWithText("grid-skeleton").assertIsDisplayed()
    }
}
