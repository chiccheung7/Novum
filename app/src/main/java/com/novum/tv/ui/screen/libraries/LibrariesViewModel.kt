package com.novum.tv.ui.screen.libraries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novum.tv.core.result.NovumResult
import com.novum.tv.core.state.UiState
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.model.MediaLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 媒体库页 UI 状态：影片/剧集类库，追新聚合源由首页展示。
 */
typealias LibrariesUiState = UiState<List<MediaLibrary>>

/**
 * 媒体库目录 ViewModel。
 *
 * @param repository 媒体库仓储。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibrariesViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val reloadTrigger = MutableStateFlow(0)

    val state: StateFlow<LibrariesUiState> = reloadTrigger
        .flatMapLatest {
            kotlinx.coroutines.flow.flow { emit(repository.libraries()) }.map { result ->
                when (result) {
                    is NovumResult.Success -> {
                        val libraries: List<MediaLibrary> = result.value
                            .filter {
                                it.kind == "movies" || it.kind == "tvshows"
                            }
                            // 聚合源的封面标记可能存在但图片返回 404，不能据此保留空卡。
                            // 它继续供首页的近期热播和 Hero 使用，不作为独立分库入口。
                            .filterNot { it.isRecentUpdatesSource }
                        if (libraries.isEmpty()) {
                            UiState.Empty()
                        } else {
                            UiState.Success(libraries)
                        }
                    }

                    is NovumResult.Failure -> UiState.Error(result.error)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    /** 错误态重试。 */
    fun reload() {
        reloadTrigger.value++
    }
}
