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

/**
 * Which script(s) OCR runs. Latin uses ML Kit; Arabic uses Tesseract; Both runs each; Auto runs
 * the fast Latin pass and only falls back to Arabic when Latin finds little text (cheap, favours
 * whichever script dominates an image).
 */
enum class OcrLanguage { LATIN, ARABIC, BOTH, AUTO }

/** Persists appearance + developer preferences. */
@Singleton
class UiPreferencesStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val DEV_MODE = booleanPreferencesKey("dev_mode")
        val OCR_LANGUAGE = androidx.datastore.preferences.core.stringPreferencesKey("ocr_language")
    }

    /**
     * true (default) = Material You (wallpaper-based colour) on Android 12+, falling back to the
     * brand palette on older devices; false = always the fixed brand palette. Default-on so the
     * app adopts the user's system colours out of the box.
     */
    val dynamicColor: Flow<Boolean> = context.uiDataStore.data.map { it[Keys.DYNAMIC_COLOR] ?: true }

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

    /**
     * OCR script selection (default Latin = ML Kit, the prior behaviour). Arabic / Both bring in
     * Tesseract. Note: classification heuristics and semantic search remain English/Latin-tuned,
     * so Arabic gains text extraction + display + keyword search, not auto-tagging.
     */
    val ocrLanguage: Flow<OcrLanguage> = context.uiDataStore.data.map {
        it[Keys.OCR_LANGUAGE]?.let { v -> runCatching { OcrLanguage.valueOf(v) }.getOrNull() } ?: OcrLanguage.LATIN
    }

    suspend fun setOcrLanguage(value: OcrLanguage) =
        context.uiDataStore.edit { it[Keys.OCR_LANGUAGE] = value.name }

    /** One-shot read for the worker (reads once per run, off the hot path). */
    suspend fun ocrLanguageNow(): OcrLanguage = ocrLanguage.first()
}
