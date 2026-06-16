package com.okapiorbits.sshotclassifier.data.repository

import com.okapiorbits.sshotclassifier.data.prefs.CapturePreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvePolicyTest {

    private val wifi = NetworkState(isConnected = true, isUnmetered = true)
    private val cellular = NetworkState(isConnected = true, isUnmetered = false)
    private val offline = NetworkState(isConnected = false, isUnmetered = false)

    @Test
    fun masterOff_neverResolves_evenWithUrlAndWifi() {
        val prefs = CapturePreferences(resolveQrLinks = false)
        assertEquals(ResolvePolicy.Decision.Disabled, ResolvePolicy.decide(prefs, isUrl = true, network = wifi))
    }

    @Test
    fun nonUrlPayload_isNotUrl() {
        val prefs = CapturePreferences(resolveQrLinks = true)
        assertEquals(ResolvePolicy.Decision.NotUrl, ResolvePolicy.decide(prefs, isUrl = false, network = wifi))
    }

    @Test
    fun wifiOnly_onCellular_doesNotResolve() {
        val prefs = CapturePreferences(resolveQrLinks = true, resolveOnWifiOnly = true)
        assertEquals(ResolvePolicy.Decision.NoNetwork, ResolvePolicy.decide(prefs, isUrl = true, network = cellular))
    }

    @Test
    fun wifiOnlyDisabled_onCellular_resolves() {
        val prefs = CapturePreferences(resolveQrLinks = true, resolveOnWifiOnly = false)
        assertEquals(ResolvePolicy.Decision.Resolve, ResolvePolicy.decide(prefs, isUrl = true, network = cellular))
    }

    @Test
    fun noNetwork_doesNotResolve() {
        val prefs = CapturePreferences(resolveQrLinks = true, resolveOnWifiOnly = false)
        assertEquals(ResolvePolicy.Decision.NoNetwork, ResolvePolicy.decide(prefs, isUrl = true, network = offline))
    }

    @Test
    fun enabled_url_wifi_resolves() {
        val prefs = CapturePreferences(resolveQrLinks = true, resolveOnWifiOnly = true)
        assertEquals(ResolvePolicy.Decision.Resolve, ResolvePolicy.decide(prefs, isUrl = true, network = wifi))
    }
}
