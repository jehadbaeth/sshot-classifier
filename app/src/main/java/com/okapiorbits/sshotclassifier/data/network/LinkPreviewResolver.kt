package com.okapiorbits.sshotclassifier.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a QR link's destination page and extracts a [LinkPreview] from its HTML.
 * Deliberately conservative because the URL comes from a scanned QR code (untrusted):
 *  - http/https only, re-checked after every redirect (a redirect can switch scheme);
 *  - rejects loopback / private (LAN) hosts so a public URL cannot bounce to the device's
 *    own network;
 *  - connect/read timeouts, a hard byte cap, and a content-type check so we never parse a
 *    huge or non-HTML body;
 *  - swallows every failure and returns null, so a resolution error never fails a worker
 *    batch or marks a capture FAILED.
 *
 * This only fetches the page text to read its <head>. The og:image is returned as a URL
 * string; whether that image is actually loaded is gated at the render site by the
 * downloadPreviewImages preference.
 */
@Singleton
class LinkPreviewResolver @Inject constructor() {

    suspend fun resolve(rawUrl: String): LinkPreview? = withContext(Dispatchers.IO) {
        runCatching { fetchAndParse(rawUrl) }.getOrNull()
    }

    private fun fetchAndParse(rawUrl: String): LinkPreview? {
        var url = URL(rawUrl)
        if (!isAllowed(url)) return null

        var redirects = 0
        while (true) {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false // follow manually so we can re-check each hop
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return null
                    if (++redirects > MAX_REDIRECTS) return null
                    url = URL(url, location) // resolve relative redirects
                    if (!isAllowed(url)) return null
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) return null

                val contentType = conn.contentType?.lowercase(Locale.US).orEmpty()
                if (!contentType.contains("text/html") && !contentType.contains("xhtml")) return null

                val html = conn.inputStream.use { readCapped(it) }
                val preview = OgParser.parse(html)
                return if (preview.isEmpty()) null else preview
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun readCapped(input: java.io.InputStream): String {
        val buffer = ByteArray(8 * 1024)
        val out = StringBuilder()
        var total = 0
        while (total < MAX_BYTES) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            out.append(String(buffer, 0, n, Charsets.UTF_8))
            // The metadata lives in <head>; stop early once it closes.
            if (out.contains("</head>", ignoreCase = true)) break
        }
        return out.toString()
    }

    private fun isAllowed(url: URL): Boolean {
        val scheme = url.protocol?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") return false
        return !isPrivateHost(url.host)
    }

    /** Coarse private/loopback host guard (not full SSRF defense; enough for a personal app). */
    private fun isPrivateHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return true
        val h = host.lowercase(Locale.US)
        if (h == "localhost" || h.endsWith(".local") || h == "::1") return true
        if (h.startsWith("10.") || h.startsWith("127.") || h.startsWith("192.168.") || h.startsWith("169.254.")) return true
        // 172.16.0.0 - 172.31.255.255
        Regex("""^172\.(1[6-9]|2\d|3[0-1])\.""").find(h)?.let { return true }
        return false
    }

    companion object {
        private const val TIMEOUT_MS = 8_000
        private const val MAX_REDIRECTS = 5
        private const val MAX_BYTES = 512 * 1024
        private const val USER_AGENT = "ScreenshotClassifier/1.0 (+offline-first link preview)"
    }
}
