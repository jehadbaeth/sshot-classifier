package com.okapiorbits.sshotclassifier.pipeline.clip

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injectable wrapper around [BpeTokenizer] that lazily builds the vocab from the
 * bundled BPE merges asset. The build (49408-token vocab) runs once on first use
 * off the UI thread (first semantic query).
 */
@Singleton
class ClipTokenizer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val delegate: BpeTokenizer by lazy {
        context.assets.open("clip/bpe_merges.txt").use { BpeTokenizer.fromStream(it) }
    }

    fun tokenize(text: String, contextLength: Int = 77): IntArray =
        delegate.tokenize(text, contextLength)
}
