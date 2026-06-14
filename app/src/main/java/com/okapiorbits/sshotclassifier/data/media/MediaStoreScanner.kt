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

/**
 * Reads screenshots from MediaStore. Screenshots are identified by their bucket /
 * relative path containing "Screenshots", which is how every major OEM files them.
 */
class MediaStoreScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun queryScreenshots(): List<DiscoveredScreenshot> {
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

        // On Q+ filter by RELATIVE_PATH, otherwise by DATA path. Both match "Screenshots".
        val selection = "${pathColumn()} LIKE ?"
        val selectionArgs = arrayOf("%Screenshots%")
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

    private fun pathColumn(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.DATA
        }
}
