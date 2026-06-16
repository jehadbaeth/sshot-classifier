package com.okapiorbits.sshotclassifier.ui.camera

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okapiorbits.sshotclassifier.data.prefs.CapturePreferencesStore
import com.okapiorbits.sshotclassifier.data.repository.ScreenshotRepository
import com.okapiorbits.sshotclassifier.monitoring.ScreenshotProcessingWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CameraCaptureViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ScreenshotRepository,
    capturePreferencesStore: CapturePreferencesStore,
) : ViewModel() {

    /** Number of captures indexed this session, for a small on-screen confirmation. */
    private val _capturedCount = MutableStateFlow(0)
    val capturedCount: StateFlow<Int> = _capturedCount.asStateFlow()

    /** MediaStore relative path captures are written to, from the user's album preference. */
    val captureRelativePath: StateFlow<String> =
        capturePreferencesStore.preferences
            .map { "Pictures/${it.captureAlbumRoot}/Captures" }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Pictures/ScreenshotClassifier/Captures")

    /**
     * Indexes a freshly captured photo (already written to MediaStore) and kicks off
     * processing. Camera captures get OCR, QR decoding, CLIP tags, and a description.
     */
    fun onCaptured(uri: Uri) {
        viewModelScope.launch {
            val id = repository.indexCapture(uri)
            if (id != null) {
                _capturedCount.value += 1
                ScreenshotProcessingWorker.enqueue(context)
            }
        }
    }
}
