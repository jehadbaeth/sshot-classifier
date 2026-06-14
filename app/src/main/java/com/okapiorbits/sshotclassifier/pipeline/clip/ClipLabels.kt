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
}
