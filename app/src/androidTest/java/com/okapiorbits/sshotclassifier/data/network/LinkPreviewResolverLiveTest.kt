package com.okapiorbits.sshotclassifier.data.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Live, network-dependent verification of [LinkPreviewResolver] end to end: a real HTTP
 * fetch + parse on the device. This is the one part of QR resolution the offline unit tests
 * (OgParserTest, ResolvePolicyTest) cannot cover.
 *
 * Local-only by nature: CI is Linux with no emulator, and it requires outbound network, so
 * it is never run in CI. Run it manually on an emulator/device with internet. It targets
 * stable endpoints with minimal HTML to keep flakiness low; if the network is down the
 * fetch returns null and the test is skipped rather than failed.
 */
@RunWith(AndroidJUnit4::class)
class LinkPreviewResolverLiveTest {

    private val resolver = LinkPreviewResolver()

    @Test
    fun fetchesTitleFromARealPage() = runBlocking {
        val preview = resolver.resolve("https://example.com")
        // Network may be unavailable in some run environments; don't fail the suite for that.
        org.junit.Assume.assumeNotNull(preview)
        assertNotNull(preview!!.title)
        assertTrue(
            "expected example.com's <title>, got \"${preview.title}\"",
            preview.title!!.contains("Example", ignoreCase = true),
        )
    }

    @Test
    fun rejectsNonHttpScheme() = runBlocking {
        // A QR can encode anything; file:// must never be fetched.
        assertNull(resolver.resolve("file:///etc/hosts"))
    }

    @Test
    fun rejectsLanHost() = runBlocking {
        assertNull(resolver.resolve("http://192.168.1.1/admin"))
    }

    private fun assertNull(value: Any?) = assertFalse("expected null", value != null)
}
