package com.novum.tv.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.data.repository.PersonRef
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 人物搜索 ViewModel，与标题搜索并行。
 * 人物结果只取服务器 Persons 响应。
 *
 * @param repository 媒体仓储。
 */
@HiltViewModel
class PersonSearchViewModel @Inject constructor(
    private val repository: MediaRepository,
) : ViewModel() {

    private val _hits = MutableStateFlow<List<PersonRef>>(emptyList())
    val hits: StateFlow<List<PersonRef>> = _hits.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** 在途搜索：连续输入时旧关键词的慢请求不能覆盖新关键词结果。 */
    private var searchJob: Job? = null
    private var currentQuery: String? = null

    /** 每次调用独立编号；同一关键词也可能有多次尚未返回的请求。 */
    private var requestSeq = 0L

    /** 页面恢复时复用已有结果或在途请求；全新 ViewModel 会正常发起搜索。 */
    fun start(query: String) {
        if (currentQuery == query) return
        search(query)
    }

    /** 关键词变化时由页面防抖后调用（与标题搜索同一节奏）。 */
    fun search(query: String) {
        currentQuery = query
        val seq = ++requestSeq
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _loading.value = true
            try {
                val hits = repository.searchPersons(query)
                if (requestSeq == seq) _hits.value = hits
            } finally {
                if (requestSeq == seq) _loading.value = false
            }
        }
    }

    /** 清空（查询词被清掉时）；同时取消在途搜索，避免清空后又被旧结果填回。 */
    fun clear() {
        currentQuery = null
        requestSeq++
        searchJob?.cancel()
        searchJob = null
        _loading.value = false
        _hits.value = emptyList()
    }
}
