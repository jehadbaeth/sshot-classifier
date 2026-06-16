package com.okapiorbits.sshotclassifier.pipeline.clip

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/** One precomputed label: concrete internal concept + its user-facing tag + embedding. */
data class ClipLabel(val internal: String, val tag: String, val embedding: FloatArray)

/**
 * Loads the bundled, precomputed CLIP text embeddings (assets/clip/) so tagging
 * needs only the on-device image encoder. labels.json is ordered to match the
 * rows in label_embeddings.f32 (N x 512 little-endian float32).
 */
@Singleton
class ClipLabels @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val labels: List<ClipLabel> by lazy { load() }

    /**
     * Labels for real-world camera captures (storefront, menu, qr code, ...).
     * Kept OUT of [screenshotLabels] so the screenshot scoring candidate set — and
     * therefore the validated screenshot eval — is exactly what it was before these
     * labels were added. A softmax/argmax over a larger candidate set can change the
     * winner even when no existing embedding moved, so screenshots must not see these.
     */
    val realWorldLabels: List<ClipLabel> by lazy { labels.filter { it.tag in REALWORLD_TAGS } }

    /** Labels used to score screenshots: the original set, excluding real-world capture labels. */
    val screenshotLabels: List<ClipLabel> by lazy { labels.filter { it.tag !in REALWORLD_TAGS } }

    private fun load(): List<ClipLabel> {
        val json = context.assets.open("clip/labels.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(json)
        val dim = ClipModelManager.EMBED_DIM
        val bytes = context.assets.open("clip/label_embeddings.f32").use { it.readBytes() }
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val emb = FloatArray(dim)
            fb.position(i * dim)
            fb.get(emb)
            ClipLabel(o.getString("internal"), o.getString("tag"), emb)
        }
    }

    companion object {
        /**
         * User-facing tags produced only from camera captures. Must match the tags of the
         * real-world labels appended by spikes/clip/add_realworld_labels.py.
         */
        val REALWORLD_TAGS = setOf(
            "storefront", "advertisement", "street sign", "business card",
            "product", "menu", "poster", "qr code",
        )
    }
}
