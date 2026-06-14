package com.okapiorbits.sshotclassifier.pipeline.clip

/**
 * Embeds a custom category label into the CLIP space. Pulled out as an interface
 * so the repository's add-category flow can be tested with a fake embedder,
 * without the 65 MB text model. Production binding is [CategoryEmbedder].
 */
interface LabelEmbedder {
    fun isReady(): Boolean

    /** Prompt-ensembled, L2-normalized embedding, or null if no model is installed. */
    fun embed(label: String): FloatArray?
}
