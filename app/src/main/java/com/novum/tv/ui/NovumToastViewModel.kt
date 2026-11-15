package com.novum.tv.ui

import androidx.lifecycle.ViewModel
import com.novum.tv.ui.component.NovumToastController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 全局 Toast 控制器的 Compose 侧载体。
 *
 * 控制器实例由 [com.novum.tv.di.AppModule] 以应用级单例提供：
 * - Compose 侧经 `hiltViewModel()` 在根组合拿到同一实例并提供给 CompositionLocal；
 * - ViewModel 侧直接 `@Inject` 注入 [NovumToastController] 或 `ToastSink` 使用；
 * - 导航切换目的地不影响未消失的消息。
 */
@HiltViewModel
class NovumToastViewModel @Inject constructor(
    val controller: NovumToastController,
) : ViewModel()
