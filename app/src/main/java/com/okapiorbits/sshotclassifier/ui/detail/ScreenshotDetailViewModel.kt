package com.okapiorbits.sshotclassifier.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.data.db.entity.TagEntity
import com.okapiorbits.sshotclassifier.data.prefs.CapturePreferences
import com.okapiorbits.sshotclassifier.data.prefs.CapturePreferencesStore
import com.okapiorbits.sshotclassifier.data.repository.ScreenshotRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : ViewModel() {

    /** Transient feedback for a QR resolution attempt; null when idle. */
    private val _resolveMessage = MutableStateFlow<String?>(null)
    val resolveMessage: StateFlow<String?> = _resolveMessage.asStateFlow()

    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    fun tags(screenshotId: Long): Flow<List<TagEntity>> = repository.observeTags(screenshotId)

    /** The image row itself, for the camera-capture description and QR payload/preview. */
    fun screenshot(screenshotId: Long): Flow<ScreenshotEntity?> = repository.observeScreenshot(screenshotId)

    /** Capture preferences (whether resolution is enabled, whether to load preview images). */
    val capturePreferences: Flow<CapturePreferences> = capturePreferencesStore.preferences

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
}
