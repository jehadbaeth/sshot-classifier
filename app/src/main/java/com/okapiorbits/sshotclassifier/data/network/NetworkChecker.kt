package com.okapiorbits.sshotclassifier.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.okapiorbits.sshotclassifier.data.repository.NetworkState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Reads the current connectivity into a pure [NetworkState] for [com.okapiorbits.sshotclassifier.data.repository.ResolvePolicy]. */
@Singleton
class NetworkChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun current(): NetworkState {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkState(isConnected = false, isUnmetered = false)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?: return NetworkState(isConnected = false, isUnmetered = false)
        val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val unmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return NetworkState(isConnected = connected, isUnmetered = unmetered)
    }
}
