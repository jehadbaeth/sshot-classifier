package com.okapiorbits.sshotclassifier.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Arabic OCR via Tesseract (ML Kit has no Arabic recognizer). Initialised multi-language as
 * `ara+eng` so a single pass reads Arabic, Latin, AND images that mix both scripts — single-
 * language `ara` mangles any Latin in the image (and vice-versa), which is what produced
 * gibberish on mixed-language images. Both trained-data files are bundled in assets and copied to
 * internal storage on first use, where Tesseract expects a `tessdata/` dir next to the data path.
 *
 * [TessBaseAPI] is not thread-safe and holds ~tens of MB once initialised, so it is created once
 * and reused under a [Mutex] (the worker OCRs sequentially anyway) — unlike the multi-GB VLM,
 * keeping it resident is fine. Returns null on any failure so the caller falls back / marks
 * the item without crashing the batch.
 */
@Singleton
class TesseractOcr @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    @Volatile private var api: TessBaseAPI? = null

    suspend fun recognize(uri: Uri): String? = mutex.withLock {
        withContext(Dispatchers.Default) {
            runCatching {
                val engine = ensureInit() ?: return@runCatching null
                val bitmap = loadBitmap(uri) ?: return@runCatching null
                engine.setImage(bitmap)
                val text = engine.getUTF8Text()
                engine.clear()
                bitmap.recycle()
                text?.takeIf { it.isNotBlank() }
            }.onFailure { Log.w(TAG, "Arabic OCR failed", it) }.getOrNull()
        }
    }

    /** Lazily copies the trained data and initialises Tesseract; null if init fails. */
    private fun ensureInit(): TessBaseAPI? {
        api?.let { return it }
        val dataPath = File(context.filesDir, "tesseract").apply { mkdirs() }
        val tessdataDir = File(dataPath, "tessdata").apply { mkdirs() }
        for (name in TRAINED_DATA) {
            val out = File(tessdataDir, name)
            if (out.exists() && out.length() > 100_000) continue
            runCatching {
                context.assets.open("tessdata/$name").use { input ->
                    out.outputStream().use { input.copyTo(it) }
                }
            }.onFailure {
                Log.w(TAG, "Could not stage $name", it)
                return null
            }
        }
        val engine = TessBaseAPI()
        return if (engine.init(dataPath.absolutePath, LANG)) {
            api = engine
            engine
        } else {
            Log.w(TAG, "Tesseract init failed")
            engine.recycle()
            null
        }
    }

    /** Decodes [uri] at a sane size — large enough for OCR, bounded so a huge image won't OOM. */
    private fun loadBitmap(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > MAX_DIM) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }.getOrNull()

    companion object {
        private const val TAG = "TesseractOcr"
        /** Multi-language: read Arabic and Latin (incl. mixed) in one pass. */
        private const val LANG = "ara+eng"
        private val TRAINED_DATA = listOf("ara.traineddata", "eng.traineddata")
        /** Cap longest side; Tesseract wants resolution but not a 4000px screenshot in RAM. */
        private const val MAX_DIM = 2200
    }
}
