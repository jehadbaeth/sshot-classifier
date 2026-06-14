package com.okapiorbits.sshotclassifier.data.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
import com.okapiorbits.sshotclassifier.data.db.entity.ReorgMoveEntity
import com.okapiorbits.sshotclassifier.data.prefs.ReorgMode
import com.okapiorbits.sshotclassifier.data.prefs.ReorgPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Files processed screenshots into per-tag albums under
 * Pictures/<root>/<album>/ (root and album routing are configurable via
 * [ReorgPreferences]). The copy itself is always non-destructive and idempotent:
 * a new MediaStore entry per screenshot, skipping any already present.
 *
 * In COPY mode that is all that happens, so no user consent is ever needed. In
 * MOVE mode the copy is the same, but the originals must then be deleted, which
 * scoped storage will not let us do silently: the caller takes the returned
 * [Result.pendingMoves], asks the user to approve the deletion via
 * [MediaStore.createDeleteRequest], and only on approval commits them with
 * [commitMoves]. That keeps every deletion explicitly user-approved and recorded
 * in an undo log. MOVE needs API 30+ (the batch delete request); below that it
 * silently degrades to COPY.
 *
 * Requires API 29+ (scoped MediaStore writes). On older devices [isSupported] is
 * false and the action is a no-op.
 */
@Singleton
class ScreenshotOrganizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ScreenshotDao,
) {
    /** A copy that has been made and whose original is awaiting delete consent. */
    data class PendingMove(
        val screenshotId: Long,
        val sourceUri: Uri,
        val albumUri: Uri,
        val originalDisplayName: String,
        val originalRelativePath: String,
    )

    data class Result(
        val copied: Int,
        val skipped: Int,
        val failed: Int,
        /** Non-empty only in MOVE mode: copies whose originals still need delete consent. */
        val pendingMoves: List<PendingMove> = emptyList(),
    )

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** MOVE needs the batch delete-request API (R+). Below that, MOVE degrades to COPY. */
    val moveSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    suspend fun organizeIntoAlbums(prefs: ReorgPreferences): Result = withContext(Dispatchers.IO) {
        if (!isSupported) return@withContext Result(0, 0, 0)
        val root = Reorganization.sanitizeRoot(prefs.albumRoot)
        val wantMove = prefs.mode == ReorgMode.MOVE && moveSupported

        var copied = 0
        var skipped = 0
        var failed = 0
        val pending = mutableListOf<PendingMove>()

        for (item in dao.doneWithTags()) {
            val topLabel = item.tags.maxByOrNull { it.weight }?.label
            val album = Reorganization.albumFor(
                item.screenshot.needs_review,
                topLabel,
                prefs.needsReviewToUncategorized,
            )
            if (album == null) { // needs-review screenshot the user chose to skip
                skipped++
                continue
            }
            val relativePath = "${android.os.Environment.DIRECTORY_PICTURES}/$root/$album/"
            val displayName = "sc_${item.screenshot.id}.png"

            if (existsIn(relativePath, displayName)) {
                skipped++
                continue
            }
            val source = Uri.parse(item.screenshot.file_path)
            val dest = copyOne(source, relativePath, displayName)
            if (dest == null) {
                failed++
                continue
            }
            copied++
            if (wantMove) {
                val (name, path) = queryNameAndPath(source) ?: (displayName to "${android.os.Environment.DIRECTORY_PICTURES}/")
                pending += PendingMove(item.screenshot.id, source, dest, name, path)
            }
        }
        Result(copied, skipped, failed, pending)
    }

    /**
     * Called after the user approves the delete request for [moves]. Repoints each
     * screenshot at its album copy (so its thumbnail still loads once the original is
     * gone) and records an undo entry. The actual deletion of the originals is done by
     * the system delete request the caller launched; this only records the aftermath.
     */
    suspend fun commitMoves(moves: List<PendingMove>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        for (m in moves) {
            dao.updateFilePath(m.screenshotId, m.albumUri.toString())
            dao.insertReorgMove(
                ReorgMoveEntity(
                    screenshot_id = m.screenshotId,
                    album_uri = m.albumUri.toString(),
                    original_display_name = m.originalDisplayName,
                    original_relative_path = m.originalRelativePath,
                    moved_at = now,
                ),
            )
        }
    }

    data class UndoResult(val restored: Int, val failed: Int)

    /**
     * Undoes recorded moves: re-creates each original from its album copy at its old
     * location, repoints the screenshot back at the restored file, deletes the album
     * copy, and clears the log entry. Best-effort per entry.
     */
    suspend fun undoMoves(): UndoResult = withContext(Dispatchers.IO) {
        var restored = 0
        var failed = 0
        for (move in dao.allReorgMoves()) {
            val albumUri = Uri.parse(move.album_uri)
            val restoredUri = copyOne(albumUri, move.original_relative_path, move.original_display_name)
            if (restoredUri == null) {
                failed++
                continue
            }
            dao.updateFilePath(move.screenshot_id, restoredUri.toString())
            runCatching { context.contentResolver.delete(albumUri, null, null) }
            dao.deleteReorgMove(move.id)
            restored++
        }
        UndoResult(restored, failed)
    }

    private fun queryNameAndPath(uri: Uri): Pair<String, String>? {
        val projection = arrayOf(
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0) ?: return null
                val path = c.getString(1) ?: "${android.os.Environment.DIRECTORY_PICTURES}/"
                return name to path
            }
        }
        return null
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

    /** Copies [source] into [relativePath]/[displayName]; returns the new uri or null. */
    private fun copyOne(source: Uri, relativePath: String, displayName: String): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val dest = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openInputStream(source).use { input ->
                if (input == null) {
                    resolver.delete(dest, null, null)
                    return null
                }
                resolver.openOutputStream(dest).use { out ->
                    if (out == null) {
                        resolver.delete(dest, null, null)
                        return null
                    }
                    input.copyTo(out)
                }
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(dest, values, null, null)
            dest
        } catch (e: Exception) {
            runCatching { resolver.delete(dest, null, null) }
            null
        }
    }
}
