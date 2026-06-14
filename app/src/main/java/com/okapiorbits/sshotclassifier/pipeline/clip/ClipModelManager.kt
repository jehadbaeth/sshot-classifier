package com.okapiorbits.sshotclassifier.pipeline.clip

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the on-device CLIP image-encoder model file. The model (~90 MB int8) is
 * too big to bundle in the APK or commit to git, so it lives in app internal
 * storage and is delivered separately:
 *   - dev/test: `adb push <model> /data/local/tmp` then copied, or pushed straight
 *     into files/models via run-as.
 *   - production: downloaded on first launch (see ClipModelDownloader / design 9).
 */
@Singleton
class ClipModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val modelDir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    /** The image encoder (tagging + image embeddings). */
    val modelFile: File get() = File(modelDir, MODEL_NAME)

    /** The text encoder (free-text semantic search, Phase 3). */
    val textModelFile: File get() = File(modelDir, TEXT_MODEL_NAME)

    fun isModelInstalled(): Boolean = isInstalled(modelFile)

    fun isTextModelInstalled(): Boolean = isInstalled(textModelFile)

    /** Both encoders present: tagging (image) and semantic search (text). */
    fun areAllModelsInstalled(): Boolean = isModelInstalled() && isTextModelInstalled()

    private fun isInstalled(f: File): Boolean = f.exists() && f.length() > 1_000_000

    companion object {
        const val MODEL_NAME = "clip_image_b32_int8w.tflite"
        const val TEXT_MODEL_NAME = "clip_text_b32_int8w.tflite"
        const val EMBED_DIM = 512
        const val IMAGE_SIZE = 224
        const val CONTEXT_LENGTH = 77
    }
}
