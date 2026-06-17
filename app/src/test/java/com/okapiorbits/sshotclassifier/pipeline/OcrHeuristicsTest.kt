package com.okapiorbits.sshotclassifier.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrHeuristicsTest {

    private val heuristics = OcrHeuristics()

    private fun labels(text: String) = heuristics.classify(text).map { it.label }

    @Test
    fun `status bar clock alone does not produce a chat tag`() {
        // Regression: a bare HH:MM (the status bar clock) used to tag everything
        // as chat / messaging. A plain settings-ish screen must not.
        val text = "11:34\nNetwork & internet\nConnected devices\nApps\nBattery"
        assertFalse("chat / messaging" in labels(text))
    }

    @Test
    fun `source code is tagged code editor`() {
        val text = """
            #include <linux/sched.h>
            public class Foo {
                void run() { return; }
            }
        """.trimIndent()
        assertTrue("code editor" in labels(text))
    }

    @Test
    fun `receipt text is tagged receipt`() {
        val text = "GROCERY MART\nSubtotal 12.40\nTax 0.99\nTotal $13.39\nThank you"
        assertTrue("receipt" in labels(text))
    }

    @Test
    fun `error text is tagged error`() {
        val text = "java.lang.NullPointerException\n    at com.example.App.main(App.java:42)"
        assertTrue("error / crash" in labels(text))
    }

    @Test
    fun `chat keywords still tag chat`() {
        val text = "Alice is typing…\nonline\nDelivered\nReply"
        assertTrue("chat / messaging" in labels(text))
    }

    @Test
    fun `code with hash directives is not tagged social media`() {
        // Regression: "#include" used to match a bare-hashtag pattern.
        val text = "#include <stdio.h>\n#define MAX 10\nint main() { return 0; }"
        assertFalse("social media" in labels(text))
    }

    @Test
    fun `blank text yields no tags`() {
        assertEquals(emptyList<String>(), labels("   "))
    }

    @Test
    fun `a code editor showing a repo url is not tagged browser`() {
        // Regression (2026-06-17): a bare URL used to be a STRONG browser marker, so any
        // screen quoting a link read as a browser. A code editor is not a browser.
        val text = "// clone from https://github.com/acme/widget\nfun main() { println(\"hi\") }"
        assertFalse("browser / web" in labels(text))
    }

    @Test
    fun `a chat with a shared link is not tagged browser`() {
        val text = "Alice is typing…\nCheck this out https://example.org\nDelivered"
        assertFalse("browser / web" in labels(text))
        assertTrue("chat / messaging" in labels(text))
    }

    @Test
    fun `a page saturated with web signals is still tagged browser`() {
        // Multiple co-occurring web signals (scheme + www + domain + chrome words) still emit.
        val text = "https://www.example.com\nSign in\nSearch the web"
        assertTrue("browser / web" in labels(text))
    }

    @Test
    fun `a lone currency amount does not tag receipt finance or shopping`() {
        // Min-score floor: a single shared pattern hit (here a price) is below the
        // emit threshold, so it must not by itself produce any of these categories.
        val text = "Acme Widget\n$12.50\nView details"
        val l = labels(text)
        assertFalse("receipt" in l)
        assertFalse("finance" in l)
        assertFalse("shopping" in l)
    }

    @Test
    fun `a single weak code keyword is dropped`() {
        // One "import " (perHit 0.22) is below the 0.30 floor.
        assertFalse("code editor" in labels("import the groceries from the car"))
    }

    @Test
    fun `currency plus a category keyword does emit`() {
        // Pattern + keyword clears the floor.
        assertTrue("finance" in labels("Account balance\n$1,240.00"))
    }

    // ---- Labeled corpus ----
    //
    // Realistic OCR snippets with the labels each must (and must not) produce. The
    // negatives are the false positives the strong/weak split is meant to kill: a
    // single generic word ("account", "total", "order", "message") on a screen that
    // is not that category. This is hand-labeled, not a sample of real screenshots,
    // so it measures the rules' logic, not field precision.

    private data class Case(val name: String, val text: String, val include: Set<String>, val exclude: Set<String>)

    private val corpus = listOf(
        // --- true positives: the category must be produced ---
        Case("receipt", "GROCERY MART\nSubtotal 12.40\nTax 0.99\nTotal \$13.39\nThank you", setOf("receipt"), emptySet()),
        Case("stack trace", "java.lang.NullPointerException\n    at com.example.App.main(App.java:42)", setOf("error / crash"), emptySet()),
        // User-facing Android error dialogs (now OCR-only, since CLIP can't tell an
        // error dialog from any modal). Each strong phrase alone clears the floor.
        Case("anr dialog", "Maps isn't responding\nDo you want to close it?\nWait\nClose app", setOf("error / crash"), emptySet()),
        Case("keeps stopping", "App keeps stopping\nSkytube keeps stopping.\nApp info\nClose app", setOf("error / crash"), emptySet()),
        Case("unfortunately stopped", "Unfortunately, Settings has stopped.\nOK", setOf("error / crash"), emptySet()),
        Case("c source", "#include <stdio.h>\nint main() { return 0; }", setOf("code editor"), emptySet()),
        Case("python source", "def main():\n    return run()", setOf("code editor"), emptySet()),
        Case("bank", "Account balance\nIBAN DE89 3704 0044\n\$1,240.00", setOf("finance"), emptySet()),
        Case("messenger", "Alice is typing…\nDelivered\nReply", setOf("chat / messaging"), emptySet()),
        Case("product page", "Nike Air Max\nAdd to cart\nFree shipping\n\$129.99", setOf("shopping"), emptySet()),
        Case("profile", "1.2M followers\n340 following\nView profile", setOf("social media"), emptySet()),
        Case("browser", "https://example.com/login\nSign in\nSearch", setOf("browser / web"), emptySet()),
        Case("agenda", "Monday\nTuesday\n9:00 am Standup\nAll day", setOf("calendar"), emptySet()),
        Case("email inbox", "Inbox\nSubject: Your invoice\nReply all\nForward\nUnsubscribe", setOf("email"), emptySet()),
        Case("reddit thread", "r/android\nPosted by u/someone\n1.2k upvotes\n340 comments\nShare", setOf("social media"), emptySet()),

        // --- true negatives: the (old) tempting label must NOT be produced ---
        Case("settings screen", "Settings\nAccount\nPrivacy\nNotifications\nStorage", emptySet(), setOf("finance")),
        Case("fitness", "Today\nTotal steps 8,432\nDistance 3.1 km", emptySet(), setOf("receipt")),
        Case("support page", "Contact us\nSend us a message\nWe reply within 24 hours", emptySet(), setOf("chat / messaging")),
        Case("sorted list", "Sort order\nName A to Z\nDate added", emptySet(), setOf("shopping")),
        Case("prose with code words", "Please return the public library books in order", emptySet(), setOf("code editor")),
        Case("single price", "Acme Widget\n\$12.50\nView details", emptySet(), setOf("receipt", "finance", "shopping")),
        Case("news fatal", "Fatal accident on the highway closes the road", emptySet(), setOf("error / crash")),
        // Weak error markers must NOT emit alone: "has stopped" / "something went
        // wrong" / "try again" are common in ordinary transient UI, so they stay below
        // the floor without a strong error phrase. Guards against re-creating the FP wave.
        Case("media stopped", "Playback has stopped", emptySet(), setOf("error / crash")),
        Case("generic retry", "Something went wrong. Try again", emptySet(), setOf("error / crash")),
        Case("show title", "Friday Night Live\nWatch now", emptySet(), setOf("calendar")),
        Case("signup form", "Create account\njohn@example.com\nPassword\nSign up", emptySet(), setOf("email")),
    )

    @Test
    fun `corpus precision and recall`() {
        val failures = mutableListOf<String>()
        for (c in corpus) {
            val got = labels(c.text).toSet()
            val missing = c.include - got
            val wrong = c.exclude intersect got
            if (missing.isNotEmpty()) failures += "${c.name}: missing $missing (got $got)"
            if (wrong.isNotEmpty()) failures += "${c.name}: false positive $wrong (got $got)"
        }
        assertTrue("corpus failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
