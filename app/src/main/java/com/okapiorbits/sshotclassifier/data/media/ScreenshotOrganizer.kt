package com.okapiorbits.sshotclassifier.data.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies processed screenshots into per-tag albums under
 * Pictures/ScreenshotClassifier/<album>/ (uncategorized when not confidently
 * tagged). Non-destructive: originals are untouched and the copy is a new
 * MediaStore entry, so no per-file user consent is needed. Idempotent: an album
 * file that already exists is skipped, so re-running only fills in new screenshots.
 *
 * Requires API 29+ (scoped MediaStore writes without a storage permission). On
 * older devices [isSupported] is false and the action is a no-op.
 */
@Singleton
class ScreenshotOrganizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ScreenshotDao,
) {
    data class Result(val copied: Int, val skipped: Int, val failed: Int)

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    suspend fun organizeIntoAlbums(): Result = withContext(Dispatchers.IO) {
        if (!isSupported) return@withContext Result(0, 0, 0)
        val resolver = context.contentResolver
        var copied = 0
        var skipped = 0
        var failed = 0

        for (item in dao.doneWithTags()) {
            val topLabel = item.tags.maxByOrNull { it.weight }?.label
            val album = Reorganization.albumFor(item.screenshot.needs_review, topLabel)
            val relativePath = "${android.os.Environment.DIRECTORY_PICTURES}/${Reorganization.ROOT}/$album/"
            val displayName = "sc_${item.screenshot.id}.png"

            if (existsIn(relativePath, displayName)) {
                skipped++
                continue
            }
            val ok = copyOne(Uri.parse(item.screenshot.file_path), relativePath, displayName)
            if (ok) copied++ else failed++
        }
        Result(copied, skipped, failed)
    }

    private fun existsIn(relativePath: String, displayName: String): Boolean {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH}=? AND ${MediaStore.Images.Media.DISPLAY_NAME}=?"
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            selection,
            arrayOf(relativePath, displayName),
            null,
        )?.use { return it.moveToFirst() }
        return false
    }

    private fun copyOne(source: Uri, relativePath: String, displayName: String): Boolean {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val dest = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openInputStream(source).use { input ->
                if (input == null) {
                    resolver.delete(dest, null, null)
                    return false
                }
                resolver.openOutputStream(dest).use { out ->
                    if (out == null) {
                        resolver.delete(dest, null, null)
                        return false
                    }
                    input.copyTo(out)
                }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(dest, values, null, null)
            true
        } catch (e: Exception) {
            runCatching { resolver.delete(dest, null, null) }
            false
        }
    }
}
