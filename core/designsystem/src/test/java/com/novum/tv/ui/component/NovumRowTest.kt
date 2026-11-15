package com.novum.tv.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.novum.tv.ui.theme.NovumTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 横向内容行测试：标题与首屏卡片渲染。
 *
 * 焦点自动滚动依赖 `bringIntoView`，本测试只验证结构与渲染。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1920dp-h1080dp-xhdpi")
class NovumRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsHeaderAndFirstCards() {
        composeRule.setContent {
            NovumTheme {
                NovumRow(
                    elements = listOf("第一张", "第二张", "第三张"),
                    key = { it },
                    title = "继续观看",
                    trailing = "共 3 部",
                ) { _, item ->
                    NovumCard(title = item, onClick = {})
                }
            }
        }

        composeRule.onNodeWithText("继续观看").assertIsDisplayed()
        composeRule.onNodeWithText("共 3 部").assertIsDisplayed()
        composeRule.onNodeWithText("第一张").assertIsDisplayed()
    }
}
