package com.okapiorbits.sshotclassifier.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.okapiorbits.sshotclassifier.data.media.Reorganization
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.reorgDataStore: DataStore<Preferences> by preferencesDataStore(name = "reorg_prefs")

/** Persists [ReorgPreferences] in a Preferences DataStore. */
@Singleton
class ReorgPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val ALBUM_ROOT = stringPreferencesKey("album_root")
        val NEEDS_REVIEW_TO_UNCATEGORIZED = booleanPreferencesKey("needs_review_to_uncategorized")
        val AUTO_RUN = booleanPreferencesKey("auto_run")
    }

    val preferences: Flow<ReorgPreferences> = context.reorgDataStore.data.map { it.toPrefs() }

    /** One-shot read for non-reactive callers (e.g. the worker). */
    suspend fun current(): ReorgPreferences = context.reorgDataStore.data.first().toPrefs()

    suspend fun setMode(mode: ReorgMode) = context.reorgDataStore.edit { it[Keys.MODE] = mode.name }

    suspend fun setAlbumRoot(root: String) = context.reorgDataStore.edit {
        // Keep the stored value safe as a folder name; fall back to the default if blank.
        it[Keys.ALBUM_ROOT] = Reorganization.sanitizeFolder(root).let { s ->
            if (s == Reorganization.UNCATEGORIZED && root.isBlank()) Reorganization.ROOT else s
        }
    }

    suspend fun setNeedsReviewToUncategorized(value: Boolean) =
        context.reorgDataStore.edit { it[Keys.NEEDS_REVIEW_TO_UNCATEGORIZED] = value }

    suspend fun setAutoRun(value: Boolean) =
        context.reorgDataStore.edit { it[Keys.AUTO_RUN] = value }

    private fun Preferences.toPrefs() = ReorgPreferences(
        mode = this[Keys.MODE]?.let { runCatching { ReorgMode.valueOf(it) }.getOrNull() } ?: ReorgMode.COPY,
        albumRoot = this[Keys.ALBUM_ROOT]?.takeIf { it.isNotBlank() } ?: Reorganization.ROOT,
        needsReviewToUncategorized = this[Keys.NEEDS_REVIEW_TO_UNCATEGORIZED] ?: true,
        autoRun = this[Keys.AUTO_RUN] ?: false,
    )
}
