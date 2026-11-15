package com.novum.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.novum.tv.perf.attachJankStats
import com.novum.tv.ui.NovumApp
import dagger.hilt.android.AndroidEntryPoint

/**
 * Novum 的唯一 Activity：单 Activity + 纯 Compose 架构的宿主。
 *
 * 职责边界：
 * - 只负责窗口级配置（edge-to-edge）与 Compose 内容挂载；
 * - 不承载任何业务逻辑，页面切换与状态管理分别由 Navigation 与 ViewModel 承担。
 *
 * TV 应用不存在多 Activity 跳转，页面通过 [com.novum.tv.navigation.NovumNavHost] 注册路由。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * 创建 Activity 并挂载 Compose 内容树。
     *
     * @param savedInstanceState 系统重建时恢复的状态。
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // JankStats 采样：debug 变体挂真监听，release 源集 no-op（变体优先级解析）
        attachJankStats(window)
        setContent {
            NovumApp()
        }
    }
}
