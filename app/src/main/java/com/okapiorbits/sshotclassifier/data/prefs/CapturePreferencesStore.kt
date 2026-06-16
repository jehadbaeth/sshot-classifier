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

private val Context.captureDataStore: DataStore<Preferences> by preferencesDataStore(name = "capture_prefs")

/** Persists [CapturePreferences] in a Preferences DataStore. */
@Singleton
class CapturePreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val RESOLVE_QR_LINKS = booleanPreferencesKey("resolve_qr_links")
        val RESOLVE_TRIGGER = stringPreferencesKey("resolve_trigger")
        val RESOLVE_WIFI_ONLY = booleanPreferencesKey("resolve_wifi_only")
        val DOWNLOAD_PREVIEW_IMAGES = booleanPreferencesKey("download_preview_images")
        val DESCRIPTION_SOURCE = stringPreferencesKey("description_source")
        val DECODE_QR_CODES = booleanPreferencesKey("decode_qr_codes")
        val CAPTURE_ALBUM_ROOT = stringPreferencesKey("capture_album_root")
    }

    val preferences: Flow<CapturePreferences> = context.captureDataStore.data.map { it.toPrefs() }

    /** One-shot read for non-reactive callers (the worker, the capture screen). */
    suspend fun current(): CapturePreferences = context.captureDataStore.data.first().toPrefs()

    suspend fun setResolveQrLinks(value: Boolean) =
        context.captureDataStore.edit { it[Keys.RESOLVE_QR_LINKS] = value }

    suspend fun setResolveTrigger(value: ResolveTrigger) =
        context.captureDataStore.edit { it[Keys.RESOLVE_TRIGGER] = value.name }

    suspend fun setResolveOnWifiOnly(value: Boolean) =
        context.captureDataStore.edit { it[Keys.RESOLVE_WIFI_ONLY] = value }

    suspend fun setDownloadPreviewImages(value: Boolean) =
        context.captureDataStore.edit { it[Keys.DOWNLOAD_PREVIEW_IMAGES] = value }

    suspend fun setDescriptionSource(value: DescriptionSource) =
        context.captureDataStore.edit { it[Keys.DESCRIPTION_SOURCE] = value.name }

    suspend fun setDecodeQrCodes(value: Boolean) =
        context.captureDataStore.edit { it[Keys.DECODE_QR_CODES] = value }

    suspend fun setCaptureAlbumRoot(root: String) = context.captureDataStore.edit {
        it[Keys.CAPTURE_ALBUM_ROOT] = Reorganization.sanitizeFolder(root).let { s ->
            if (s == Reorganization.UNCATEGORIZED && root.isBlank()) CapturePreferences.DEFAULT_CAPTURE_ROOT else s
        }
    }

    private fun Preferences.toPrefs() = CapturePreferences(
        resolveQrLinks = this[Keys.RESOLVE_QR_LINKS] ?: false,
        resolveTrigger = this[Keys.RESOLVE_TRIGGER]?.let { runCatching { ResolveTrigger.valueOf(it) }.getOrNull() }
            ?: ResolveTrigger.MANUAL,
        resolveOnWifiOnly = this[Keys.RESOLVE_WIFI_ONLY] ?: true,
        downloadPreviewImages = this[Keys.DOWNLOAD_PREVIEW_IMAGES] ?: false,
        descriptionSource = this[Keys.DESCRIPTION_SOURCE]?.let { runCatching { DescriptionSource.valueOf(it) }.getOrNull() }
            ?: DescriptionSource.STRUCTURED,
        decodeQrCodes = this[Keys.DECODE_QR_CODES] ?: true,
        captureAlbumRoot = this[Keys.CAPTURE_ALBUM_ROOT]?.takeIf { it.isNotBlank() }
            ?: CapturePreferences.DEFAULT_CAPTURE_ROOT,
    )
}
