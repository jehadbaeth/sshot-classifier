package com.okapiorbits.sshotclassifier.ui.scan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.okapiorbits.sshotclassifier.data.db.entity.SourceType
import com.okapiorbits.sshotclassifier.data.prefs.CapturePreferencesStore
import com.okapiorbits.sshotclassifier.data.repository.ScreenshotRepository
import com.okapiorbits.sshotclassifier.monitoring.ScreenshotProcessingWorker
import com.okapiorbits.sshotclassifier.pipeline.geometry.DocumentEnhance
import com.okapiorbits.sshotclassifier.pipeline.geometry.PerspectiveCrop
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** How the source image reached the crop screen; controls cleanup on cancel. */
enum class ScanOrigin { FRESH_CAPTURE, EXISTING_IMAGE }

data class ScanUiState(
    val loading: Boolean = true,
    val bitmap: Bitmap? = null,
    /** Corner handles in the loaded (possibly downsampled) bitmap's own pixel space, TL/TR/BR/BL. */
    val corners: List<PointF> = emptyList(),
    val saving: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
    /** Monotone-printer mode: binarize to pure black/white instead of the default color cleanup. */
    val blackAndWhite: Boolean = false,
    /** Downsampled preview of the warped + enhanced result, recomputed after corners settle. */
    val previewBitmap: Bitmap? = null,
)

