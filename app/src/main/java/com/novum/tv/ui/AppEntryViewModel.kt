package com.novum.tv.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novum.tv.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 应用入口状态：决定首屏是登录页还是首页。
 *
 * 单独抽 ViewModel 的原因：会话存储在 DataStore 中，读取是异步的；
 * 若直接在 Composable 里 `runBlocking` 会阻塞首帧。
 *
 * @param authRepository 用于恢复本地会话。
 */
@HiltViewModel
class AppEntryViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    /** 入口三态。 */
    enum class Entry {
        /** 正在读取本地会话。 */
        Loading,

        /** 已有会话，直接进首页。 */
        LoggedIn,

        /** 无会话，进登录页。 */
        LoggedOut,
    }

    private val _entry = MutableStateFlow(Entry.Loading)
    val entry: StateFlow<Entry> = _entry.asStateFlow()

    init {
        viewModelScope.launch {
            _entry.value = if (authRepository.restore() == null) Entry.LoggedOut else Entry.LoggedIn
        }
    }
}
