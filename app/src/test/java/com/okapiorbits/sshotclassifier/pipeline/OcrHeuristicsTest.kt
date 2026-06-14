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
}
