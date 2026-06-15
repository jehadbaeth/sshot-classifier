package com.okapiorbits.sshotclassifier.pipeline

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipEncoder
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipLabels
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipModelManager
import com.okapiorbits.sshotclassifier.pipeline.clip.TagFuser
import com.okapiorbits.sshotclassifier.pipeline.clip.ZeroShotClassifier
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Offline accuracy measurement against labeled datasets. NOT a pass/fail unit test:
 * it runs the EXACT production classification path (OCR + heuristics + CLIP zero-shot
 * + TagFuser.fuse + TagFuser.decide, the same calls ImageProcessor makes) over a tree
 * of labeled images and writes a confusion matrix, per-class precision/recall, accuracy,
 * and the "other"/needs-review rates so we can answer whether residual misses are a
 * taxonomy gap or the CLIP ceiling.
 *
 * Setup (images are NOT bundled in the APK; pushed to the app's external files dir):
 *   adb shell mkdir -p /sdcard/Android/data/com.okapiorbits.sshotclassifier/files/eval
 *   adb push <dataset>/. /sdcard/Android/data/com.okapiorbits.sshotclassifier/files/eval/
 * where <dataset> has one subdirectory per ground-truth class, named by the slugs in
 * [slugToTag] (e.g. images under eval/email/ and eval/social_media/).
 *
 * Requires both int8 CLIP models installed in the app (see SemanticSearchInstrumentedTest);
 * skipped via assumeTrue if absent or if no eval images are present.
 *
 * Results are written to the app's external files dir and pulled with:
 *   adb pull /sdcard/Android/data/com.okapiorbits.sshotclassifier/files/eval-results.csv
 *   adb pull /sdcard/Android/data/com.okapiorbits.sshotclassifier/files/eval-summary.txt
 */
@RunWith(AndroidJUnit4::class)
class ClassificationEvalTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** Filesystem-safe folder name -> canonical user-facing tag from labels.json. */
    private val slugToTag = mapOf(
        "social_media" to "social media",
        "receipt" to "receipt",
        "map" to "map",
        "code" to "code editor",
        "chat" to "chat / messaging",
        "document" to "document",
        "browser" to "browser / web",
        "game" to "game",
        "shopping" to "shopping",
        "news" to "news",
        "video" to "video / streaming",
        "error" to "error / crash",
        "calendar" to "calendar",
        "finance" to "finance",
        "other" to "other",
        "email" to "email",
    )

    private data class Row(
        val file: String,
        val truth: String,
        val predicted: String,
        /** CLIP-only argmax (no OCR/fusion) — lets us see when the fix flipped the call. */
        val clipTop: String,
        val needsReview: Boolean,
        val topTags: String,
    )

    @Test
    fun evaluateLabeledDatasets() = runBlocking<Unit> {
        val modelManager = ClipModelManager(ctx)
        assumeTrue("CLIP image model not installed; push it first", modelManager.isModelInstalled())

        // Prefer internal filesDir (adb-pushed with the models, reliably readable by
        // the app under FUSE); fall back to the external files dir.
        val internal = File(ctx.filesDir, "eval")
        val evalRoot = if (internal.isDirectory) internal else File(ctx.getExternalFilesDir(null), "eval")
        assumeTrue("No eval/ dataset found at ${evalRoot.absolutePath}", evalRoot.isDirectory)
        val classDirs = evalRoot.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: emptyList()
        assumeTrue("eval/ has no class subdirectories", classDirs.isNotEmpty())

        val encoder = ClipEncoder(ctx, modelManager)
        val ocr = OcrExtractor(ctx)
        val heuristics = OcrHeuristics()
        val zeroShot = ZeroShotClassifier(ClipLabels(ctx))
        val fuser = TagFuser()

        val rows = mutableListOf<Row>()
        for (dir in classDirs) {
            val truth = slugToTag[dir.name] ?: dir.name
            val images = dir.listFiles { f -> f.isImage() }?.sortedBy { it.name } ?: continue
            for (img in images) {
                val uri = Uri.fromFile(img)
                val ocrText = ocr.extract(uri)?.text ?: ""
                val ocrCandidates = heuristics.classify(ocrText)
                val embedding = encoder.encode(uri)
                if (embedding == null) {
                    rows += Row(img.name, truth, ENCODE_FAILED, ENCODE_FAILED, false, "")
                    continue
                }
                val clipScores = zeroShot.classify(embedding)
                val clipTop = clipScores.maxByOrNull { it.value }?.key ?: NONE
                val decision = fuser.decide(fuser.fuse(clipScores, ocrCandidates))
                val predicted = decision.primary ?: NONE
                val topTags = decision.tags.joinToString("; ") { "%s %.2f".format(it.label, it.weight) }
                rows += Row(img.name, truth, predicted, clipTop, decision.needsReview, topTags)
                android.util.Log.i(
                    TAG,
                    "${dir.name}/${img.name} truth=$truth clipOnly=$clipTop pred=$predicted " +
                        "review=${decision.needsReview} [$topTags]",
                )
            }
        }
        assumeTrue("No images found under eval subdirectories", rows.isNotEmpty())

        writeCsv(rows)
        val summary = buildSummary(rows)
        File(ctx.getExternalFilesDir(null), SUMMARY_NAME).writeText(summary)
        android.util.Log.i(TAG, "\n$summary")
    }

    private fun writeCsv(rows: List<Row>) {
        val sb = StringBuilder("file,truth,predicted,clip_only,needs_review,top_tags\n")
        for (r in rows) {
            sb.append("\"${r.file}\",\"${r.truth}\",\"${r.predicted}\",\"${r.clipTop}\",${r.needsReview},\"${r.topTags}\"\n")
        }
        File(ctx.getExternalFilesDir(null), CSV_NAME).writeText(sb.toString())
    }

    private fun buildSummary(rows: List<Row>): String {
        val sb = StringBuilder()
        val total = rows.size
        val correct = rows.count { it.predicted == it.truth }
        val otherCount = rows.count { it.predicted == "other" }
        val reviewCount = rows.count { it.needsReview }
        sb.append("Classification eval — $total images\n")
        sb.append("Overall accuracy: $correct/$total = ${pct(correct, total)}\n")
        sb.append("Predicted \"other\": $otherCount/$total = ${pct(otherCount, total)}\n")
        sb.append("Flagged needs-review: $reviewCount/$total = ${pct(reviewCount, total)}\n\n")

        // Fix impact: where OCR+fusion changed the call vs CLIP alone, and whether that
        // helped (matched truth) or hurt. This is what isolates the OCR-rule fix from
        // CLIP getting it right on its own.
        val flipped = rows.filter { it.clipTop != it.predicted }
        val flipToTruth = flipped.count { it.predicted == it.truth }
        val flipFromTruth = flipped.count { it.clipTop == it.truth }
        val clipAcc = rows.count { it.clipTop == it.truth }
        sb.append("CLIP-only accuracy: $clipAcc/$total = ${pct(clipAcc, total)} (vs fused above)\n")
        sb.append("Fusion changed the call on ${flipped.size}/$total; helped $flipToTruth, hurt $flipFromTruth\n")
        for (r in flipped) {
            val mark = when { r.predicted == r.truth -> "FIX"; r.clipTop == r.truth -> "REGRESS"; else -> "neutral" }
            sb.append("  [$mark] ${r.file}: clip=${r.clipTop} -> fused=${r.predicted} (truth=${r.truth})\n")
        }
        sb.append("\n")

        val classes = rows.map { it.truth }.toSortedSet()
        sb.append("Per-class (truth): support  recall  | most-common prediction\n")
        for (c in classes) {
            val ofClass = rows.filter { it.truth == c }
            val tp = ofClass.count { it.predicted == c }
            val topPred = ofClass.groupingBy { it.predicted }.eachCount().maxByOrNull { it.value }
            sb.append(
                "  %-18s %4d   %6s | %s (%d)\n".format(
                    c, ofClass.size, pct(tp, ofClass.size), topPred?.key ?: "-", topPred?.value ?: 0,
                ),
            )
        }
        sb.append("\nPer-predicted precision:\n")
        for (p in rows.map { it.predicted }.toSortedSet()) {
            val ofPred = rows.filter { it.predicted == p }
            val tp = ofPred.count { it.truth == p }
            sb.append("  %-18s %4d   %6s\n".format(p, ofPred.size, pct(tp, ofPred.size)))
        }

        sb.append("\nConfusion (truth -> predicted, counts):\n")
        for (c in classes) {
            val ofClass = rows.filter { it.truth == c }
            val byPred = ofClass.groupingBy { it.predicted }.eachCount().entries.sortedByDescending { it.value }
            sb.append("  %-18s -> %s\n".format(c, byPred.joinToString(", ") { "${it.key}:${it.value}" }))
        }
        return sb.toString()
    }

    private fun pct(n: Int, d: Int): String = if (d == 0) "n/a" else "%.0f%%".format(100.0 * n / d)

    private fun File.isImage(): Boolean {
        val n = name.lowercase()
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp")
    }

    companion object {
        private const val TAG = "ClassificationEval"
        private const val CSV_NAME = "eval-results.csv"
        private const val SUMMARY_NAME = "eval-summary.txt"
        private const val ENCODE_FAILED = "(encode-failed)"
        private const val NONE = "(none)"
    }
}
