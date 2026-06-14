package com.okapiorbits.sshotclassifier.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.watchDataStore: DataStore<Preferences> by preferencesDataStore(name = "watch_prefs")

/**
 * Persists the set of folders (MediaStore bucket display names) the app watches for
 * new images. Defaults to the device "Screenshots" bucket so behaviour is unchanged
 * until the user adds more folders.
 */
@Singleton
class WatchedFoldersStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val FOLDERS = stringSetPreferencesKey("watched_folders")
    }

    val folders: Flow<Set<String>> = context.watchDataStore.data.map { it[Keys.FOLDERS] ?: DEFAULT }

    /** One-shot read for the worker / repository sync. */
    suspend fun current(): Set<String> = context.watchDataStore.data.first()[Keys.FOLDERS] ?: DEFAULT

    suspend fun setWatched(folder: String, watched: Boolean) = context.watchDataStore.edit { prefs ->
        val updated = (prefs[Keys.FOLDERS] ?: DEFAULT).toMutableSet()
        if (watched) updated.add(folder) else updated.remove(folder)
        prefs[Keys.FOLDERS] = updated
    }

    companion object {
        /** Watched out of the box; matches the pre-configurable behaviour. */
        val DEFAULT = setOf("Screenshots")
    }
}
