package com.okapiorbits.sshotclassifier.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.uiDataStore: DataStore<Preferences> by preferencesDataStore(name = "ui_prefs")

/** Persists appearance preferences. Currently just the Material You opt-in. */
@Singleton
class UiPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }

    /** false = fixed brand palette (default); true = Material You on Android 12+. */
    val dynamicColor: Flow<Boolean> = context.uiDataStore.data.map { it[Keys.DYNAMIC_COLOR] ?: false }

    suspend fun setDynamicColor(enabled: Boolean) =
        context.uiDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
}
