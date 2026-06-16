package com.okapiorbits.sshotclassifier.data.repository

import com.okapiorbits.sshotclassifier.data.prefs.CapturePreferences

/** Connectivity snapshot, kept free of Android types so [ResolvePolicy] stays pure. */
data class NetworkState(val isConnected: Boolean, val isUnmetered: Boolean)

/**
 * The single, pure gate for "should we fetch a QR link's preview right now". Both the
 * manual "Resolve link" button and the worker's automatic path call this, so the
 * privacy-critical rules (off means no fetch; Wi-Fi-only on a metered connection means
 * no fetch) live in one place and are unit-tested without a network.
 *
 * Note: this does NOT consider [CapturePreferences.resolveTrigger] — manual vs automatic
 * is the caller's concern (the worker only consults this when the trigger is AUTOMATIC;
 * the manual button always may, as long as the master switch is on).
 */
object ResolvePolicy {
    sealed interface Decision {
        /** Allowed to fetch. */
        data object Resolve : Decision
        /** The master switch is off. */
        data object Disabled : Decision
        /** The payload is not an http(s) URL. */
        data object NotUrl : Decision
        /** No usable network under the current Wi-Fi-only preference. */
        data object NoNetwork : Decision
    }

    fun decide(prefs: CapturePreferences, isUrl: Boolean, network: NetworkState): Decision = when {
        !prefs.resolveQrLinks -> Decision.Disabled
        !isUrl -> Decision.NotUrl
        !network.isConnected -> Decision.NoNetwork
        prefs.resolveOnWifiOnly && !network.isUnmetered -> Decision.NoNetwork
        else -> Decision.Resolve
    }
}
