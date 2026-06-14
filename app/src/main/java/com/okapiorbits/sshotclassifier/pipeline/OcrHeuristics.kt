package com.okapiorbits.sshotclassifier.pipeline

import javax.inject.Inject
import javax.inject.Singleton

/** A candidate tag with a 0..1 confidence derived from OCR text signals. */
data class TagCandidate(val label: String, val weight: Float)

/**
 * Text-only classifier over OCR output. This is the OCR co-classifier from the
 * CLIP spike: the categories CLIP confused (code vs error vs receipt vs finance,
 * etc.) are exactly the ones cheaply separable by keywords and patterns.
 *
 * Each rule scores a category from distinct signal hits. Weight saturates so a
 * couple of strong signals already give high confidence. Labels match the
 * user-facing taxonomy in docs/design.md section 6.1. CLIP-led categories (map,
 * game, video, photo-like) are intentionally absent here.
 */
@Singleton
class OcrHeuristics @Inject constructor() {

    private data class Rule(
        val label: String,
        val keywords: List<String> = emptyList(),
        val patterns: List<Regex> = emptyList(),
        val perHit: Float = 0.34f,
        /**
         * Pattern hits count for less than keyword hits. Patterns like a currency
         * amount or a decimal number are shared across receipt/finance/shopping, so
         * on their own (below MIN_EMIT) they must not emit a tag; they only add
         * confidence when a category keyword is also present.
         */
        val patternHit: Float = 0.14f,
    )

    private val rules = listOf(
        Rule(
            label = "receipt",
            keywords = listOf("subtotal", "total", "tax", "vat", "receipt", "invoice", "amount due", "change due", "qty"),
            patterns = listOf(Regex("""[$€£]\s?\d"""), Regex("""\b\d+\.\d{2}\b""")),
        ),
        Rule(
            label = "error / crash",
            keywords = listOf("exception", "stack trace", "stacktrace", "traceback", "fatal", "nullpointer", "segmentation fault", "error:", "errno", "unhandled"),
            patterns = listOf(Regex("""\bat\s+[\w.$]+\([\w.]+:\d+\)"""), Regex("""[\w.]+(Error|Exception)\b""")),
        ),
        Rule(
            label = "code editor",
            keywords = listOf("import ", "public ", "private ", "function ", "def ", "class ", "return ", "const ", "void ", "#include", "println", "console.log", "var ", "val "),
            patterns = listOf(Regex("""[{};]\s*$""", RegexOption.MULTILINE), Regex("""=>|::|->|!=|==""")),
            perHit = 0.22f,
        ),
        Rule(
            label = "finance",
            keywords = listOf("balance", "account", "transaction", "transfer", "iban", "deposit", "withdraw", "credit", "debit", "statement", "routing"),
            patterns = listOf(Regex("""[$€£]\s?\d""")),
        ),
        Rule(
            label = "calendar",
            keywords = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "january", "february", "march", "april", "june", "july", "august", "september", "october", "november", "december", "event", "all day"),
            patterns = listOf(Regex("""\b\d{1,2}:\d{2}\s?(am|pm)\b""")),
            perHit = 0.25f,
        ),
        Rule(
            label = "chat / messaging",
            // No bare-time pattern: the status bar clock appears on every
            // screenshot and would tag them all as chat. Rely on chat keywords.
            keywords = listOf("typing", "online", "last seen", "delivered", "message", "reply", "forwarded", "sent a", "is typing"),
            perHit = 0.34f,
        ),
        Rule(
            label = "shopping",
            keywords = listOf("add to cart", "buy now", "checkout", "free shipping", "in stock", "out of stock", "price", "order", "wishlist", "delivery"),
            patterns = listOf(Regex("""[$€£]\s?\d""")),
        ),
        Rule(
            label = "social media",
            // No bare @/# pattern: "#include", "#define" etc. tag every code
            // screenshot as social. Rely on social keywords instead.
            keywords = listOf("likes", "comments", "shares", "followers", "following", "retweet", "repost", "your story", "view profile", "reels"),
            perHit = 0.34f,
        ),
        Rule(
            label = "browser / web",
            keywords = listOf("http://", "https://", "www.", "sign in", "search"),
            patterns = listOf(Regex("""\b[\w-]+\.(com|org|net|io|gov|edu)\b""")),
            perHit = 0.25f,
        ),
    )

    fun classify(rawText: String): List<TagCandidate> {
        if (rawText.isBlank()) return emptyList()
        val text = rawText.lowercase()
        val out = mutableListOf<TagCandidate>()
        for (rule in rules) {
            var keywordHits = 0
            for (kw in rule.keywords) if (text.contains(kw)) keywordHits++
            var patternHits = 0
            for (re in rule.patterns) if (re.containsMatchIn(text)) patternHits++

            val weight = minOf(1f, keywordHits * rule.perHit + patternHits * rule.patternHit)
            // Minimum-score floor: a single weak keyword, or a lone shared pattern
            // (e.g. a currency amount), is just noise for fusion, so do not emit it.
            if (weight >= MIN_EMIT) out += TagCandidate(rule.label, weight)
        }
        return out.sortedByDescending { it.weight }
    }

    companion object {
        /** A candidate must reach this confidence to be emitted at all. */
        const val MIN_EMIT = 0.30f
    }
}
