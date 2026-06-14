package com.okapiorbits.sshotclassifier.pipeline

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
 * Extracts text from a screenshot with ML Kit Text Recognition v2 (on-device,
 * Latin script). Returns null on failure so the caller can mark the item FAILED
 * without crashing the batch.
 */
@Singleton
class OcrExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extract(uri: Uri): OcrResult? {
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
}
