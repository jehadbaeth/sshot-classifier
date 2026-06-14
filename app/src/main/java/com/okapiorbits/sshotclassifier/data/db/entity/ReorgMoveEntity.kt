package com.okapiorbits.sshotclassifier.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One record per file moved (copied into an album, then original deleted) during a
 * reorganization run. It is the undo log: it holds where the album copy now lives
 * (so we can read its bytes and delete it) and where the original was (so we can
 * restore it). Cleared when the user undoes or starts a fresh move run.
 */
@Entity(tableName = "reorg_moves")
data class ReorgMoveEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The screenshot whose file was moved, so undo can repoint its file_path. */
    val screenshot_id: Long,
    /** content:// uri of the album copy the move created. */
    val album_uri: String,
    /** Original display name, to restore the file under its old name. */
    val original_display_name: String,
    /** Original RELATIVE_PATH (e.g. "Pictures/Screenshots/"), to restore the location. */
    val original_relative_path: String,
    val moved_at: Long,
)
