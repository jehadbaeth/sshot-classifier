package com.okapiorbits.sshotclassifier.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okapiorbits.sshotclassifier.data.repository.ScreenshotRepository
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
) : ViewModel() {

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

    private fun readModelInfo() = ModelInfo(
        imageInstalled = modelManager.isModelInstalled(),
        imageBytes = modelManager.modelFile.let { if (it.exists()) it.length() else 0L },
        textInstalled = modelManager.isTextModelInstalled(),
        textBytes = modelManager.textModelFile.let { if (it.exists()) it.length() else 0L },
    )
}
