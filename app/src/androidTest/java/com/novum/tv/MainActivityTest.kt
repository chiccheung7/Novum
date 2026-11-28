package com.novum.tv

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 启动链路仪器化测试：验证全新安装无会话时进入登录页。
 *
 * 运行方式：连接电视/模拟器后执行 `./gradlew :app:connectedDebugAndroidTest`。
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** 全新安装没有会话，必须显示登录表单而不是跳到首页。 */
    @Test
    fun showsLoginForm() {
        composeRule.onNodeWithText("服务器地址").assertIsDisplayed()
        composeRule.onAllNodesWithText("登录").onFirst().assertIsDisplayed()
    }

    /** 未登录时首页入口不应被构造。 */
    @Test
    fun doesNotShowAuthenticatedHomeActions() {
        composeRule.onNodeWithContentDescription("收藏").assertDoesNotExist()
    }
}
