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
        /**
         * Specific, category-defining signals: one alone clears [MIN_EMIT] and emits
         * the tag (e.g. "subtotal", "stacktrace", "add to cart"). Use sparingly, only
         * for terms that are rare outside this category.
         */
        val strong: List<String> = emptyList(),
        /**
         * Generic signals that also appear on unrelated screens ("total", "account",
         * "order", "message"). One weak hit, or even two, stays below the floor; they
         * only emit a tag when corroborated by a strong hit or several signals. This is
         * the main false-positive guard: a Settings "Account" row no longer reads as
         * finance, a fitness "Total steps" no longer reads as a receipt.
         */
        val weak: List<String> = emptyList(),
        val patterns: List<Regex> = emptyList(),
    )

    private val rules = listOf(
        Rule(
            label = "receipt",
            strong = listOf("subtotal", "receipt", "invoice", "amount due", "change due"),
            weak = listOf("total", "tax", "vat", "qty"),
            patterns = listOf(Regex("""[$€£]\s?\d"""), Regex("""\b\d+\.\d{2}\b""")),
        ),
        Rule(
            label = "error / crash",
            strong = listOf("exception", "stack trace", "stacktrace", "traceback", "segmentation fault", "nullpointer", "errno", "unhandled", "error:"),
            weak = listOf("fatal"),
            patterns = listOf(Regex("""\bat\s+[\w.$]+\([\w.]+:\d+\)"""), Regex("""[\w.]+(Error|Exception)\b""")),
        ),
        Rule(
            label = "code editor",
            // Tokens that are almost always code; one is enough.
            strong = listOf("#include", "console.log", "println", "def ", "function "),
            // Words that also occur in prose ("public transport", "return ticket");
            // need a couple together (or a code pattern) to count.
            weak = listOf("import ", "public ", "private ", "class ", "return ", "const ", "void ", "var ", "val "),
            patterns = listOf(Regex("""[{};]\s*$""", RegexOption.MULTILINE), Regex("""=>|::|->|!=|==""")),
        ),
        Rule(
            label = "finance",
            strong = listOf("iban", "routing", "transaction"),
            weak = listOf("balance", "account", "transfer", "deposit", "withdraw", "credit", "debit", "statement"),
            patterns = listOf(Regex("""[$€£]\s?\d""")),
        ),
        Rule(
            label = "calendar",
            // Day and month names are individually weak (a single "Monday" is not a
            // calendar); a real calendar shows several, or names plus a time.
            weak = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "january", "february", "march", "april", "june", "july", "august", "september", "october", "november", "december", "event", "all day"),
            patterns = listOf(Regex("""\b\d{1,2}:\d{2}\s?(am|pm)\b""")),
        ),
        Rule(
            label = "chat / messaging",
            // No bare-time pattern: the status bar clock appears on every
            // screenshot and would tag them all as chat.
            strong = listOf("is typing", "last seen", "delivered", "forwarded"),
            weak = listOf("typing", "online", "message", "reply", "sent a"),
        ),
        Rule(
            label = "shopping",
            strong = listOf("add to cart", "buy now", "checkout", "free shipping", "out of stock", "wishlist"),
            weak = listOf("in stock", "price", "order", "delivery"),
            patterns = listOf(Regex("""[$€£]\s?\d""")),
        ),
        Rule(
            label = "social media",
            // No bare @/# pattern: "#include", "#define" etc. tag every code
            // screenshot as social. Reddit/forum markers live here too: CLIP reads
            // text-heavy threads as documents, so OCR is the reliable signal.
            strong = listOf("followers", "following", "retweet", "repost", "your story", "view profile", "reels", "upvote", "downvote", "subreddit", "posted by"),
            weak = listOf("likes", "comments", "shares", "karma", "crosspost"),
            // r/<sub> and u/<user> are distinctive; \b avoids matching "year/" etc.
            patterns = listOf(Regex("""\br/\w"""), Regex("""\bu/\w""")),
        ),
        Rule(
            label = "email",
            // No dedicated tag existed before; CLIP routed "an email inbox" to
            // document. Email screens have very reliable text markers.
            strong = listOf("unsubscribe", "reply all", "forwarded message", "subject:", "compose email", "inbox"),
            weak = listOf("reply", "forward", "to:", "from:", "cc:", "bcc:", "attachment", "draft", "archive", "mark as read"),
            // A literal email address is corroborating but appears on many screens,
            // so it stays a weak pattern, not a strong keyword.
            patterns = listOf(Regex("""\b[\w.+-]+@[\w-]+\.[a-z]{2,}\b""")),
        ),
        Rule(
            label = "browser / web",
            strong = listOf("http://", "https://", "www."),
            weak = listOf("sign in", "search"),
            patterns = listOf(Regex("""\b[\w-]+\.(com|org|net|io|gov|edu)\b""")),
        ),
    )

    fun classify(rawText: String): List<TagCandidate> {
        if (rawText.isBlank()) return emptyList()
        val text = rawText.lowercase()
        val out = mutableListOf<TagCandidate>()
        for (rule in rules) {
            var strongHits = 0
            for (kw in rule.strong) if (text.contains(kw)) strongHits++
            var weakHits = 0
            for (kw in rule.weak) if (text.contains(kw)) weakHits++
            var patternHits = 0
            for (re in rule.patterns) if (re.containsMatchIn(text)) patternHits++

            val weight = minOf(
                1f,
                strongHits * STRONG_HIT + weakHits * WEAK_HIT + patternHits * PATTERN_HIT,
            )
            // Floor: one strong hit clears it; generic signals must corroborate. With
            // WEAK_HIT below half the floor, two generic words alone still don't emit.
            if (weight >= MIN_EMIT) out += TagCandidate(rule.label, weight)
        }
        return out.sortedByDescending { it.weight }
    }

    companion object {
        /** A candidate must reach this confidence to be emitted at all. */
        const val MIN_EMIT = 0.30f

        /** A specific, category-defining keyword. One clears the floor. */
        private const val STRONG_HIT = 0.34f

        /** A generic keyword. Below half the floor, so two alone still do not emit. */
        private const val WEAK_HIT = 0.14f

        /** A shared structural pattern (currency, decimal, domain). Same weight as weak. */
        private const val PATTERN_HIT = 0.14f
    }
}
