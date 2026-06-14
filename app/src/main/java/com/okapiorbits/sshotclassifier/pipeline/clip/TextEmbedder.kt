package com.okapiorbits.sshotclassifier.pipeline.clip

/**
 * Embeds free text into the CLIP 512-d space. Pulled out as an interface so the
 * search path can be exercised in tests with a fake embedder, without loading the
 * 65 MB TFLite text model. Production binding is [ClipTextEncoder].
 */
interface TextEmbedder {
    /** True when an embedding model is available; false means callers should fall back to OCR. */
    fun isReady(): Boolean

    /** L2-normalized embedding, or null if no model is installed or the run fails. */
    fun encode(text: String): FloatArray?
}
