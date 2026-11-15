package com.novum.tv

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 启动链路仪器化测试：验证 Hilt 注入、单 Activity 挂载与首页渲染。
 *
 * 运行方式：连接电视/模拟器后执行 `./gradlew :app:connectedDebugAndroidTest`。
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** 顶栏入口必须可见。 */
    @Test
    fun showsTopBarEntries() {
        composeRule.onNodeWithContentDescription("收藏").assertIsDisplayed()
    }

    /** 品牌标语必须可见，确保导航起始页正确。 */
    @Test
    fun showsScaffoldStatus() {
        composeRule.onNodeWithText("为热爱观影的人打造私人影音世界").assertIsDisplayed()
    }
}
