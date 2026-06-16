package com.okapiorbits.sshotclassifier.pipeline

import javax.inject.Inject
import javax.inject.Singleton

/** Inputs for describing a camera capture. Tags are user-facing labels, weightiest first. */
data class CaptureContext(
    val ocrText: String,
    val tags: List<String>,
    val qrPayload: String?,
    val qrIsUrl: Boolean,
)

/**
 * Turns a classified camera capture into a short human-readable description for the
 * inventory.
 *
 * Phase A ships [StructuredCaptureDescriber], which composes the text deterministically
 * from OCR, tags, and any QR payload. It is an interface so a generative on-device
 * vision-language model can replace it later (Phase B) without touching the pipeline or
 * the stored `description` column. See docs/design.md (camera capture feature) and TODO.md.
 */
interface CaptureDescriber {
    fun describe(ctx: CaptureContext): String
}

@Singleton
class StructuredCaptureDescriber @Inject constructor() : CaptureDescriber {

    override fun describe(ctx: CaptureContext): String {
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
