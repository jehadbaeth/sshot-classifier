package com.okapiorbits.sshotclassifier.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.data.db.entity.TagEntity
import com.okapiorbits.sshotclassifier.data.prefs.CapturePreferences
import com.okapiorbits.sshotclassifier.data.prefs.CapturePreferencesStore
import com.okapiorbits.sshotclassifier.data.db.entity.SourceType
import com.okapiorbits.sshotclassifier.data.repository.ScreenshotRepository
import com.okapiorbits.sshotclassifier.pipeline.clip.EmbeddingCodec
import com.okapiorbits.sshotclassifier.pipeline.clip.ZeroShotClassifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the screenshot detail / tag editor. Stateless with respect to which
 * screenshot is shown: the screen passes the id into each call and observes
 * [tags] keyed by that id, so a single VM instance serves any opened screenshot
 * without needing nav-args / SavedStateHandle.
 */
@HiltViewModel
class ScreenshotDetailViewModel @Inject constructor(
    private val repository: ScreenshotRepository,
    private val capturePreferencesStore: CapturePreferencesStore,
    private val zeroShot: ZeroShotClassifier,
) : ViewModel() {

    /** Transient feedback for a QR resolution attempt; null when idle. */
    private val _resolveMessage = MutableStateFlow<String?>(null)
    val resolveMessage: StateFlow<String?> = _resolveMessage.asStateFlow()

    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    fun tags(screenshotId: Long): Flow<List<TagEntity>> = repository.observeTags(screenshotId)

    /** The image row itself, for the camera-capture description and QR payload/preview. */
    fun screenshot(screenshotId: Long): Flow<ScreenshotEntity?> = repository.observeScreenshot(screenshotId)

    /** Extracted OCR text, shown read-only on the detail screen. */
    fun ocrText(screenshotId: Long): Flow<String?> = repository.observeOcrText(screenshotId)

    /** Capture preferences (whether resolution is enabled, whether to load preview images). */
    val capturePreferences: Flow<CapturePreferences> = capturePreferencesStore.preferences

    /**
     * Tags the model thinks fit this image but that aren't applied yet, for one-tap adding. Runs
     * the same CLIP zero-shot scoring as the pipeline over the stored embedding, keeps labels with
     * meaningful probability mass, and drops ones already on the image. Empty when there's no
     * embedding (model not installed / not reprocessed). Reacts to the tag list so a suggestion
     * disappears once added.
     */
    fun suggestions(screenshotId: Long): Flow<List<String>> =
        repository.observeTags(screenshotId).combine(rankedCandidates(screenshotId)) { tags, candidates ->
            val existing = tags.map { it.label.lowercase() }.toSet()
            candidates.filter { it.lowercase() !in existing }.take(MAX_SUGGESTIONS)
        }

    private fun rankedCandidates(screenshotId: Long): Flow<List<String>> = flow {
        val bytes = repository.embeddingFor(screenshotId)
        if (bytes == null) {
            emit(emptyList()); return@flow
        }
        val isCamera = repository.observeScreenshot(screenshotId).first()?.source_type == SourceType.CAMERA.name
        val scores = zeroShot.classify(EmbeddingCodec.toFloats(bytes), includeRealWorld = isCamera)
        emit(
            scores.entries
                .filter { it.value >= SUGGESTION_FLOOR && it.key != "other" }
                .sortedByDescending { it.value }
                .map { it.key }
        )
    }

    fun addTag(screenshotId: Long, label: String) {
        viewModelScope.launch { repository.addUserTag(screenshotId, label) }
    }

    fun removeTag(tag: TagEntity) {
        viewModelScope.launch { repository.removeTag(tag.id, tag.screenshot_id) }
    }

    /**
     * Resolves the capture's QR link into a stored preview (the resolved fields then flow
     * back through [screenshot]). Sets a short message on a non-success so the UI can say why.
     */
    fun resolveLink(screenshotId: Long) {
        if (_resolving.value) return
        viewModelScope.launch {
            _resolving.value = true
            _resolveMessage.value = null
            val result = repository.resolveQrLink(screenshotId)
            _resolveMessage.value = when (result) {
                is ScreenshotRepository.ResolveResult.Resolved -> null
                ScreenshotRepository.ResolveResult.Disabled -> "Link resolution is off. Enable it in Settings."
                ScreenshotRepository.ResolveResult.NotUrl -> "This code is not a web link."
                ScreenshotRepository.ResolveResult.NoNetwork -> "No usable network (check the Wi-Fi-only setting)."
                ScreenshotRepository.ResolveResult.Failed -> "Could not read a preview from that link."
                ScreenshotRepository.ResolveResult.Gone -> null
            }
            _resolving.value = false
        }
    }

    fun clearResolveMessage() {
        _resolveMessage.value = null
    }

    private companion object {
        /** Minimum softmax probability mass for a label to be worth suggesting. */
        const val SUGGESTION_FLOOR = 0.04f
        const val MAX_SUGGESTIONS = 6
    }
}