@HiltViewModel
class DocumentScanViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ScreenshotRepository,
    private val capturePreferencesStore: CapturePreferencesStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ScanUiState())
    val state: StateFlow<ScanUiState> = _state.asStateFlow()

    private var sourceUri: Uri? = null
    private var origin: ScanOrigin = ScanOrigin.EXISTING_IMAGE

    /** Full-resolution source dimensions, for scaling display-space corners back up on save. */
    private var sourceWidth = 0
    private var sourceHeight = 0

    private var previewJob: Job? = null

    fun load(uri: Uri, origin: ScanOrigin) {
        this.sourceUri = uri
        this.origin = origin
        viewModelScope.launch {
            _state.value = ScanUiState(loading = true)
            val bitmap = withContext(Dispatchers.IO) { decodeDownsampled(uri) }
            if (bitmap == null) {
                _state.value = ScanUiState(loading = false, error = "Couldn't open that image")
                return@launch
            }
            val inset = 0.08f
            val corners = listOf(
                PointF(bitmap.width * inset, bitmap.height * inset),
                PointF(bitmap.width * (1 - inset), bitmap.height * inset),
                PointF(bitmap.width * (1 - inset), bitmap.height * (1 - inset)),
                PointF(bitmap.width * inset, bitmap.height * (1 - inset)),
            )
            _state.value = ScanUiState(loading = false, bitmap = bitmap, corners = corners)
            schedulePreview()
        }
    }

    fun moveCorner(index: Int, point: PointF) {
        val current = _state.value
        val bitmap = current.bitmap ?: return
        val clamped = PointF(
            point.x.coerceIn(0f, bitmap.width.toFloat()),
            point.y.coerceIn(0f, bitmap.height.toFloat()),
        )
        val updated = current.corners.toMutableList().also { it[index] = clamped }
        _state.value = current.copy(corners = updated)
        schedulePreview()
    }

    fun resetCorners() {
        val bitmap = _state.value.bitmap ?: return
        val inset = 0.08f
        _state.value = _state.value.copy(
            corners = listOf(
                PointF(bitmap.width * inset, bitmap.height * inset),
                PointF(bitmap.width * (1 - inset), bitmap.height * inset),
                PointF(bitmap.width * (1 - inset), bitmap.height * (1 - inset)),
                PointF(bitmap.width * inset, bitmap.height * (1 - inset)),
            ),
        )
        schedulePreview()
    }

    fun toggleBlackAndWhite() {
        _state.value = _state.value.copy(blackAndWhite = !_state.value.blackAndWhite)
        schedulePreview()
    }

    /** Recomputes the warped + enhanced preview shortly after corners/mode settle, so a
     * fast drag doesn't trigger a warp+binarize pass on every frame. */
    private fun schedulePreview() {
        val current = _state.value
        val bitmap = current.bitmap ?: return
        if (current.corners.size != 4) return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(200)
            val corners = current.corners
            val bw = current.blackAndWhite
            val preview = withContext(Dispatchers.Default) {
                val warped = PerspectiveCrop.warpToRect(bitmap, corners, maxDimension = 900)
                val leveled = DocumentEnhance.autoLevels(warped)
                if (bw) DocumentEnhance.binarize(leveled) else leveled
            }
            if (_state.value.corners === corners && _state.value.blackAndWhite == bw) {
                _state.value = _state.value.copy(previewBitmap = preview)
            }
        }
    }

    fun confirm() {
        val current = _state.value
        val uri = sourceUri
        val displayBitmap = current.bitmap
        if (uri == null || displayBitmap == null || current.corners.size != 4 || current.saving) return

        _state.value = current.copy(saving = true, error = null)
        viewModelScope.launch {
            val savedUri = withContext(Dispatchers.Default) {
                runCatching {
                    // Re-decode at full resolution so the scan isn't limited to display quality.
                    val fullRes = decodeFullResolution(uri) ?: displayBitmap
                    val scaleX = fullRes.width.toFloat() / displayBitmap.width
                    val scaleY = fullRes.height.toFloat() / displayBitmap.height
                    val fullResCorners = current.corners.map { PointF(it.x * scaleX, it.y * scaleY) }
                    val cropped = PerspectiveCrop.warpToRect(fullRes, fullResCorners)
                    val leveled = DocumentEnhance.autoLevels(cropped)
                    val finished = if (current.blackAndWhite) DocumentEnhance.binarize(leveled) else leveled
                    val root = capturePreferencesStore.current().captureAlbumRoot
                    writeScanToMediaStore(finished, "Pictures/$root/Scans")
                }
            }.getOrNull()

            if (savedUri == null) {
                _state.value = _state.value.copy(saving = false, error = "Couldn't save the scan")
                return@launch
            }

            val indexed = repository.indexCapture(savedUri, SourceType.SCAN)
            if (indexed != null) ScreenshotProcessingWorker.enqueue(context)

            if (origin == ScanOrigin.FRESH_CAPTURE) deleteTempCapture(uri)
            _state.value = _state.value.copy(saving = false, done = true)
        }
    }

    /** Discards a temp fresh-capture file if the user backs out without confirming. */
    fun cancel() {
        if (origin == ScanOrigin.FRESH_CAPTURE) sourceUri?.let { deleteTempCapture(it) }
    }

    /** Fresh scan captures are written straight to a cache [java.io.File] (see
     * CameraCaptureScreen.takePictureToCache), not MediaStore, so cleanup is a plain file
     * delete rather than a ContentResolver delete (which doesn't support the file scheme). */
    private fun deleteTempCapture(uri: Uri) {
        if (uri.scheme == "file") {
            uri.path?.let { runCatching { java.io.File(it).delete() } }
        } else {
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }

    private fun decodeDownsampled(uri: Uri, maxDimension: Int = 1600): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
            true
        }
        if (opened != true || bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        sourceWidth = bounds.outWidth
        sourceHeight = bounds.outHeight
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxDimension || bounds.outHeight / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }

    private fun decodeFullResolution(uri: Uri): Bitmap? =
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }

    private fun writeScanToMediaStore(bitmap: Bitmap, relativePath: String): Uri? {
        val resolver = context.contentResolver
        val name = "scan_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val dest = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(dest)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            } ?: run { resolver.delete(dest, null, null); return null }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(dest, values, null, null)
            }
            dest
        } catch (e: Exception) {
            runCatching { resolver.delete(dest, null, null) }
            null
        }
    }
}
