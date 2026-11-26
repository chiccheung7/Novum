package com.novum.tv.ui.component

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.runtime.mutableStateOf
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
 * 线路选择面板测试：覆盖渲染、选中态、单选回调及加载/失败态。
 * 直接驱动 [NovumLinePickerPanel]，不启动 Dialog 窗口。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-xhdpi")
class NovumLinePickerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleLines = listOf(
        LineOption("https://hk.example.com", "香港线路", "hk.example.com", 45L),
        LineOption("https://us.example.com", "美国线路", "us.example.com", 180L),
    )

    @Test
    fun showsLinesWithLatencyAndPinnedCurrent() {
        composeRule.setContent {
            NovumTheme {
                NovumLinePickerPanel(
                    lines = sampleLines,
                    pinnedUrl = "https://us.example.com",
                    onSelectLine = {},
                    loading = false,
                    failed = false,
                )
            }
        }

        composeRule.onNodeWithText("选择线路").assertIsDisplayed()
        composeRule.onNodeWithText("香港线路").assertIsDisplayed()
        composeRule.onNodeWithText("美国线路").assertIsDisplayed()
        // 线路延迟显示在列表行中。
        composeRule.onNodeWithText("180ms").assertIsDisplayed()
        // 锁定线路 = 当前项（行 contentDescription）
        composeRule.onNodeWithContentDescription("美国线路（当前）").assertIsDisplayed()
    }

    @Test
    fun lowestLatencyLineIsCurrentWhenNotPinned() {
        composeRule.setContent {
            NovumTheme {
                NovumLinePickerPanel(
                    lines = sampleLines,
                    pinnedUrl = null,
                    onSelectLine = {},
                    loading = false,
                    failed = false,
                )
            }
        }

        // 未锁线时当前项 = 延迟最低线路（香港 45ms），无「自动」行
        composeRule.onNodeWithContentDescription("香港线路（当前）").assertIsDisplayed()
    }

    @Test
    fun arrivingLinesReceiveFocusAfterLoading() {
        val loading = mutableStateOf(true)
        composeRule.setContent {
            NovumTheme {
                NovumLinePickerPanel(
                    lines = sampleLines.takeUnless { loading.value },
                    pinnedUrl = sampleLines[1].baseUrl,
                    onSelectLine = {},
                    loading = loading.value,
                    failed = false,
                )
            }
        }
        composeRule.onNodeWithText("正在获取线路…").assertIsDisplayed()
        composeRule.runOnIdle { loading.value = false }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("美国线路（当前）").assertIsFocused()
    }

    @Test
    fun missingPinnedLineFallsBackToAnAvailableFocusTarget() {
        composeRule.setContent {
            NovumTheme {
                NovumLinePickerPanel(
                    lines = sampleLines,
                    pinnedUrl = "https://removed.example.com",
                    onSelectLine = {},
                    loading = false,
                    failed = false,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("香港线路（当前）").assertIsFocused()
    }

    @Test
    fun selectingLineInvokesCallback() {
        var selected: LineOption? = null
        composeRule.setContent {
            NovumTheme {
                NovumLinePickerPanel(
                    lines = sampleLines,
                    pinnedUrl = null,
                    onSelectLine = { selected = it },
                    loading = false,
                    failed = false,
                )
            }
        }

        composeRule
            .onNodeWithText("香港线路")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()

        assertTrue("选中线路应把该线路交给回调", selected?.baseUrl == "https://hk.example.com")
    }

    @Test
    fun loadingStateShowsHint() {
        composeRule.setContent {
            NovumTheme {
                NovumLinePickerPanel(
                    lines = null,
                    pinnedUrl = null,
                    onSelectLine = {},
                    loading = true,
                    failed = false,
                )
            }
        }

        composeRule.onNodeWithText("正在获取线路…").assertIsDisplayed()
    }

    @Test
    fun failedStateShowsHints() {
        composeRule.setContent {
            NovumTheme {
                NovumLinePickerPanel(
                    lines = null,
                    pinnedUrl = null,
                    onSelectLine = {},
                    loading = false,
                    failed = true,
                )
            }
        }

        composeRule.onNodeWithText("线路列表获取失败").assertIsDisplayed()
        composeRule.onNodeWithText("将沿用当前服务器地址").assertIsDisplayed()
    }
}
