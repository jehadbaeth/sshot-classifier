package com.okapiorbits.sshotclassifier.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.okapiorbits.sshotclassifier.data.db.ScreenshotWithTags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(viewModel: GalleryViewModel) {
    val screenshots by viewModel.screenshots.collectAsStateWithLifecycle()
    val pending by viewModel.pendingCount.collectAsStateWithLifecycle()
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()

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
            ExtendedFloatingActionButton(
                onClick = { viewModel.scan() },
                icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                text = { Text(if (pending > 0) "Processing…" else "Scan") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ModelBanner(modelState, onDownload = viewModel::downloadModel)
        Box(modifier = Modifier.fillMaxSize()) {
            if (screenshots.isEmpty()) {
                EmptyState()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(screenshots, key = { it.screenshot.id }) { item ->
                        GalleryCell(item)
                    }
                }
            }
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
fun GalleryCell(item: ScreenshotWithTags) {
    Box(modifier = Modifier.aspectRatio(0.62f)) {
        AsyncImage(
            model = item.screenshot.file_path,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        val top = item.tags.maxByOrNull { it.weight }
        if (top != null) {
            Text(
                text = top.label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No screenshots indexed yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tap Scan to find and classify screenshots on this device",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
