package com.okapiorbits.sshotclassifier.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OgParserTest {

    @Test
    fun extractsOpenGraphTags() {
        val html = """
            <html><head>
            <meta property="og:title" content="Example Shop — Summer Sale" />
            <meta property="og:description" content="Up to 50% off all items" />
            <meta property="og:image" content="https://cdn.example.com/og.jpg" />
            <title>fallback title</title>
            </head><body>...</body></html>
        """.trimIndent()
        val p = OgParser.parse(html)
        assertEquals("Example Shop — Summer Sale", p.title)
        assertEquals("Up to 50% off all items", p.description)
        assertEquals("https://cdn.example.com/og.jpg", p.imageUrl)
    }

    @Test
    fun fallsBackToTitleAndMetaDescription() {
        val html = """
            <head>
            <title>My Page Title</title>
            <meta name="description" content="A plain meta description">
            </head>
        """.trimIndent()
        val p = OgParser.parse(html)
        assertEquals("My Page Title", p.title)
        assertEquals("A plain meta description", p.description)
        assertNull(p.imageUrl)
    }

    @Test
    fun handlesContentBeforeProperty_andSingleQuotes() {
        val html = "<meta content='Reversed attrs' property='og:title'>"
        assertEquals("Reversed attrs", OgParser.parse(html).title)
    }

    @Test
    fun decodesEntitiesAndCollapsesWhitespace() {
        val html = "<title>Tom &amp; Jerry\n   &quot;show&quot;</title>"
        assertEquals("Tom & Jerry \"show\"", OgParser.parse(html).title)
    }

    @Test
    fun emptyOnNoMetadata() {
        assertTrue(OgParser.parse("<html><body>nothing here</body></html>").isEmpty())
    }
}
