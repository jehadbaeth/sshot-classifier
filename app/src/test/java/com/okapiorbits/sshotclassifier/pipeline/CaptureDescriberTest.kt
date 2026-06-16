package com.okapiorbits.sshotclassifier.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureDescriberTest {

    private val describer = StructuredCaptureDescriber()

    @Test
    fun storefront_with_text() {
        val d = describer.describe(
            CaptureContext(
                ocrText = "DR MEYER\nDENTIST\nOpen 9-5",
                tags = listOf("storefront", "street sign"),
                qrPayload = null,
                qrIsUrl = false,
            )
        )
        assertEquals("Storefront. Text reads: \"DR MEYER DENTIST Open 9-5\".", d)
    }

    @Test
    fun qr_url_uses_host_not_full_url() {
        val d = describer.describe(
            CaptureContext(
                ocrText = "Scan for menu",
                tags = listOf("qr code"),
                qrPayload = "https://www.example.com/menu?ref=table12",
                qrIsUrl = true,
            )
        )
        assertEquals("QR code linking to example.com. Text reads: \"Scan for menu\".", d)
    }

    @Test
    fun qr_non_url_payload_is_quoted_and_truncated() {
        val payload = "WIFI:S:MyNetwork;T:WPA;P:" + "x".repeat(200) + ";;"
        val d = describer.describe(
            CaptureContext(ocrText = "", tags = emptyList(), qrPayload = payload, qrIsUrl = false)
        )
        assertTrue(d.startsWith("QR code: \"WIFI:S:MyNetwork"))
        assertTrue("should be truncated with ellipsis", d.contains("…"))
    }

    @Test
    fun no_recognized_tag_falls_back_to_photo() {
        val d = describer.describe(
            CaptureContext(ocrText = "", tags = listOf("other"), qrPayload = null, qrIsUrl = false)
        )
        assertEquals("Photo.", d)
    }

    @Test
    fun other_tag_is_skipped_in_favor_of_a_real_tag() {
        val d = describer.describe(
            CaptureContext(
                ocrText = "",
                tags = listOf("other", "menu"),
                qrPayload = null,
                qrIsUrl = false,
            )
        )
        assertEquals("Menu.", d)
    }

    @Test
    fun long_ocr_is_truncated() {
        val d = describer.describe(
            CaptureContext(
                ocrText = "word ".repeat(100),
                tags = listOf("poster"),
                qrPayload = null,
                qrIsUrl = false,
            )
        )
        assertTrue("description should be capped", d.length < 200)
        assertTrue(d.contains("…"))
    }
}
