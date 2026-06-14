package com.okapiorbits.sshotclassifier.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okapiorbits.sshotclassifier.data.db.entity.CustomCategoryEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val total by viewModel.screenshotCount.collectAsStateWithLifecycle()
    val pending by viewModel.pendingCount.collectAsStateWithLifecycle()
    val reprocessable by viewModel.reprocessableCount.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val categoryStatus by viewModel.categoryStatus.collectAsStateWithLifecycle()
    val reorganizeStatus by viewModel.reorganizeStatus.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Section("Library")
            Stat("Screenshots indexed", total.toString())
            if (pending > 0) Stat("Waiting to be processed", pending.toString())
            if (reprocessable > 0 && models.imageInstalled) Stat("Awaiting visual tags", reprocessable.toString())

            Section("AI models")
            ModelRow("Image encoder (tagging + visual search)", models.imageInstalled, models.imageBytes)
            ModelRow("Text encoder (free-text search)", models.textInstalled, models.textBytes)
            when (val d = download) {
                is DownloadState.Running -> {
                    Text(
                        "Downloading… ${(d.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    LinearProgressIndicator(
                        progress = { d.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                is DownloadState.Failed -> Text(
                    "Download failed: ${d.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                DownloadState.Idle -> Unit
            }
            if (!(models.imageInstalled && models.textInstalled) && download !is DownloadState.Running) {
                Button(
                    onClick = viewModel::downloadModels,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Download missing models")
                }
                Text(
                    "About 155 MB total over the network, once. Nothing else leaves the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Maintenance")
            if (reprocessable > 0 && models.imageInstalled) {
                OutlinedButton(onClick = viewModel::reprocessMissing, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Reprocess $reprocessable older screenshots")
                }
            }
            OutlinedButton(onClick = viewModel::scanNow, modifier = Modifier.padding(top = 4.dp)) {
                Text(if (pending > 0) "Processing…" else "Scan for new screenshots")
            }
            if (viewModel.reorganizeSupported) {
                OutlinedButton(
                    onClick = viewModel::reorganize,
                    enabled = reorganizeStatus != SettingsViewModel.RUNNING,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text("Copy into tag albums")
                }
                Text(
                    "Copies each screenshot into Pictures/ScreenshotClassifier/<tag>/ " +
                        "(uncategorized when unsure). Originals are kept; nothing is moved or deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                reorganizeStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Text(
                "A background pass also runs every 6 hours.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Custom categories")
            CategoriesSection(
                categories = categories,
                canAdd = models.textInstalled,
                status = categoryStatus,
                onAdd = viewModel::addCategory,
                onRemove = viewModel::removeCategory,
                onDismissStatus = viewModel::clearCategoryStatus,
            )

            Section("About")
            Stat("Version", viewModel.versionName)
            Text(
                "Fully offline. Screenshots are classified and searched on this device; " +
                    "the only network use is the one-time model download above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun CategoriesSection(
    categories: List<CustomCategoryEntity>,
    canAdd: Boolean,
    status: String?,
    onAdd: (String) -> Unit,
    onRemove: (CustomCategoryEntity) -> Unit,
    onDismissStatus: () -> Unit,
) {
    Text(
        "Define your own concepts (for example \"boarding pass\" or \"memes\"). They are " +
            "matched visually and applied automatically. Matches can be imperfect; remove a " +
            "wrong tag from any screenshot.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (!canAdd) {
        Text(
            "Install the text model (above) to add categories.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (categories.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            categories.forEach { cat ->
                InputChip(
                    selected = true,
                    onClick = {},
                    label = { Text(cat.label) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove ${cat.label}",
                            modifier = Modifier.clickable { onRemove(cat) },
                        )
                    },
                )
            }
        }
    }

    if (canAdd) {
        var newCat by remember { mutableStateOf("") }
        fun submit() {
            if (newCat.isNotBlank()) {
                onAdd(newCat)
                newCat = ""
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newCat,
                onValueChange = { newCat = it; onDismissStatus() },
                label = { Text("New category") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { submit() }, enabled = newCat.isNotBlank(), modifier = Modifier.padding(start = 8.dp)) {
                Text("Add")
            }
        }
    }

    if (status != null) {
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Stat(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ModelRow(label: String, installed: Boolean, bytes: Long) {
    val value = if (installed) "Installed · ${bytes / 1_000_000} MB" else "Not installed"
    Stat(label, value)
}
