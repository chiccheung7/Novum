package com.novum.tv.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novum.tv.core.error.NovumError
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.result.runCatchingSuspend
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.AuthRepository
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.model.HomeFeed
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel。
 *
 * 加载策略：一次请求聚合五个区块（见 [MediaRepository.feed]）
 * 成功但整页无内容时展示空态，失败时展示错误与重试入口。
 *
 * 收藏入口位于详情页，首页只负责浏览。
 *
 * @param repository 媒体库仓储。
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _feed = MutableStateFlow<UiState<HomeFeed>>(UiState.Loading)
    val feed: StateFlow<UiState<HomeFeed>> = _feed.asStateFlow()

    /**
     * 首焦重锚版本号（刷新后首焦副作用重跑，把焦点拽回 Hero）：
     * 首次加载或显式重试成功后递增；静默刷新不移动用户焦点。
     */
    private val _focusEpoch = MutableStateFlow(0)
    val focusEpoch: StateFlow<Int> = _focusEpoch.asStateFlow()

    private val _reauthenticating = MutableStateFlow(false)
    val reauthenticating: StateFlow<Boolean> = _reauthenticating.asStateFlow()

    private val _reauthenticationError = MutableStateFlow<NovumError?>(null)
    val reauthenticationError: StateFlow<NovumError?> = _reauthenticationError.asStateFlow()

    private var feedJob: Job? = null
    private var feedRequest = 0

    init {
        load()
    }

    /** 首次加载或错误态重试。 */
    fun load() {
        requestFeed(silent = false)
    }

    /**
     * 回到首页时的静默刷新：向服务器确认「继续观看」
     * 列等区块的现况——在详情页标为已看/收藏后，回到首页要立刻反映。
     *
     * 不进 Loading 态（骨架屏闪烁），刷新期间保留现有内容；失败静默保留原状。
     * 仅在已有成功数据时执行——首载与错误态重试走 [load]。
     *
     * 静默刷新不递增 [focusEpoch]，避免网络请求完成时把焦点从用户当前所在位置移走。
     * 回到首页时由页面重新组合触发初始焦点逻辑，和数据刷新解耦。
     */
    fun refresh() {
        if (_feed.value !is UiState.Success) return
        requestFeed(silent = true)
    }

    /** Only an explicit action on a rejected session clears it; network failures keep the session. */
    fun reauthenticate(onDone: () -> Unit) {
        val state = _feed.value
        if (state !is UiState.Error || state.error !is NovumError.Unauthorized || _reauthenticating.value) return
        _reauthenticating.value = true
        _reauthenticationError.value = null
        // An in-flight feed must not restore protected content after the session is cleared.
        feedRequest += 1
        feedJob?.cancel()
        viewModelScope.launch {
            val result = runCatchingSuspend { authRepository.logout() }
            _reauthenticating.value = false
            result.onSuccess { onDone() }
                .onFailure { _reauthenticationError.value = NovumError.Unknown(it) }
        }
    }

    /** 页面反复恢复或重试时，仅最新请求可以提交状态。 */
    private fun requestFeed(silent: Boolean) {
        val request = ++feedRequest
        feedJob?.cancel()
        if (!silent) _feed.update { UiState.Loading }
        feedJob = viewModelScope.launch {
            val result = repository.feed()
            if (request != feedRequest) return@launch
            when (result) {
                is NovumResult.Success -> {
                    _feed.update {
                        if (result.value.isEmpty) UiState.Empty() else UiState.Success(result.value)
                    }
                    if (!silent) _focusEpoch.value += 1
                }

                is NovumResult.Failure -> {
                    // A revoked token must expose recovery even on a quiet return-to-home refresh.
                    if (!silent || result.error is NovumError.Unauthorized) {
                        _feed.update { UiState.Error(result.error) }
                    }
                }
            }
        }
    }
}
