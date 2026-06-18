package com.okapiorbits.sshotclassifier.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.okapiorbits.sshotclassifier.data.db.entity.ProcessingStatus
import com.okapiorbits.sshotclassifier.ui.common.EmptyState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.okapiorbits.sshotclassifier.data.db.ScreenshotWithTags
import com.okapiorbits.sshotclassifier.ui.detail.ScreenshotDetailScreen
import com.okapiorbits.sshotclassifier.ui.detail.ScreenshotDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(viewModel: GalleryViewModel, onOpenCamera: () -> Unit = {}) {
    val screenshots by viewModel.screenshots.collectAsStateWithLifecycle()
    val pending by viewModel.pendingCount.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val reprocessable by viewModel.reprocessableCount.collectAsStateWithLifecycle()
    val needsReview by viewModel.needsReviewCount.collectAsStateWithLifecycle()
    val reviewOnly by viewModel.reviewOnly.collectAsStateWithLifecycle()
    val captureCount by viewModel.captureCount.collectAsStateWithLifecycle()
    val sourceFilter by viewModel.sourceFilter.collectAsStateWithLifecycle()
    val duplicatesOnly by viewModel.duplicatesOnly.collectAsStateWithLifecycle()
    val duplicateGroupCount by viewModel.duplicateGroupCount.collectAsStateWithLifecycle()
    val processing by viewModel.processing.collectAsStateWithLifecycle()

    // In-tab navigation to a screenshot's tag editor; no NavHost needed.
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = selectedId?.let { id -> screenshots.find { it.screenshot.id == id } }
    BackHandler(enabled = selectedId != null) { selectedId = null }
    if (selectedId != null && selected == null) selectedId = null // row vanished
    if (selected != null) {
        val detailVm: ScreenshotDetailViewModel = hiltViewModel()
        ScreenshotDetailScreen(
            screenshotId = selected.screenshot.id,
            filePath = selected.screenshot.file_path,
            viewModel = detailVm,
            onBack = { selectedId = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val suffix = if (pending > 0) " · $pending pending" else ""
                    Text("Screenshots (${screenshots.size})$suffix")
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FloatingActionButton(onClick = onOpenCamera) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "Capture a photo")
                }
                ExtendedFloatingActionButton(
                    onClick = { viewModel.scan() },
                    icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                    text = { Text(if (pending > 0) "Processing…" else "Scan") },
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ProcessingBar(processing)
            ModelBanner(modelState, onDownload = viewModel::downloadModel)
            if (modelState is ModelState.Installed && reprocessable > 0 && pending == 0) {
                ReprocessBanner(reprocessable, onReprocess = viewModel::reprocessMissing)
            }
            if (needsReview > 0 || reviewOnly) {
                FilterChip(
                    selected = reviewOnly,
                    onClick = { viewModel.toggleReviewOnly() },
                    label = { Text(if (reviewOnly) "Showing needs review · tap to clear" else "Needs review ($needsReview)") },
                    leadingIcon = { Icon(Icons.Default.Flag, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            // Once there are captures, let the user browse screenshots vs photos separately.
            if (captureCount > 0) {
                val (screenshotSource, cameraSource) = viewModel.sourceTypes
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    FilterChip(
                        selected = sourceFilter == null,
                        onClick = { viewModel.setSourceFilter(null) },
                        label = { Text("All") },
                    )
                    FilterChip(
                        selected = sourceFilter == screenshotSource,
                        onClick = { viewModel.setSourceFilter(screenshotSource) },
                        label = { Text("Screenshots") },
                    )
                    FilterChip(
                        selected = sourceFilter == cameraSource,
                        onClick = { viewModel.setSourceFilter(cameraSource) },
                        label = { Text("Photos") },
                    )
                }
            }
            // Near-duplicate review (needs visual embeddings, i.e. the image model).
            if (modelState is ModelState.Installed) {
                FilterChip(
                    selected = duplicatesOnly,
                    onClick = { viewModel.toggleDuplicatesOnly() },
                    label = {
                        Text(
                            when {
                                duplicatesOnly && duplicateGroupCount > 0 ->
                                    "Duplicates: $duplicateGroupCount group${if (duplicateGroupCount == 1) "" else "s"} · tap to clear"
                                duplicatesOnly -> "No near-duplicates found · tap to clear"
                                else -> "Find near-duplicates"
                            }
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        Box(modifier = Modifier.fillMaxSize()) {
            if (screenshots.isEmpty()) {
                EmptyState()
            } else {
                val now = remember { System.currentTimeMillis() }
                // Group into date buckets only on the default (recency) view; filtered/duplicate
                // views aren't time-ordered, so show them as a single flat section.
                val grouped = remember(screenshots, now, duplicatesOnly) {
                    if (duplicatesOnly) listOf("" to screenshots)
                    else groupByDateBucket(screenshots, now)
                }
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Adaptive(minSize = 120.dp),
                    contentPadding = PaddingValues(8.dp),
                    verticalItemSpacing = 6.dp,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    grouped.forEach { (label, list) ->
                        if (label.isNotEmpty()) {
                            item(span = StaggeredGridItemSpan.FullLine, key = "hdr_$label") {
                                SectionHeader(label)
                            }
                        }
                        items(list, key = { it.screenshot.id }) { item ->
                            GalleryCell(item, onClick = { selectedId = item.screenshot.id })
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun ProcessingBar(state: ProcessingState) {
    AnimatedVisibility(visible = state.active) {
        val animated by animateFloatAsState(targetValue = state.fraction, label = "processingProgress")
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Processing images…", style = MaterialTheme.typography.labelLarge)
                if (state.total > 0) {
                    Text(
                        "${state.done} / ${state.total}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun ModelBanner(state: ModelState, onDownload: () -> Unit) {
    when (state) {
        is ModelState.Installed -> Unit
        is ModelState.Downloading -> Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Downloading AI model… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        else -> Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    if (state is ModelState.Error) "AI model download failed: ${state.message}"
                    else "Visual AI tagging is off. Tags come from text only until the model is installed.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = onDownload, modifier = Modifier.padding(top = 6.dp)) {
                    Text("Install AI model")
                }
            }
        }
    }
}

@Composable
private fun ReprocessBanner(count: Int, onReprocess: () -> Unit) {
    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "$count screenshot${if (count == 1) "" else "s"} tagged before the visual " +
                    "model was installed. Reprocess to add visual tags and search.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onReprocess, modifier = Modifier.padding(top = 6.dp)) {
                Text("Reprocess $count")
            }
        }
    }
}

@Composable
fun GalleryCell(item: ScreenshotWithTags, onClick: () -> Unit = {}) {
    val shape = RoundedCornerShape(12.dp)
    // Show each image at (close to) its real shape for a staggered, photos-app feel; clamp so an
    // extreme panorama or tall screenshot can't produce a giant tile.
    val w = item.screenshot.width
    val h = item.screenshot.height
    val ratio = if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.55f, 1.4f) else 0.62f
    Box(
        modifier = Modifier
            .aspectRatio(ratio)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant) // placeholder while the image loads
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.screenshot.file_path,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        val top = item.tags.maxByOrNull { it.weight }
        if (top != null) {
            // Gradient scrim so the label stays readable over any image.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.55f),
                        )
                    ),
            ) {
                Text(
                    text = top.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        CellStatusBadge(
            status = item.screenshot.status,
            needsReview = item.screenshot.needs_review,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
        )
    }
}

/** Small corner badge: still-processing spinner, queued clock, or a needs-review flag. */
@Composable
private fun CellStatusBadge(status: String, needsReview: Boolean, modifier: Modifier = Modifier) {
    val processing = status == ProcessingStatus.PROCESSING.name
    val pending = status == ProcessingStatus.PENDING.name
    if (!processing && !pending && !needsReview) return
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            processing -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = Color.White,
            )
            pending -> Icon(
                Icons.Default.Schedule, contentDescription = "Queued",
                tint = Color.White, modifier = Modifier.size(13.dp),
            )
            else -> Icon(
                Icons.Default.Flag, contentDescription = "Needs review",
                tint = Color.White, modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * Buckets screenshots into Today / This week / This month / Earlier by date_added, preserving the
 * incoming recency order. MediaStore stores seconds; other rows may store millis, so normalise.
 * Returns ordered (label, items) pairs, empty buckets omitted.
 */
private fun groupByDateBucket(
    items: List<ScreenshotWithTags>,
    now: Long,
): List<Pair<String, List<ScreenshotWithTags>>> {
    val cal = java.util.Calendar.getInstance().apply {
        timeInMillis = now
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }
    val startOfToday = cal.timeInMillis
    val startOfWeek = startOfToday - 6L * 24 * 3600 * 1000
    val startOfMonth = startOfToday - 29L * 24 * 3600 * 1000

    fun bucket(epoch: Long): String {
        val ms = if (epoch < 1_000_000_000_000L) epoch * 1000 else epoch
        return when {
            ms >= startOfToday -> "Today"
            ms >= startOfWeek -> "This week"
            ms >= startOfMonth -> "This month"
            else -> "Earlier"
        }
    }

    val order = listOf("Today", "This week", "This month", "Earlier")
    val byBucket = items.groupBy { bucket(it.screenshot.date_added) }
    return order.mapNotNull { label -> byBucket[label]?.let { label to it } }
}

@Composable
private fun EmptyState() {
    EmptyState(
        icon = Icons.Default.PhotoLibrary,
        title = "No screenshots yet",
        subtitle = "Tap Scan to find and classify screenshots on this device, or use the camera " +
            "button to capture something.",
    )
}
