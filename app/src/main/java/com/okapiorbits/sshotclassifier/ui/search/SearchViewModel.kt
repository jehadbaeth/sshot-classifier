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
import kotlinx.coroutines.flow.map
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

    private val _selectedTags = MutableStateFlow<Set<String>>(emptySet())
    val selectedTags: StateFlow<Set<String>> = _selectedTags.asStateFlow()

    val tagCounts: StateFlow<List<TagCount>> =
        repository.observeTagCounts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val results: StateFlow<List<ScreenshotWithTags>> =
        combine(_query.debounce(250), _selectedTags) { q, tags -> q to tags }
            .flatMapLatest { (q, tags) ->
                when {
                    // Multiple tags = intersection: an image must carry ALL selected tags.
                    tags.isNotEmpty() -> repository.observeGallery().map { list ->
                        list.filter { s -> tags.all { t -> s.tags.any { it.label == t } } }
                    }
                    q.isNotBlank() -> flow { emit(repository.hybridSearch(q)) }
                    else -> flowOf(emptyList())
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(q: String) {
        _query.value = q
        if (q.isNotBlank()) _selectedTags.value = emptySet()
    }

    fun toggleTag(label: String) {
        _selectedTags.value = _selectedTags.value.let { if (label in it) it - label else it + label }
        if (_selectedTags.value.isNotEmpty()) _query.value = ""
    }

    fun clearTags() {
        _selectedTags.value = emptySet()
    }
}
