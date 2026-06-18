package com.okapiorbits.sshotclassifier.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okapiorbits.sshotclassifier.ui.common.EmptyState
import com.okapiorbits.sshotclassifier.ui.detail.ScreenshotDetailScreen
import com.okapiorbits.sshotclassifier.ui.detail.ScreenshotDetailViewModel
import com.okapiorbits.sshotclassifier.ui.gallery.GalleryCell

@Composable
fun SearchScreen(viewModel: SearchViewModel) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val selectedTags by viewModel.selectedTags.collectAsStateWithLifecycle()
    val tagCounts by viewModel.tagCounts.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()

    // In-tab navigation to a result's detail / tag editor (same as the gallery); no NavHost.
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val selected = selectedId?.let { id -> results.find { it.screenshot.id == id } }
    BackHandler(enabled = selectedId != null) { selectedId = null }
    if (selectedId != null && selected == null) selectedId = null // result vanished
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

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = {
                Text(
                    if (viewModel.semanticReady) "Search by text or visual concept"
                    else "Search text in screenshots"
                )
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
        )

        if (tagCounts.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(tagCounts, key = { it.label }) { tc ->
                    FilterChip(
                        selected = tc.label in selectedTags,
                        onClick = { viewModel.toggleTag(tc.label) },
                        label = { Text("${tc.label} (${tc.cnt})") },
                    )
                }
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            when {
                results.isNotEmpty() -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 110.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(results, key = { it.screenshot.id }) { item ->
                            GalleryCell(item, onClick = { selectedId = item.screenshot.id })
                        }
                    }
                }
                query.isBlank() && selectedTags.isEmpty() -> EmptyState(
                    icon = Icons.Default.Search,
                    title = "Search your library",
                    subtitle = if (viewModel.semanticReady)
                        "Find screenshots by the text in them or by what they look like, or pick a tag."
                    else "Type to search the text in your screenshots, or pick a tag.",
                )
                else -> EmptyState(
                    icon = Icons.Default.SearchOff,
                    title = "No matches",
                    subtitle = "Try different words, or clear the tag filter.",
                )
            }
        }
    }
}
