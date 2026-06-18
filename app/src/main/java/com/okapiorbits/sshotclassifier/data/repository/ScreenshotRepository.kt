package com.okapiorbits.sshotclassifier.data.repository

import android.content.ContentUris
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
import com.okapiorbits.sshotclassifier.data.db.ScreenshotWithTags
import com.okapiorbits.sshotclassifier.data.db.TagCount
import com.okapiorbits.sshotclassifier.data.db.entity.CustomCategoryEntity
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.data.db.entity.SourceType
import com.okapiorbits.sshotclassifier.data.db.entity.TagEntity
import com.okapiorbits.sshotclassifier.data.db.entity.TagSource
import com.okapiorbits.sshotclassifier.data.media.ImageHasher
import com.okapiorbits.sshotclassifier.data.media.MediaStoreScanner
import com.okapiorbits.sshotclassifier.data.media.WatchableFolder
import com.okapiorbits.sshotclassifier.data.network.LinkPreview
import com.okapiorbits.sshotclassifier.data.network.LinkPreviewResolver
import com.okapiorbits.sshotclassifier.data.network.NetworkChecker
import com.okapiorbits.sshotclassifier.data.prefs.CapturePreferencesStore
import com.okapiorbits.sshotclassifier.data.prefs.ResolveTrigger
import com.okapiorbits.sshotclassifier.data.prefs.WatchedFoldersStore
import com.okapiorbits.sshotclassifier.pipeline.clip.CustomCategoryScorer
import com.okapiorbits.sshotclassifier.pipeline.clip.DuplicateFinder
import com.okapiorbits.sshotclassifier.pipeline.clip.EmbeddingCodec
import com.okapiorbits.sshotclassifier.pipeline.clip.LabelEmbedder
import com.okapiorbits.sshotclassifier.pipeline.clip.SemanticSearcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenshotRepository @Inject constructor(
    private val dao: ScreenshotDao,
    private val scanner: MediaStoreScanner,
    private val hasher: ImageHasher,
    private val semanticSearcher: SemanticSearcher,
    private val categoryEmbedder: LabelEmbedder,
    private val watchedFoldersStore: WatchedFoldersStore,
    private val capturePreferencesStore: CapturePreferencesStore,
    private val linkPreviewResolver: LinkPreviewResolver,
    private val networkChecker: NetworkChecker,
) {
    /** Folders the user is watching (reactive), and all folders available to pick. */
    val watchedFolders: Flow<Set<String>> = watchedFoldersStore.folders

    suspend fun availableFolders(): List<WatchableFolder> =
        withContext(Dispatchers.IO) { scanner.availableFolders() }

    suspend fun setFolderWatched(folder: String, watched: Boolean) =
        watchedFoldersStore.setWatched(folder, watched)

    fun observeGallery(): Flow<List<ScreenshotWithTags>> = dao.observeAllWithTags()

    fun observeByTag(label: String): Flow<List<ScreenshotWithTags>> = dao.observeByTag(label)

    fun observeCount(): Flow<Int> = dao.observeCount()

    /** Number of in-app camera captures (drives whether the gallery shows a source filter). */
    fun observeCaptureCount(): Flow<Int> = dao.observeSourceCount(SourceType.CAMERA.name)

    /**
     * Near-duplicate groups by CLIP embedding similarity (see [DuplicateFinder]). Returns
     * groups of 2+ screenshot ids; empty if the visual model never ran (no embeddings).
     * Computed on demand from the stored embeddings, not cached, since it is a user action.
     */
    suspend fun findDuplicateGroups(
        threshold: Float = DuplicateFinder.DEFAULT_THRESHOLD,
    ): List<List<Long>> {
        val items = dao.allEmbeddings().map { it.screenshot_id to EmbeddingCodec.toFloats(it.vector) }
        return DuplicateFinder.groups(items, threshold)
    }

    fun observePendingCount(): Flow<Int> = dao.observeStatusCount()

    fun observeTagCounts(): Flow<List<TagCount>> = dao.observeTagCounts()

    /** Count of DONE screenshots lacking a CLIP image embedding (reprocess candidates). */
    fun observeReprocessableCount(): Flow<Int> = dao.observeReprocessableCount()

    /** Count of recorded reorganization moves available to undo. */
    fun observeReorgMoveCount(): Flow<Int> = dao.observeReorgMoveCount()

    /** Screenshots whose auto-tagging was uncertain and wants a human look. */
    fun observeNeedsReview(): Flow<List<ScreenshotWithTags>> = dao.observeNeedsReview()
    fun observeNeedsReviewCount(): Flow<Int> = dao.observeNeedsReviewCount()

    /** Resets DONE-but-unembedded screenshots to PENDING. Returns how many. */
    suspend fun markForReprocessing(): Int = dao.markMissingEmbeddingsForReprocessing()
    suspend fun markAllForReprocessing(): Int = dao.markAllForReprocessing()

    suspend fun pendingScreenshots(): List<ScreenshotEntity> = dao.pending()

    /** Reactive screenshot + its tags for the detail editor. */
    fun observeScreenshot(id: Long): Flow<ScreenshotEntity?> = dao.observeScreenshot(id)
    fun observeTags(screenshotId: Long): Flow<List<TagEntity>> = dao.observeTagsFor(screenshotId)
    fun observeOcrText(screenshotId: Long): Flow<String?> = dao.observeOcrText(screenshotId)
    suspend fun embeddingFor(screenshotId: Long): ByteArray? = dao.embeddingFor(screenshotId)

    /**
     * Adds a user-authored tag. Labels are trimmed and lowercased so they match
     * the auto-tag namespace and the search chips. No-ops (returns false) on a
     * blank label or an exact duplicate already present on the screenshot.
     */
    suspend fun addUserTag(screenshotId: Long, rawLabel: String): Boolean {
        val label = rawLabel.trim().lowercase()
        if (label.isEmpty()) return false
        if (dao.tagExists(screenshotId, label)) return false
        dao.insertTag(
            TagEntity(
                screenshot_id = screenshotId,
                label = label,
                weight = 1f,
                source = TagSource.USER.name,
            )
        )
        dao.updateNeedsReview(screenshotId, false) // a human edited it -> reviewed
        return true
    }

    /** Removes any single tag (user-added or a wrong auto tag); editing counts as reviewed. */
    suspend fun removeTag(tagId: Long, screenshotId: Long) {
        dao.deleteTag(tagId)
        dao.updateNeedsReview(screenshotId, false)
    }

    /** Undo for a bulk tag-add: removes the USER tag [label] from the given screenshots. */
    suspend fun removeUserTagFromAll(ids: Set<Long>, label: String) {
        if (ids.isNotEmpty()) dao.deleteUserTagFromAll(ids.toList(), label)
    }

    // ---- User-defined auto-tag categories ----

    fun observeCategories(): Flow<List<CustomCategoryEntity>> = dao.observeCategories()

    /** Whether categories can be created (needs the text model to embed labels). */
    fun canAddCategories(): Boolean = categoryEmbedder.isReady()

    sealed interface AddCategoryResult {
        /** Created and applied to [matched] existing screenshots. */
        data class Added(val matched: Int) : AddCategoryResult
        data object Blank : AddCategoryResult
        data object Duplicate : AddCategoryResult
        data object ModelMissing : AddCategoryResult
        data object EncodeFailed : AddCategoryResult
    }

    /**
     * Creates a custom auto-category: embeds the label on-device (prompt-ensembled),
     * stores it, then immediately scores it against every stored image embedding so
     * already-indexed screenshots get tagged right away. New screenshots are scored
     * later by [com.okapiorbits.sshotclassifier.pipeline.ImageProcessor].
     */
    suspend fun addCustomCategory(rawLabel: String): AddCategoryResult {
        val label = rawLabel.trim().lowercase()
        if (label.isEmpty()) return AddCategoryResult.Blank
        if (!categoryEmbedder.isReady()) return AddCategoryResult.ModelMissing
        if (dao.categoryExists(label)) return AddCategoryResult.Duplicate
        val embedding = categoryEmbedder.embed(label) ?: return AddCategoryResult.EncodeFailed

        dao.insertCategory(CustomCategoryEntity(label = label, embedding = EmbeddingCodec.toBytes(embedding)))

        val cat = listOf(CustomCategoryScorer.Category(label, embedding))
        var matched = 0
        for (row in dao.allEmbeddings()) {
            val hits = CustomCategoryScorer.score(EmbeddingCodec.toFloats(row.vector), cat)
            if (hits.isEmpty()) continue
            if (dao.tagExists(row.screenshot_id, label)) continue // don't duplicate an existing tag
            dao.insertTag(
                TagEntity(
                    screenshot_id = row.screenshot_id,
                    label = label,
                    weight = hits.first().weight,
                    source = TagSource.CUSTOM.name,
                )
            )
            matched++
        }
        return AddCategoryResult.Added(matched)
    }

    // ---- Backup: export / import of user-meaningful tag data ----

    data class ImportResult(val userTagsApplied: Int, val categoriesAdded: Int, val skipped: Int)

    /**
     * Serializes the user-meaningful, non-re-derivable data to JSON: manual (USER) tags keyed
     * by image content hash, plus custom category labels. Auto tags re-derive on a rescan, so
     * they are intentionally excluded. The hash (not the unstable row id) lets tags re-attach
     * after a reinstall or a destructive DB recreate.
     */
    suspend fun exportTagsJson(): String {
        val tags = JSONArray()
        for (t in dao.userTagsForExport()) {
            tags.put(JSONObject().put("hash", t.fileHash).put("label", t.label))
        }
        val cats = JSONArray()
        for (c in dao.allCategories()) cats.put(c.label)
        return JSONObject()
            .put("version", BACKUP_VERSION)
            .put("userTags", tags)
            .put("customCategories", cats)
            .toString(2)
    }

    /**
     * Applies a backup produced by [exportTagsJson]. User tags re-attach to images present on
     * this device (matched by hash; missing images and existing duplicates are skipped). Custom
     * categories are re-created via [addCustomCategory] (re-embedded on-device; needs the text
     * model, else skipped). Never throws on bad input — returns a best-effort [ImportResult].
     */
    suspend fun importTagsJson(json: String): ImportResult {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return ImportResult(0, 0, 0)
        var applied = 0
        var skipped = 0
        val tags = root.optJSONArray("userTags") ?: JSONArray()
        for (i in 0 until tags.length()) {
            val o = tags.optJSONObject(i) ?: continue
            val hash = o.optString("hash").takeIf { it.isNotBlank() } ?: continue
            val label = o.optString("label").trim().lowercase().takeIf { it.isNotBlank() } ?: continue
            val id = dao.idByHash(hash)
            if (id == null || dao.tagExists(id, label)) { skipped++; continue }
            dao.insertTag(TagEntity(screenshot_id = id, label = label, weight = 1f, source = TagSource.USER.name))
            dao.updateNeedsReview(id, false)
            applied++
        }
        var catsAdded = 0
        val cats = root.optJSONArray("customCategories") ?: JSONArray()
        for (i in 0 until cats.length()) {
            val label = cats.optString(i).takeIf { it.isNotBlank() } ?: continue
            if (addCustomCategory(label) is AddCategoryResult.Added) catsAdded++ else skipped++
        }
        return ImportResult(applied, catsAdded, skipped)
    }

    /** Deletes a custom category and removes the auto tags it produced. */
    suspend fun removeCustomCategory(id: Long, label: String) {
        dao.deleteCategory(id)
        dao.deleteTagsByLabelAndSource(label, TagSource.CUSTOM.name)
    }

    /** Full-text OCR search. Empty/blank query returns nothing. */
    fun search(query: String): Flow<List<ScreenshotWithTags>> {
        val fts = SearchFusion.toFtsPrefixQuery(query) ?: return flowOf(emptyList())
        return dao.searchByText(fts)
    }

    /**
     * Hybrid search: free-text visual search (CLIP text encoder -> cosine over
     * stored image embeddings) merged with OCR full-text matches via reciprocal
     * rank fusion. RRF avoids comparing the two incompatible score scales (CLIP
     * cosine ~0.2-0.35 vs a binary FTS hit) by fusing ranks, not raw scores.
     *
     * Degrades gracefully: if the text model is absent, visual ranking is empty
     * and this returns pure OCR results; if there are no OCR matches, it returns
     * pure visual results. Empty/blank query returns nothing.
     */
    suspend fun hybridSearch(query: String, limit: Int = 100): List<ScreenshotWithTags> {
        if (query.isBlank()) return emptyList()

        val visualRanking = semanticSearcher.search(query, limit).map { it.screenshotId }
        val textRanking = SearchFusion.toFtsPrefixQuery(query)
            ?.let { dao.searchIdsByText(it) }
            ?: emptyList()

        val fused = SearchFusion.reciprocalRankFusion(listOf(visualRanking, textRanking))
            .take(limit)
        if (fused.isEmpty()) return emptyList()

        val byId = dao.screenshotsByIds(fused).associateBy { it.screenshot.id }
        return SearchFusion.reorderTo(fused, byId)
    }

    // ---- QR link-preview resolution (Phase B; the only network-touching capture path) ----

    sealed interface ResolveResult {
        data class Resolved(val preview: LinkPreview) : ResolveResult
        /** Master switch off. */
        data object Disabled : ResolveResult
        data object NotUrl : ResolveResult
        /** No usable network under the Wi-Fi-only preference. */
        data object NoNetwork : ResolveResult
        /** Fetch failed or the page had no usable metadata. */
        data object Failed : ResolveResult
        /** The capture row no longer exists. */
        data object Gone : ResolveResult
    }

    /** Whether a stored payload is an http(s) URL (recomputed from the raw payload). */
    private fun isHttpUrl(payload: String?): Boolean =
        payload != null && (payload.startsWith("http://", true) || payload.startsWith("https://", true))

    /**
     * Resolves a capture's QR URL into a stored [LinkPreview], honoring the user's
     * preferences and connectivity via the shared [ResolvePolicy]. Used by both the manual
     * "Resolve link" button and (for the automatic trigger) the processing worker. Never
     * throws: failures map to [ResolveResult.Failed].
     */
    suspend fun resolveQrLink(screenshotId: Long): ResolveResult {
        val row = dao.getById(screenshotId) ?: return ResolveResult.Gone
        val payload = row.qr_payload
        val prefs = capturePreferencesStore.current()
        val decision = ResolvePolicy.decide(prefs, isHttpUrl(payload), networkChecker.current())
        when (decision) {
            ResolvePolicy.Decision.Disabled -> return ResolveResult.Disabled
            ResolvePolicy.Decision.NotUrl -> return ResolveResult.NotUrl
            ResolvePolicy.Decision.NoNetwork -> return ResolveResult.NoNetwork
            ResolvePolicy.Decision.Resolve -> Unit
        }
        val preview = linkPreviewResolver.resolve(payload!!) ?: return ResolveResult.Failed
        dao.updateLinkPreview(
            id = screenshotId,
            title = preview.title,
            description = preview.description,
            imageUrl = preview.imageUrl,
            resolvedAt = System.currentTimeMillis(),
        )
        return ResolveResult.Resolved(preview)
    }

    /**
     * Worker hook: resolve a freshly processed capture's QR link only when the user chose
     * the AUTOMATIC trigger. No-ops (returns false) otherwise or on any non-success, so a
     * resolution problem never disrupts the processing batch.
     */
    suspend fun maybeAutoResolve(screenshotId: Long): Boolean {
        if (capturePreferencesStore.current().resolveTrigger != ResolveTrigger.AUTOMATIC) return false
        return resolveQrLink(screenshotId) is ResolveResult.Resolved
    }

    /**
     * Indexes an in-app camera capture that has already been written to MediaStore.
     * Inserts it directly (bypassing the watched-folder scan) as a CAMERA-source row in
     * PENDING state; the processing worker then runs it through the full pipeline (OCR,
     * QR decode, CLIP, description). Returns the new row id, or null if it was a
     * duplicate (same content hash) or could not be read.
     */
    suspend fun indexCapture(uri: Uri): Long? {
        val hash = hasher.sha256(uri) ?: return null
        if (dao.existsByHash(hash)) return null
        val (w, h) = hasher.dimensions(uri) ?: (0 to 0)
        val mediaId = try { ContentUris.parseId(uri) } catch (e: Exception) { -1L }
        val rowId = dao.insert(
            ScreenshotEntity(
                file_path = uri.toString(),
                file_hash = hash,
                media_store_id = mediaId,
                date_added = System.currentTimeMillis(),
                date_processed = null,
                width = w,
                height = h,
                source_type = SourceType.CAMERA.name,
            )
        )
        return if (rowId != -1L) rowId else null
    }

    /**
     * Discovers screenshots from MediaStore and inserts any not yet known (by hash).
     * Returns the number of newly indexed screenshots. ML processing happens later
     * in the pipeline; this phase only records existence.
     */
    suspend fun syncFromMediaStore(): Int {
        var inserted = 0
        val folders = watchedFoldersStore.current()
        for (found in scanner.queryScreenshots(folders)) {
            val hash = hasher.sha256(found.uri) ?: continue
            if (dao.existsByHash(hash)) continue
            val rowId = dao.insert(
                ScreenshotEntity(
                    file_path = found.uri.toString(),
                    file_hash = hash,
                    media_store_id = found.mediaStoreId,
                    date_added = found.dateAdded,
                    date_processed = null,
                    width = found.width,
                    height = found.height,
                )
            )
            if (rowId != -1L) inserted++
        }
        return inserted
    }

    private companion object {
        const val BACKUP_VERSION = 1
    }
}
