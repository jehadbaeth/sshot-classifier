package com.okapiorbits.sshotclassifier.pipeline

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** A decoded QR / barcode found in an image. */
data class BarcodeResult(
    /** Raw decoded value (a URL, plain text, wifi config, etc.). */
    val rawValue: String,
    /** Whether the payload is an http(s) URL, i.e. resolvable into a link preview later. */
    val isUrl: Boolean,
)

/**
 * Decodes QR codes and barcodes from an image with ML Kit Barcode Scanning
 * (on-device, no network). Returns the first decoded code, or null when none is
 * present or decoding fails. Fully offline: this only reads the code, it never
 * fetches what a URL points to (that is the opt-in Phase B link-resolution step).
 */
@Singleton
class BarcodeExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scanner = BarcodeScanning.getClient()

    suspend fun extract(uri: Uri): BarcodeResult? {
        val image = try {
            InputImage.fromFilePath(context, uri)
        } catch (e: Exception) {
            return null
        }

        return suspendCancellableCoroutine { cont ->
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val first = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                    if (first == null) {
                        cont.resume(null)
                    } else {
                        val raw = first.rawValue!!
                        val isUrl = first.valueType == Barcode.TYPE_URL ||
                            raw.startsWith("http://", ignoreCase = true) ||
                            raw.startsWith("https://", ignoreCase = true)
                        cont.resume(BarcodeResult(raw, isUrl))
                    }
                }
                .addOnFailureListener { cont.resume(null) }
        }
    }
}
