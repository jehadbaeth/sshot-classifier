package com.okapiorbits.sshotclassifier.pipeline.clip

import java.io.InputStream
import java.util.regex.Pattern

/**
 * Faithful Kotlin port of open_clip's SimpleTokenizer (byte-level BPE, 49408-token
 * vocab, context length 77). This must match the Python tokenizer exactly, or the
 * text encoder produces embeddings that no longer line up with the stored image
 * embeddings and semantic search silently degrades.
 *
 * The vocab is reconstructed in the same order open_clip builds it (byte-unicode
 * base, then `</w>` forms, then merges, then the two special tokens), so we only
 * need to bundle the original BPE merges (bpe_vocab_16e6.txt.gz) — not a separate
 * vocab file.
 *
 * Intentional simplification: open_clip's text cleaning runs ftfy.fix_text and
 * HTML unescape before lowercasing + whitespace collapse. For plain search
 * queries those are effectively identity, so we only do lowercase + whitespace
 * collapse. Documented in docs/design.md.
 */
class BpeTokenizer(mergesText: String) {

    private val byteEncoder: Map<Int, Char>
    private val encoder: Map<String, Int>
    private val bpeRanks: Map<Pair<String, String>, Int>
    private val cache = HashMap<String, List<String>>()

    val sotToken: Int
    val eotToken: Int

    private val pattern: Pattern = Pattern.compile(
        "'s|'t|'re|'ve|'m|'ll|'d|\\p{L}+|\\p{N}|[^\\s\\p{L}\\p{N}]+",
        Pattern.CASE_INSENSITIVE,
    )

    init {
        // bytes_to_unicode(): deterministic reversible byte<->unicode mapping.
        val bs = ArrayList<Int>()
        for (i in '!'.code..'~'.code) bs.add(i)
        for (i in '¡'.code..'¬'.code) bs.add(i)
        for (i in '®'.code..'ÿ'.code) bs.add(i)
        val cs = ArrayList(bs)
        var n = 0
        for (b in 0 until 256) {
            if (b !in bs) {
                bs.add(b)
                cs.add(256 + n)
                n++
            }
        }
        val be = HashMap<Int, Char>(256)
        val baseVocab = ArrayList<String>(bs.size)
        for (i in bs.indices) {
            val ch = cs[i].toChar()
            be[bs[i]] = ch
            baseVocab.add(ch.toString())
        }
        byteEncoder = be

        // merges[1 : 49152-256-2+1] == lines[1, 48895)
        val lines = mergesText.split("\n")
        val merges = ArrayList<Pair<String, String>>()
        val end = 49152 - 256 - 2 + 1
        for (i in 1 until end) {
            val parts = lines[i].split(" ")
            merges.add(Pair(parts[0], parts[1]))
        }

        // vocab order: base, base+</w>, merged pairs, specials.
        val vocab = ArrayList<String>(baseVocab.size * 2 + merges.size + 2)
        vocab.addAll(baseVocab)
        for (v in baseVocab) vocab.add(v + "</w>")
        for (m in merges) vocab.add(m.first + m.second)
        vocab.add("<start_of_text>")
        vocab.add("<end_of_text>")

        val enc = HashMap<String, Int>(vocab.size)
        for (i in vocab.indices) enc[vocab[i]] = i
        encoder = enc

        val ranks = HashMap<Pair<String, String>, Int>(merges.size)
        for (i in merges.indices) ranks[merges[i]] = i
        bpeRanks = ranks

        sotToken = enc.getValue("<start_of_text>")
        eotToken = enc.getValue("<end_of_text>")
    }

    /** Tokenize to a fixed-length context window: [sot] + bpe ids + [eot], zero-padded. */
    fun tokenize(text: String, contextLength: Int = 77): IntArray {
        val tokens = ArrayList<Int>()
        tokens.add(sotToken)
        tokens.addAll(encode(text))
        tokens.add(eotToken)
        val result = IntArray(contextLength)
        if (tokens.size > contextLength) {
            for (i in 0 until contextLength) result[i] = tokens[i]
            result[contextLength - 1] = eotToken
        } else {
            for (i in tokens.indices) result[i] = tokens[i]
        }
        return result
    }

    /** Byte-level BPE encode to vocab ids (no special tokens). */
    fun encode(text: String): List<Int> {
        val clean = cleanLower(text)
        val out = ArrayList<Int>()
        val m = pattern.matcher(clean)
        while (m.find()) {
            val piece = m.group()
            val sb = StringBuilder()
            for (b in piece.toByteArray(Charsets.UTF_8)) {
                sb.append(byteEncoder[b.toInt() and 0xFF])
            }
            for (sub in bpe(sb.toString())) {
                encoder[sub]?.let { out.add(it) }
            }
        }
        return out
    }

    private fun cleanLower(text: String): String =
        text.replace(WHITESPACE, " ").trim().lowercase()

    private fun bpe(token: String): List<String> {
        cache[token]?.let { return it }
        if (token.isEmpty()) return emptyList()

        var word = ArrayList<String>(token.length)
        for (i in 0 until token.length - 1) word.add(token[i].toString())
        word.add(token[token.length - 1].toString() + "</w>")

        if (word.size == 1) {
            val res = listOf(word[0])
            cache[token] = res
            return res
        }

        var pairs = getPairs(word)
        while (true) {
            var bigram: Pair<String, String>? = null
            var bestRank = Int.MAX_VALUE
            for (p in pairs) {
                val r = bpeRanks[p] ?: continue
                if (r < bestRank) {
                    bestRank = r
                    bigram = p
                }
            }
            if (bigram == null) break
            val (first, second) = bigram
            val newWord = ArrayList<String>(word.size)
            var i = 0
            while (i < word.size) {
                val j = indexOfFrom(word, first, i)
                if (j < 0) {
                    for (k in i until word.size) newWord.add(word[k])
                    break
                }
                for (k in i until j) newWord.add(word[k])
                i = j
                if (word[i] == first && i < word.size - 1 && word[i + 1] == second) {
                    newWord.add(first + second)
                    i += 2
                } else {
                    newWord.add(word[i])
                    i += 1
                }
            }
            word = newWord
            if (word.size == 1) break
            pairs = getPairs(word)
        }
        cache[token] = word
        return word
    }

    private fun getPairs(word: List<String>): Set<Pair<String, String>> {
        val pairs = LinkedHashSet<Pair<String, String>>()
        for (i in 0 until word.size - 1) pairs.add(Pair(word[i], word[i + 1]))
        return pairs
    }

    private fun indexOfFrom(list: List<String>, value: String, from: Int): Int {
        for (i in from until list.size) if (list[i] == value) return i
        return -1
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        /**
         * Build from a plain (uncompressed) merges stream (the bundled
         * clip/bpe_merges.txt). Note: aapt auto-gunzips and renames any .gz
         * asset at build time, so the merges are bundled decompressed.
         */
        fun fromStream(input: InputStream): BpeTokenizer =
            BpeTokenizer(input.readBytes().toString(Charsets.UTF_8))
    }
}
