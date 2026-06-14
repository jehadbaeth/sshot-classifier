package com.okapiorbits.sshotclassifier.data.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** A screenshot discovered in MediaStore, before it is processed or persisted. */
data class DiscoveredScreenshot(
    val mediaStoreId: Long,
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val dateAdded: Long,
    val width: Int,
    val height: Int,
)

/** An image folder on the device that the user can choose to watch. */
data class WatchableFolder(val name: String, val imageCount: Int)

/**
 * Reads images from MediaStore, filtered to a configurable set of folders
 * (MediaStore bucket display names, e.g. "Screenshots", "Camera"). BUCKET_DISPLAY_NAME
 * is the immediate parent folder name and is available on every supported API level,
 * so the same filter works on all devices.
 */
class MediaStoreScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Images in the given folders, newest first. [folders] are bucket display names;
     * an empty set matches nothing (the caller decides the default).
     */
    fun queryScreenshots(folders: Set<String>): List<DiscoveredScreenshot> {
        if (folders.isEmpty()) return emptyList()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            pathColumn(),
        )

        val placeholders = folders.joinToString(",") { "?" }
        val selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} IN ($placeholders)"
        val selectionArgs = folders.toTypedArray()
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val results = mutableListOf<DiscoveredScreenshot>()
        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
            ?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                val pathCol = cursor.getColumnIndexOrThrow(pathColumn())

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    results += DiscoveredScreenshot(
                        mediaStoreId = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        displayName = cursor.getString(nameCol) ?: "",
                        relativePath = cursor.getString(pathCol) ?: "",
                        dateAdded = cursor.getLong(dateCol) * 1000L,
                        width = cursor.getInt(widthCol),
                        height = cursor.getInt(heightCol),
                    )
                }
            }
        return results
    }

    /**
     * Distinct image folders on the device with their image counts, busiest first.
     * Powers the "watched folders" picker. MediaStore has no DISTINCT/GROUP BY, so
     * counts are tallied client side over the bucket-name column.
     */
    fun availableFolders(): List<WatchableFolder> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val counts = LinkedHashMap<String, Int>()
        context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(bucketCol)?.takeIf { it.isNotBlank() } ?: continue
                counts[name] = (counts[name] ?: 0) + 1
            }
        }
        return counts.entries.map { WatchableFolder(it.key, it.value) }.sortedByDescending { it.imageCount }
    }

    private fun pathColumn(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.DATA
        }
}
