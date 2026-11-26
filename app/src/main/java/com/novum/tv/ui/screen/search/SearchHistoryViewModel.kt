package com.novum.tv.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novum.tv.data.search.SearchHistoryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SearchHistoryViewModel @Inject constructor(
    private val store: SearchHistoryStore,
) : ViewModel() {

    val recentSearches: StateFlow<List<String>> = store.recentSearches.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )

    fun record(query: String) {
        viewModelScope.launch { store.record(query) }
    }

    fun clear() {
        viewModelScope.launch { store.clear() }
    }
}
