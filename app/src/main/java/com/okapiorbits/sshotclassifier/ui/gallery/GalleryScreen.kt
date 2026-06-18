package com.okapiorbits.sshotclassifier.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val sortOrder by viewModel.sortOrder.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyStaggeredGridState()
    var showBulkTagDialog by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    // Multi-select takes priority for the back button: exit selection before leaving the screen.
    BackHandler(enabled = selectedIds.isNotEmpty()) { viewModel.clearSelection() }

    // In-tab navigation to a screenshot's tag editor; no NavHost needed.
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = selectedId?.let { id -> screenshots.find { it.screenshot.id == id } }
    BackHandler(enabled = selectedId != null) { selectedId = null }
    if (selectedId != null && selected == null) selectedId = null // row vanished
    if (selected != null) {
        val detailVm: ScreenshotDetailViewModel = hiltViewModel()
        // Swipe left/right to move between screenshots, in the current sort/filter order.
        val startIndex = remember(selectedId) {
            screenshots.indexOfFirst { it.screenshot.id == selectedId }.coerceAtLeast(0)
        }
        val pagerState = rememberPagerState(initialPage = startIndex) { screenshots.size }
        // Subtle scale+fade entrance when opening a screenshot (safe, no shared-element).
        val enter = remember { Animatable(0.94f) }
        val fade = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            launch { enter.animateTo(1f, tween(200)) }
            launch { fade.animateTo(1f, tween(200)) }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = enter.value; scaleY = enter.value; alpha = fade.value
            },
        ) { page ->
            val s = screenshots.getOrNull(page)
            if (s != null) {
                ScreenshotDetailScreen(
                    screenshotId = s.screenshot.id,
                    filePath = s.screenshot.file_path,
                    viewModel = detailVm,
                    onBack = { selectedId = null },
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            if (selectedIds.isNotEmpty()) {
                SelectionTopBar(
                    count = selectedIds.size,
                    onClose = { viewModel.clearSelection() },
                    onSelectAll = { viewModel.selectAll() },
                    onAddTag = { showBulkTagDialog = true },
                    onShare = { shareImages(context, viewModel.selectedUris()) },
                )
            } else {
                TopAppBar(
                    title = {
                        val suffix = if (pending > 0) " · $pending pending" else ""
                        Text("Screenshots (${screenshots.size})$suffix")
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { sortMenuOpen = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(order.label) },
                                        onClick = { viewModel.setSortOrder(order); sortMenuOpen = false },
                                        leadingIcon = {
                                            if (order == sortOrder) Icon(Icons.Default.Check, contentDescription = null)
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
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
                // Date sections only make sense on the default newest-first view; other sorts
                // (oldest, recently tagged) and the duplicates view show a single flat section.
                val grouped = remember(screenshots, now, duplicatesOnly, sortOrder) {
                    if (duplicatesOnly || sortOrder != SortOrder.NEWEST) listOf("" to screenshots)
                    else groupByDateBucket(screenshots, now)
                }
                PullToRefreshBox(
                    isRefreshing = pending > 0,
                    onRefresh = { viewModel.scan() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                LazyVerticalStaggeredGrid(
                    state = gridState,
                    columns = StaggeredGridCells.Adaptive(minSize = 100.dp),
                    contentPadding = PaddingValues(6.dp),
                    verticalItemSpacing = 4.dp,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    grouped.forEach { (label, list) ->
                        if (label.isNotEmpty()) {
                            item(span = StaggeredGridItemSpan.FullLine, key = "hdr_$label") {
                                SectionHeader(label)
                            }
                        }
                        items(list, key = { it.screenshot.id }) { item ->
                            GalleryCell(
                                item = item,
                                selected = item.screenshot.id in selectedIds,
                                onClick = {
                                    if (selectedIds.isNotEmpty()) viewModel.toggleSelected(item.screenshot.id)
                                    else selectedId = item.screenshot.id
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.toggleSelected(item.screenshot.id)
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
                }
                // Jump back to the top once scrolled down a few rows.
                val showTop by remember { derivedStateOf { gridState.firstVisibleItemIndex > 8 } }
                // Bottom-START so it doesn't collide with the camera/scan FABs at bottom-end.
                androidx.compose.animation.AnimatedVisibility(
                    visible = showTop,
                    modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                ) {
                    SmallFloatingActionButton(
                        onClick = { scope.launch { gridState.animateScrollToItem(0) } },
                    ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Scroll to top") }
                }
            }
        }
        }
    }

    if (showBulkTagDialog) {
        BulkTagDialog(
            count = selectedIds.size,
            onConfirm = { label ->
                viewModel.addTagToSelected(label)
                showBulkTagDialog = false
            },
            onDismiss = { showBulkTagDialog = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onAddTag: () -> Unit,
    onShare: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Clear selection") }
        },
        title = { Text("$count selected") },
        actions = {
            IconButton(onClick = onShare) { Icon(Icons.Default.Share, contentDescription = "Share") }
            IconButton(onClick = onAddTag) { Icon(Icons.Default.Label, contentDescription = "Add tag") }
            IconButton(onClick = onSelectAll) { Icon(Icons.Default.SelectAll, contentDescription = "Select all") }
        },
    )
}

@Composable
private fun BulkTagDialog(count: Int, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add tag to $count") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Tag") },
                singleLine = true,
            )
        },
    )
}

private fun shareImages(context: android.content.Context, uris: List<android.net.Uri>) {
    if (uris.isEmpty()) return
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/*"
        putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, ArrayList(uris))
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share ${uris.size} images"))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryCell(
    item: ScreenshotWithTags,
    onClick: () -> Unit = {},
    onLongClick: () -> Unit = {},
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    // Show each image at (close to) its real shape for a staggered, photos-app feel; clamp so an
    // extreme panorama or tall screenshot can't produce a giant tile.
    val w = item.screenshot.width
    val h = item.screenshot.height
    // Clamp tightly so portrait screenshots don't become very tall tiles (keeps the grid dense).
    val ratio = if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.7f, 1.3f) else 0.75f
    Box(
        modifier = modifier
            .aspectRatio(ratio)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant) // placeholder while the image loads
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        AsyncImage(
            model = item.screenshot.file_path,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
            )
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
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
