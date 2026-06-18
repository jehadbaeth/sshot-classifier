package com.okapiorbits.sshotclassifier.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * Full-text search index over OCR text. Standalone FTS4 table (not external
 * content) kept in sync manually when OCR completes. The implicit FTS rowid is
 * set to the screenshot id so results join straight back to `screenshots`.
 *
 * Room supports FTS3/FTS4 natively, not FTS5. FTS4 is chosen for reliability on
 * minSdk 26; BM25 ranking (FTS5 only) can come later if search quality needs it.
 * For now results are ranked by recency.
 *
 * Tokenizer is `unicode61` (not the default `simple`) so non-Latin scripts — notably the
 * Arabic OCR text — tokenize and case-fold correctly, with Latin handled at least as well.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "ocr_fts")
data class OcrFtsEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowid: Long,
    @ColumnInfo(name = "text") val text: String,
)
