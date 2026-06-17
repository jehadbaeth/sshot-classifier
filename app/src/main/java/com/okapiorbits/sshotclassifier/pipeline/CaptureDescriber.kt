package com.okapiorbits.sshotclassifier.pipeline

import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inputs for describing a camera capture. Tags are user-facing labels, weightiest first.
 * [imageUri] is the captured image; the deterministic describer ignores it, the generative
 * one needs the pixels. It is nullable so non-image call sites (and tests) need not supply it.
 */
data class CaptureContext(
    val ocrText: String,
    val tags: List<String>,
    val qrPayload: String?,
    val qrIsUrl: Boolean,
    val imageUri: Uri? = null,
)

/**
 * Turns a classified camera capture into a short human-readable description for the
 * inventory.
 *
 * [StructuredCaptureDescriber] composes the text deterministically from OCR, tags, and any
 * QR payload (always available, offline). The experimental [vlm.GenerativeCaptureDescriber]
 * runs an on-device vision-language model instead; [CaptureDescriberRouter] picks between
 * them per the user's preference + device capability + model availability, always falling
 * back to structured. [describe] is `suspend` because the generative path is slow and
 * blocking. See docs/design.md (camera capture feature) and TODO.md.
 */
interface CaptureDescriber {
    suspend fun describe(ctx: CaptureContext): String
}

@Singleton
class StructuredCaptureDescriber @Inject constructor() : CaptureDescriber {

    override suspend fun describe(ctx: CaptureContext): String {
        val snippet = snippet(ctx.ocrText)
        val primary = ctx.tags.firstOrNull { it != "other" && it != "qr code" }

        val lead = when {
            ctx.qrPayload != null -> qrLead(ctx.qrPayload, ctx.qrIsUrl)
            primary != null -> primary.replaceFirstChar { it.uppercase() }
            else -> "Photo"
        }

        val sb = StringBuilder(lead)
        if (!lead.endsWith(".")) sb.append(".")
        if (snippet.isNotEmpty()) sb.append(" Text reads: \"").append(snippet).append("\".")
        return sb.toString()
    }

    private fun qrLead(payload: String, isUrl: Boolean): String =
        if (isUrl) "QR code linking to ${host(payload)}"
        else "QR code: \"${truncate(payload, MAX_QR)}\""

    /** Best-effort host extraction from a URL, without resolving anything. */
    private fun host(url: String): String {
        val afterScheme = url.substringAfter("://", url)
        val host = afterScheme.substringBefore('/').substringBefore('?').removePrefix("www.")
        return host.ifBlank { truncate(url, MAX_QR) }
    }

    /** Collapse whitespace and trim OCR text to a readable snippet. */
    private fun snippet(text: String): String {
        val collapsed = text.replace(WHITESPACE, " ").trim()
        return truncate(collapsed, MAX_SNIPPET)
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max).trimEnd() + "…"

    companion object {
        private const val MAX_SNIPPET = 140
        private const val MAX_QR = 80
        private val WHITESPACE = Regex("""\s+""")
    }
}
