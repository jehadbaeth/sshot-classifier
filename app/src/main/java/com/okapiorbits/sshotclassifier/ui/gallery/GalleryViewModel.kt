package com.okapiorbits.sshotclassifier.ui.gallery

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
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
import kotlinx.coroutines.FlowPreview
import com.okapiorbits.sshotclassifier.data.db.entity.SourceType
import kotlinx.coroutines.flow.Flow
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

    /** When true, show only near-duplicate images, grouped together (computed on toggle). */
    private val _duplicatesOnly = MutableStateFlow(false)
    val duplicatesOnly: StateFlow<Boolean> = _duplicatesOnly.asStateFlow()
    private val _duplicateIds = MutableStateFlow<List<Long>>(emptyList())

    /** Number of near-duplicate groups found by the last toggle; 0 until computed. */
    private val _duplicateGroupCount = MutableStateFlow(0)
    val duplicateGroupCount: StateFlow<Int> = _duplicateGroupCount.asStateFlow()

    /** How the grid is ordered. */
    private val _sortOrder = MutableStateFlow(SortOrder.NEWEST)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()
    fun setSortOrder(order: SortOrder) { _sortOrder.value = order }

    private data class Filters(
        val reviewOnly: Boolean,
        val source: String?,
        val duplicatesOnly: Boolean,
        val duplicateIds: List<Long>,
        val sort: SortOrder,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val baseScreenshots: Flow<List<ScreenshotWithTags>> =
        combine(_reviewOnly, _sourceFilter, _duplicatesOnly, _duplicateIds, _sortOrder) {
            r, s, d, ids, sort -> Filters(r, s, d, ids, sort)
        }
            .flatMapLatest { f ->
                val base = if (f.reviewOnly) repository.observeNeedsReview() else repository.observeGallery()
                base.map { list ->
                    var out = list
                    if (f.source != null) out = out.filter { it.screenshot.source_type == f.source }
                    if (f.duplicatesOnly) {
                        // Keep only grouped images, ordered so group members sit adjacent.
                        val order = f.duplicateIds.withIndex().associate { (i, id) -> id to i }
                        out = out.filter { it.screenshot.id in order }
                            .sortedBy { order[it.screenshot.id] }
                    } else {
                        // User-chosen ordering (duplicates view keeps its grouped order).
                        out = when (f.sort) {
                            SortOrder.NEWEST -> out.sortedByDescending { it.screenshot.date_added }
                            SortOrder.OLDEST -> out.sortedBy { it.screenshot.date_added }
                            SortOrder.RECENTLY_PROCESSED ->
                                out.sortedByDescending { it.screenshot.date_processed ?: 0L }
                        }
                    }
                    out
                }
            }

    // ---- Search (folded in from the old Search tab): text query + multi-tag filter ----

    /** Whether free-text visual search is available (text model installed). */
    val semanticReady: Boolean = modelManager.isTextModelInstalled()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    fun setQuery(q: String) { _query.value = q }

    private val _selectedTags = MutableStateFlow<Set<String>>(emptySet())
    val selectedTags: StateFlow<Set<String>> = _selectedTags.asStateFlow()
    fun toggleTag(label: String) {
        _selectedTags.value = _selectedTags.value.let { if (label in it) it - label else it + label }
    }

    /** Tag chips with counts, for the gallery filter row. */
    val tagCounts: StateFlow<List<com.okapiorbits.sshotclassifier.data.db.TagCount>> =
        repository.observeTagCounts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** True when a text query or tag filter is narrowing the grid. */
    val searchActive: StateFlow<Boolean> =
        combine(_query, _selectedTags) { q, t -> q.isNotBlank() || t.isNotEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val screenshots: StateFlow<List<ScreenshotWithTags>> =
        combine(_query.debounce(250), _selectedTags, baseScreenshots) { q, tags, base -> Triple(q, tags, base) }
            .flatMapLatest { (q, tags, base) ->
                fun hasAll(s: ScreenshotWithTags) = tags.all { t -> s.tags.any { it.label == t } }
                when {
                    // Text query: hybrid (visual + OCR) search, optionally narrowed by selected tags.
                    q.isNotBlank() -> flow {
                        val r = repository.hybridSearch(q)
                        emit(if (tags.isEmpty()) r else r.filter(::hasAll))
                    }
                    // Tags only: intersect the current (filtered/sorted) gallery.
                    tags.isNotEmpty() -> flowOf(base.filter(::hasAll))
                    else -> flowOf(base)
                }
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

    /**
     * Toggle the near-duplicate filter. Turning it on computes groups from the stored CLIP
     * embeddings (a one-off pass) and shows only grouped images; turning it off clears it.
     */
    fun toggleDuplicatesOnly() {
        if (_duplicatesOnly.value) {
            _duplicatesOnly.value = false
            return
        }
        viewModelScope.launch {
            val groups = repository.findDuplicateGroups()
            _duplicateIds.value = groups.flatten()
            _duplicateGroupCount.value = groups.size
            _duplicatesOnly.value = true
        }
    }

    val pendingCount: StateFlow<Int> =
        repository.observePendingCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Progress of the current processing batch, for a determinate bar in the gallery. Derives a
     * batch total from the high-water-mark of the pending count (which we can't otherwise know),
     * so `done/total` advances as the worker drains the queue and resets when it empties.
     */
    private var batchTotal = 0
    val processing: StateFlow<ProcessingState> =
        repository.observePendingCount().map { pending ->
            if (pending <= 0) {
                batchTotal = 0
                ProcessingState(active = false, done = 0, total = 0)
            } else {
                if (pending > batchTotal) batchTotal = pending
                ProcessingState(active = true, done = batchTotal - pending, total = batchTotal)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProcessingState(false, 0, 0))

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

    // ---- Multi-select (long-press to enter; bulk actions) ----

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    fun toggleSelected(id: Long) {
        _selectedIds.value = _selectedIds.value.let { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun selectAll() {
        _selectedIds.value = screenshots.value.map { it.screenshot.id }.toSet()
    }

    /** One-shot result of a bulk tag-add, so the UI can confirm it with an Undo snackbar. */
    data class BulkTagEvent(val label: String, val ids: Set<Long>)
    private val _bulkTagEvent = MutableStateFlow<BulkTagEvent?>(null)
    val bulkTagEvent: StateFlow<BulkTagEvent?> = _bulkTagEvent.asStateFlow()
    fun clearBulkTagEvent() { _bulkTagEvent.value = null }

    /** Adds one tag to every selected image (bulk), then clears the selection. */
    fun addTagToSelected(label: String) {
        val ids = _selectedIds.value
        if (ids.isEmpty() || label.isBlank()) return
        viewModelScope.launch {
            ids.forEach { repository.addUserTag(it, label) }
            _selectedIds.value = emptySet()
            _bulkTagEvent.value = BulkTagEvent(label.trim().lowercase(), ids)
        }
    }

    /** Undo a bulk tag-add: remove that tag from the images it was applied to. */
    fun undoBulkTag(event: BulkTagEvent) {
        viewModelScope.launch { repository.removeUserTagFromAll(event.ids, event.label) }
        _bulkTagEvent.value = null
    }

    /** Content URIs of the current selection, for a bulk share or delete intent. */
    fun selectedUris(): List<Uri> {
        val ids = _selectedIds.value
        return screenshots.value
            .filter { it.screenshot.id in ids }
            .map { Uri.parse(it.screenshot.file_path) }
    }

    /** One-shot result of a bulk tag-remove, so the UI can confirm it with an Undo snackbar. */
    data class BulkRemoveTagEvent(val label: String, val ids: Set<Long>)
    private val _bulkRemoveTagEvent = MutableStateFlow<BulkRemoveTagEvent?>(null)
    val bulkRemoveTagEvent: StateFlow<BulkRemoveTagEvent?> = _bulkRemoveTagEvent.asStateFlow()
    fun clearBulkRemoveTagEvent() { _bulkRemoveTagEvent.value = null }

    /** Removes [label] from every selected image (any source), then clears the selection. */
    fun removeTagFromSelected(label: String) {
        val ids = _selectedIds.value
        if (ids.isEmpty() || label.isBlank()) return
        val normalised = label.trim().lowercase()
        viewModelScope.launch {
            repository.removeTagFromAll(ids, normalised)
            _selectedIds.value = emptySet()
            _bulkRemoveTagEvent.value = BulkRemoveTagEvent(normalised, ids)
        }
    }

    /** Undo a bulk tag-remove: re-adds that tag to the images it was removed from. */
    fun undoBulkRemoveTag(event: BulkRemoveTagEvent) {
        viewModelScope.launch { event.ids.forEach { repository.addUserTag(it, event.label) } }
        _bulkRemoveTagEvent.value = null
    }

    /** Requests system consent to delete the selected images via [MediaStore.createDeleteRequest]. */
    data class BulkDeleteConsent(val request: IntentSenderRequest, val ids: Set<Long>)
    private val _pendingBulkDelete = MutableStateFlow<BulkDeleteConsent?>(null)
    val pendingBulkDelete: StateFlow<BulkDeleteConsent?> = _pendingBulkDelete.asStateFlow()

    fun requestBulkDelete() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val ids = _selectedIds.value
        if (ids.isEmpty()) return
        val uris = selectedUris()
        val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
        _pendingBulkDelete.value = BulkDeleteConsent(
            IntentSenderRequest.Builder(pi.intentSender).build(),
            ids,
        )
    }

    /** Called after the user approves the system delete dialog; removes DB rows. */
    fun onBulkDeleteApproved() {
        val ids = _pendingBulkDelete.value?.ids ?: return
        _pendingBulkDelete.value = null
        viewModelScope.launch {
            repository.deleteScreenshots(ids)
            _selectedIds.value = emptySet()
        }
    }

    fun clearPendingBulkDelete() { _pendingBulkDelete.value = null }

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

/** Gallery ordering options. */
enum class SortOrder(val label: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    RECENTLY_PROCESSED("Recently tagged"),
}

/** Determinate progress of the background processing queue. */
data class ProcessingState(val active: Boolean, val done: Int, val total: Int) {
    val fraction: Float get() = if (total > 0) done.toFloat() / total else 0f
}
