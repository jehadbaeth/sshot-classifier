package com.okapiorbits.sshotclassifier.ui.settings

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okapiorbits.sshotclassifier.data.db.entity.CustomCategoryEntity
import com.okapiorbits.sshotclassifier.data.media.ScreenshotOrganizer
import com.okapiorbits.sshotclassifier.data.prefs.ReorgMode
import com.okapiorbits.sshotclassifier.data.prefs.ReorgPreferences
import com.okapiorbits.sshotclassifier.data.prefs.ReorgPreferencesStore
import com.okapiorbits.sshotclassifier.data.repository.ScreenshotRepository
import com.okapiorbits.sshotclassifier.data.repository.ScreenshotRepository.AddCategoryResult
import com.okapiorbits.sshotclassifier.monitoring.ScreenshotProcessingWorker
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipModelDownloader
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Install state and on-disk size of the two CLIP models. */
data class ModelInfo(
    val imageInstalled: Boolean,
    val imageBytes: Long,
    val textInstalled: Boolean,
    val textBytes: Long,
)

/** Progress of an in-flight model download, surfaced on the settings screen. */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val progress: Float) : DownloadState
    data class Failed(val message: String) : DownloadState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ScreenshotRepository,
    private val modelManager: ClipModelManager,
    private val downloader: ClipModelDownloader,
    private val organizer: ScreenshotOrganizer,
    private val reorgPrefsStore: ReorgPreferencesStore,
) : ViewModel() {

    val reorganizeSupported: Boolean = organizer.isSupported
    val moveSupported: Boolean = organizer.moveSupported

    val screenshotCount: StateFlow<Int> =
        repository.observeCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val pendingCount: StateFlow<Int> =
        repository.observePendingCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val reprocessableCount: StateFlow<Int> =
        repository.observeReprocessableCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _models = MutableStateFlow(readModelInfo())
    val models: StateFlow<ModelInfo> = _models.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    val versionName: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    val categories: StateFlow<List<CustomCategoryEntity>> =
        repository.observeCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _categoryStatus = MutableStateFlow<String?>(null)
    val categoryStatus: StateFlow<String?> = _categoryStatus.asStateFlow()

    fun addCategory(label: String) {
        viewModelScope.launch {
            _categoryStatus.value = when (val r = repository.addCustomCategory(label)) {
                is AddCategoryResult.Added ->
                    "Added \"${label.trim().lowercase()}\" · tagged ${r.matched} existing"
                AddCategoryResult.Blank -> null
                AddCategoryResult.Duplicate -> "That category already exists"
                AddCategoryResult.ModelMissing -> "Install the text model first"
                AddCategoryResult.EncodeFailed -> "Could not build an embedding for that label"
            }
        }
    }

    fun removeCategory(category: CustomCategoryEntity) {
        viewModelScope.launch { repository.removeCustomCategory(category.id, category.label) }
    }

    fun clearCategoryStatus() {
        _categoryStatus.value = null
    }

    /** Re-read model install state from disk (call when returning to the screen). */
    fun refresh() {
        _models.value = readModelInfo()
    }

    /** Downloads any missing models; installed ones are skipped by the downloader. */
    fun downloadModels() {
        if (_download.value is DownloadState.Running) return
        viewModelScope.launch {
            _download.value = DownloadState.Running(0f)
            val result = downloader.download { p -> _download.value = DownloadState.Running(p) }
            _download.value = when (result) {
                is ClipModelDownloader.State.Done -> DownloadState.Idle
                is ClipModelDownloader.State.Failed -> DownloadState.Failed(result.message)
                else -> DownloadState.Idle
            }
            _models.value = readModelInfo()
        }
    }

    /** Re-runs already-tagged screenshots that lack a visual embedding. */
    fun reprocessMissing() {
        viewModelScope.launch {
            val count = repository.markForReprocessing()
            if (count > 0) ScreenshotProcessingWorker.enqueue(context)
        }
    }

    /** Triggers an immediate scan + processing pass. */
    fun scanNow() {
        ScreenshotProcessingWorker.enqueue(context)
    }

    // ---- Reorganization preferences ----

    val reorgPrefs: StateFlow<ReorgPreferences> =
        reorgPrefsStore.preferences
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReorgPreferences())

    /** Recorded moves available to undo. */
    val undoableMoves: StateFlow<Int> =
        repository.observeReorgMoveCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setReorgMode(mode: ReorgMode) = viewModelScope.launch { reorgPrefsStore.setMode(mode) }
    fun setAlbumRoot(root: String) = viewModelScope.launch { reorgPrefsStore.setAlbumRoot(root) }
    fun setNeedsReviewToUncategorized(v: Boolean) =
        viewModelScope.launch { reorgPrefsStore.setNeedsReviewToUncategorized(v) }
    fun setAutoRun(v: Boolean) = viewModelScope.launch { reorgPrefsStore.setAutoRun(v) }

    private val _reorganizeStatus = MutableStateFlow<String?>(null)
    val reorganizeStatus: StateFlow<String?> = _reorganizeStatus.asStateFlow()

    /** A delete-consent request the UI must launch (MOVE mode), with its pending copies. */
    data class DeleteConsent(
        val request: IntentSenderRequest,
        val moves: List<ScreenshotOrganizer.PendingMove>,
        val copied: Int,
    )

    private val _pendingDelete = MutableStateFlow<DeleteConsent?>(null)
    val pendingDelete: StateFlow<DeleteConsent?> = _pendingDelete.asStateFlow()

    /**
     * Runs reorganization with the current preferences. COPY finishes immediately. MOVE
     * copies, then surfaces a [DeleteConsent] the UI launches; the originals are deleted
     * only after the user approves (see [onDeleteApproved]).
     */
    fun reorganize() {
        if (_reorganizeStatus.value == RUNNING) return
        viewModelScope.launch {
            _reorganizeStatus.value = RUNNING
            val prefs = reorgPrefsStore.current()
            val r = organizer.organizeIntoAlbums(prefs)
            val base = "Copied ${r.copied}" +
                (if (r.skipped > 0) ", ${r.skipped} already there" else "") +
                (if (r.failed > 0) ", ${r.failed} failed" else "")
            if (r.pendingMoves.isEmpty()) {
                _reorganizeStatus.value = "$base into albums"
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val uris = r.pendingMoves.map { it.sourceUri }
                val pi = MediaStore.createDeleteRequest(context.contentResolver, uris)
                _pendingDelete.value = DeleteConsent(
                    IntentSenderRequest.Builder(pi.intentSender).build(),
                    r.pendingMoves,
                    r.copied,
                )
                _reorganizeStatus.value = "$base; confirm deleting ${uris.size} originals…"
            }
        }
    }

    /** Called when the user approves the system delete dialog: finalize the move. */
    fun onDeleteApproved() {
        val consent = _pendingDelete.value ?: return
        _pendingDelete.value = null
        viewModelScope.launch {
            organizer.commitMoves(consent.moves)
            _reorganizeStatus.value = "Moved ${consent.moves.size} into albums"
        }
    }

    /** Called when the user declines the delete dialog: copies stay, originals kept. */
    fun onDeleteCancelled() {
        val consent = _pendingDelete.value ?: return
        _pendingDelete.value = null
        _reorganizeStatus.value = "Copied ${consent.copied}; deletion cancelled, originals kept"
    }

    /** Restores moved originals from their album copies and clears the undo log. */
    fun undoMoves() {
        if (_reorganizeStatus.value == RUNNING) return
        viewModelScope.launch {
            _reorganizeStatus.value = RUNNING
            val u = organizer.undoMoves()
            _reorganizeStatus.value = "Restored ${u.restored} originals" +
                (if (u.failed > 0) ", ${u.failed} failed" else "")
        }
    }

    companion object {
        const val RUNNING = "Organizing…"
    }

    private fun readModelInfo() = ModelInfo(
        imageInstalled = modelManager.isModelInstalled(),
        imageBytes = modelManager.modelFile.let { if (it.exists()) it.length() else 0L },
        textInstalled = modelManager.isTextModelInstalled(),
        textBytes = modelManager.textModelFile.let { if (it.exists()) it.length() else 0L },
    )
}
