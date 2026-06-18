package com.okapiorbits.sshotclassifier.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.uiDataStore: DataStore<Preferences> by preferencesDataStore(name = "ui_prefs")

/** Persists appearance + developer preferences. */
@Singleton
class UiPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DEV_MODE = booleanPreferencesKey("dev_mode")
    }

    /** false = fixed brand palette (default); true = Material You on Android 12+. */
    val dynamicColor: Flow<Boolean> = context.uiDataStore.data.map { it[Keys.DYNAMIC_COLOR] ?: false }

    suspend fun setDynamicColor(enabled: Boolean) =
        context.uiDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }

    /**
     * Developer mode (default off). When on, normally-gated configs can be force-enabled for
     * testing — notably the experimental generative VLM describer on under-spec devices — and
     * debug-log export is offered. Intended for testers, not everyday use.
     */
    val devMode: Flow<Boolean> = context.uiDataStore.data.map { it[Keys.DEV_MODE] ?: false }

    suspend fun setDevMode(enabled: Boolean) =
        context.uiDataStore.edit { it[Keys.DEV_MODE] = enabled }

    /** One-shot read for non-reactive call sites (e.g. the describer router). */
    suspend fun devModeNow(): Boolean = devMode.first()
}
