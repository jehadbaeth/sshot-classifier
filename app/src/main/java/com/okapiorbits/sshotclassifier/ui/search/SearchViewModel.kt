package com.okapiorbits.sshotclassifier.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okapiorbits.sshotclassifier.data.db.ScreenshotWithTags
import com.okapiorbits.sshotclassifier.data.db.TagCount
import com.okapiorbits.sshotclassifier.data.repository.ScreenshotRepository
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: ScreenshotRepository,
    modelManager: ClipModelManager,
) : ViewModel() {

    /** Whether free-text visual search is available (text model installed). */
    val semanticReady: Boolean = modelManager.isTextModelInstalled()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    val tagCounts: StateFlow<List<TagCount>> =
        repository.observeTagCounts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val results: StateFlow<List<ScreenshotWithTags>> =
        combine(_query.debounce(250), _selectedTag) { q, tag -> q to tag }
            .flatMapLatest { (q, tag) ->
                when {
                    tag != null -> repository.observeByTag(tag)
                    q.isNotBlank() -> flow { emit(repository.hybridSearch(q)) }
                    else -> flowOf(emptyList())
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(q: String) {
        _query.value = q
        if (q.isNotBlank()) _selectedTag.value = null
    }

    fun toggleTag(label: String) {
        _selectedTag.value = if (_selectedTag.value == label) null else label
        if (_selectedTag.value != null) _query.value = ""
    }
}
