package com.okapiorbits.sshotclassifier.pipeline.clip

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Packs/unpacks a float32 embedding to a little-endian byte blob for Room storage. */
object EmbeddingCodec {
    fun toBytes(v: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in v) buf.putFloat(f)
        return buf.array()
    }

    fun toFloats(bytes: ByteArray): FloatArray {
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }
}
