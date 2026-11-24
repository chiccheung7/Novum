package com.novum.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novum.tv.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 外观相关设置（目前只有「界面大小」）。
 *
 * 单独抽 ViewModel 的原因：视口缩放挂在 [NovumApp] 最外层，
 * 必须在主题与导航之外读到设置流；直接用 `runBlocking` 会在首帧阻塞磁盘读取。
 *
 * @param settingsRepository 设置仓储。
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {

    /**
     * 界面大小档位（1 = 标准）。
     *
     * 初始值取 1：设置是异步读取的，先按标准画布渲染首帧再按用户档位校正，
     * 避免启动瞬间的尺寸跳变方向不可控（读盘通常在第一帧后即完成）。
     */
    val uiScale: StateFlow<Float> = settingsRepository.settings
        .map { it.uiScale }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1f)
}
