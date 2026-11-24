package com.novum.tv.ui.screen.person

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.model.MediaItem
import com.novum.tv.navigation.PersonRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 人物作品页状态。
 *
 * @param detail 作品列表加载态。
 */
data class PersonUiState(
    val detail: UiState<List<MediaItem>> = UiState.Loading,
)

/**
 * 人物作品页 ViewModel：按人物条目 id 使用 PersonIds 过滤并拉取作品。
 *
 * @param repository 媒体仓储。
 * @param savedStateHandle 路由参数（[PersonRoute]）。
 */
@HiltViewModel
class PersonViewModel @Inject constructor(
    private val repository: MediaRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    /** 人物条目 id。 */
    val personId: String = savedStateHandle.toRoute<PersonRoute>().personId

    private val _state = MutableStateFlow(PersonUiState())
    val state: StateFlow<PersonUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** 在途请求：连续点「重试」只保留最后一次，避免旧结果覆盖新状态。 */
    private var loadJob: Job? = null

    /** 首次加载或错误态重试。 */
    fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = PersonUiState(UiState.Loading)
            _state.value = when (val result = repository.personWorks(personId)) {
                is NovumResult.Success -> PersonUiState(UiState.Success(result.value))
                is NovumResult.Failure -> PersonUiState(UiState.Error(result.error))
            }
        }
    }
}
