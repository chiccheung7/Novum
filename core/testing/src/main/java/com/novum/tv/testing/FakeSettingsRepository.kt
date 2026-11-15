package com.novum.tv.testing

import com.novum.tv.data.settings.AppSettings
import com.novum.tv.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 设置仓储的内存假实现，用于 ViewModel 测试。
 *
 * 行为与 DataStore 实现保持一致：读取即时、写入原子。
 *
 * @param initial 初始设置快照。
 */
class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {

    private val state = MutableStateFlow(initial)

    override val settings: Flow<AppSettings> = state.asStateFlow()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}
