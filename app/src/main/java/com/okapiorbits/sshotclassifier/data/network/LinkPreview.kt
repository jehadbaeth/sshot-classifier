package com.okapiorbits.sshotclassifier.data.network

/**
 * A resolved preview of a QR link's destination. All fields are optional: a page may
 * have only a title, only OpenGraph tags, or nothing useful. [imageUrl] is just a URL
 * string here; whether it is actually loaded/displayed is gated separately by the
 * downloadPreviewImages preference at the render site.
 */
data class LinkPreview(
    val title: String?,
    val description: String?,
    val imageUrl: String?,
) {
    fun isEmpty(): Boolean = title.isNullOrBlank() && description.isNullOrBlank() && imageUrl.isNullOrBlank()
}

/**
 * Extracts a [LinkPreview] from an HTML document. Pure (no network), so it is unit-tested
 * directly. Prefers OpenGraph (`og:title`, `og:description`, `og:image`) and falls back to
 * the `<title>` element and `<meta name="description">`. Order-insensitive to the
 * `property`/`content` attribute order and tolerant of single or double quotes.
 */
object OgParser {

    fun parse(html: String): LinkPreview {
        val title = metaContent(html, "og:title") ?: titleTag(html)
        val description = metaContent(html, "og:description") ?: metaNamed(html, "description")
        val image = metaContent(html, "og:image")
        return LinkPreview(
            title = title?.let(::clean),
            description = description?.let(::clean),
            imageUrl = image?.let(::clean),
        )
    }

    /** A <meta> tag matching property/name == [key] (OpenGraph uses `property`). */
    private fun metaContent(html: String, key: String): String? =
        metaBy("""property\s*=\s*["']${Regex.escape(key)}["']""", html)
            ?: metaBy("""name\s*=\s*["']${Regex.escape(key)}["']""", html)

    private fun metaNamed(html: String, name: String): String? =
        metaBy("""name\s*=\s*["']${Regex.escape(name)}["']""", html)

    /**
     * Finds a <meta ...> tag whose attributes match [keyAttr], then pulls its `content`.
     * Handles `content` appearing before or after the key attribute.
     */
    private fun metaBy(keyAttr: String, html: String): String? {
        val tag = Regex("""<meta\b[^>]*$keyAttr[^>]*>""", RegexOption.IGNORE_CASE).find(html)?.value
            ?: Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.value }
                .firstOrNull { Regex(keyAttr, RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?: return null
        return Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)
    }

    private fun titleTag(html: String): String? =
        Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)

    /** Decode the few HTML entities common in titles, collapse whitespace, trim. */
    private fun clean(s: String): String =
        s.replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("""\s+"""), " ")
            .trim()
}
