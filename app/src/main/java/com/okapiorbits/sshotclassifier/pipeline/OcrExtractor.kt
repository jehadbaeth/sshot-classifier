package com.okapiorbits.sshotclassifier.pipeline

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.okapiorbits.sshotclassifier.data.prefs.OcrLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Result of OCR on one screenshot. */
data class OcrResult(
    val text: String,
    /** Best-effort dominant language tag from recognized blocks, or null. */
    val language: String?,
)

/**
 * Extracts text from an image. Latin script uses ML Kit Text Recognition v2; Arabic uses
 * [TesseractOcr] (ML Kit has no Arabic recognizer). The [OcrLanguage] is chosen by the user and
 * passed in by the worker. Returns null on failure so the caller can mark the item FAILED
 * without crashing the batch.
 */
@Singleton
class OcrExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tesseract: TesseractOcr,
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extract(uri: Uri, language: OcrLanguage = OcrLanguage.LATIN): OcrResult? {
        return when (language) {
            OcrLanguage.LATIN -> latin(uri)
            OcrLanguage.ARABIC -> arabic(uri)
            OcrLanguage.BOTH -> {
                // Run both; combine whatever each returns. Null only if neither yields anything.
                val l = latin(uri)
                val a = arabic(uri)
                val combined = listOfNotNull(l?.text, a?.text).filter { it.isNotBlank() }.joinToString("\n")
                if (combined.isBlank()) null else OcrResult(combined, l?.language ?: "ar")
            }
            OcrLanguage.AUTO -> {
                // Cheap auto-detect: the fast Latin pass usually suffices; only when it finds
                // little real text (i.e. the image is likely non-Latin) do we pay the Arabic pass.
                val l = latin(uri)
                if ((l?.text?.count { it.isLetterOrDigit() } ?: 0) >= AUTO_LATIN_MIN_CHARS) {
                    l
                } else {
                    // Latin came up short; try Arabic and keep whichever yielded more text.
                    val a = arabic(uri)
                    val lLen = l?.text?.trim()?.length ?: 0
                    val aLen = a?.text?.trim()?.length ?: 0
                    if (aLen > lLen) a else l
                }
            }
        }
    }

    private suspend fun latin(uri: Uri): OcrResult? {
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: Exception) {
            return null
        }
        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val language = visionText.textBlocks
                        .mapNotNull { it.recognizedLanguage }
                        .firstOrNull { it.isNotBlank() && it != "und" }
                    cont.resume(OcrResult(visionText.text, language))
                }
                .addOnFailureListener { cont.resume(null) }
        }
    }

    private suspend fun arabic(uri: Uri): OcrResult? =
        tesseract.recognize(uri)?.let { OcrResult(it, "ar") }

    private companion object {
        /** Below this many Latin letters/digits, Auto treats the image as likely non-Latin. */
        const val AUTO_LATIN_MIN_CHARS = 12
    }
}
