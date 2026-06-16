package com.okapiorbits.sshotclassifier.ui.gallery

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okapiorbits.sshotclassifier.data.db.ScreenshotWithTags
import com.okapiorbits.sshotclassifier.data.repository.ScreenshotRepository
import com.okapiorbits.sshotclassifier.monitoring.ScreenshotProcessingWorker
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipModelDownloader
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.okapiorbits.sshotclassifier.data.db.entity.SourceType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ScreenshotRepository,
    private val modelManager: ClipModelManager,
    private val downloader: ClipModelDownloader,
) : ViewModel() {

    /** When true, the grid shows only screenshots flagged for review. */
    private val _reviewOnly = MutableStateFlow(false)
    val reviewOnly: StateFlow<Boolean> = _reviewOnly.asStateFlow()

    /** Source filter: null = all, or a [SourceType] name to show only that source. */
    private val _sourceFilter = MutableStateFlow<String?>(null)
    val sourceFilter: StateFlow<String?> = _sourceFilter.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val screenshots: StateFlow<List<ScreenshotWithTags>> =
        combine(_reviewOnly, _sourceFilter, ::Pair)
            .flatMapLatest { (only, source) ->
                val base = if (only) repository.observeNeedsReview() else repository.observeGallery()
                if (source == null) base
                else base.map { list -> list.filter { it.screenshot.source_type == source } }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Number of camera captures; the source filter chips only appear when there are some. */
    val captureCount: StateFlow<Int> =
        repository.observeCaptureCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setSourceFilter(source: String?) {
        _sourceFilter.value = source
    }

    val sourceTypes: Pair<String, String> = SourceType.SCREENSHOT.name to SourceType.CAMERA.name

    val pendingCount: StateFlow<Int> =
        repository.observePendingCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Screenshots tagged before the visual model was installed (no embedding yet). */
    val reprocessableCount: StateFlow<Int> =
        repository.observeReprocessableCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Count flagged for human review (uncertain auto-tagging). */
    val needsReviewCount: StateFlow<Int> =
        repository.observeNeedsReviewCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun toggleReviewOnly() {
        _reviewOnly.value = !_reviewOnly.value
    }

    private val _modelState = MutableStateFlow(
        if (modelManager.areAllModelsInstalled()) ModelState.Installed else ModelState.Missing
    )
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    fun scan() {
        ScreenshotProcessingWorker.enqueue(context)
    }

    /**
     * Re-runs already-tagged screenshots that have no image embedding through the
     * pipeline so they gain CLIP tags and become visible to visual search. Only
     * meaningful once the image model is installed.
     */
    fun reprocessMissing() {
        viewModelScope.launch {
            val count = repository.markForReprocessing()
            if (count > 0) ScreenshotProcessingWorker.enqueue(context)
        }
    }

    fun refreshModelState() {
        if (modelManager.areAllModelsInstalled()) _modelState.value = ModelState.Installed
    }

    fun downloadModel() {
        if (_modelState.value is ModelState.Downloading) return
        viewModelScope.launch {
            _modelState.value = ModelState.Downloading(0f)
            val result = downloader.download { p -> _modelState.value = ModelState.Downloading(p) }
            _modelState.value = when (result) {
                is ClipModelDownloader.State.Done -> ModelState.Installed
                is ClipModelDownloader.State.Failed -> ModelState.Error(result.message)
                else -> ModelState.Missing
            }
        }
    }
}

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val progress: Float) : ModelState
    data object Installed : ModelState
    data class Error(val message: String) : ModelState
}
