package com.novum.tv.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novum.tv.data.repository.MediaRepository
import com.novum.tv.data.repository.PersonIndexStore
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
 * 服务端 Persons 为主要来源，本地索引兜底；本地索引仅包含已浏览条目的演职员。
 *
 * @param repository 媒体仓储，合并服务端与本地人物结果。
 * @param index 本地人物索引。
 */
@HiltViewModel
class PersonSearchViewModel @Inject constructor(
    private val repository: MediaRepository,
    @Suppress("unused") private val index: PersonIndexStore,
) : ViewModel() {

    private val _hits = MutableStateFlow<List<PersonRef>>(emptyList())
    val hits: StateFlow<List<PersonRef>> = _hits.asStateFlow()

    /** 在途搜索：连续输入时旧关键词的慢请求不能覆盖新关键词结果。 */
    private var searchJob: Job? = null

    /** 最近一次请求的关键词，用于拦住已无法取消的迟达结果。 */
    private var latestQuery: String = ""

    /** 关键词变化时由页面防抖后调用（与标题搜索同一节奏）。 */
    fun search(query: String) {
        latestQuery = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val hits = repository.searchPersons(query)
            if (latestQuery == query) _hits.value = hits
        }
    }

    /** 清空（查询词被清掉时）；同时取消在途搜索，避免清空后又被旧结果填回。 */
    fun clear() {
        latestQuery = ""
        searchJob?.cancel()
        searchJob = null
        _hits.value = emptyList()
    }
}
