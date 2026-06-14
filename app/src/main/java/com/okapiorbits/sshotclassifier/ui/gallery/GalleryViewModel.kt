package com.okapiorbits.sshotclassifier.ui.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okapiorbits.sshotclassifier.data.db.ScreenshotWithTags
import com.okapiorbits.sshotclassifier.data.repository.ScreenshotRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GalleryUiState(
    val isSyncing: Boolean = false,
    val lastSyncAdded: Int? = null,
)

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val repository: ScreenshotRepository,
) : ViewModel() {

    val screenshots: StateFlow<List<ScreenshotWithTags>> =
        repository.observeGallery()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    /** Manual trigger: scan MediaStore for new screenshots and index them. */
    fun sync() {
        if (_uiState.value.isSyncing) return
        viewModelScope.launch {
            _uiState.value = GalleryUiState(isSyncing = true)
            val added = runCatching { repository.syncFromMediaStore() }.getOrDefault(0)
            _uiState.value = GalleryUiState(isSyncing = false, lastSyncAdded = added)
        }
    }
}
