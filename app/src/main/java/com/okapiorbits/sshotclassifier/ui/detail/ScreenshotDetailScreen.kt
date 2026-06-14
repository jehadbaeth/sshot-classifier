package com.okapiorbits.sshotclassifier.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.okapiorbits.sshotclassifier.data.db.entity.TagEntity
import com.okapiorbits.sshotclassifier.data.db.entity.TagSource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScreenshotDetailScreen(
    screenshotId: Long,
    filePath: String,
    viewModel: ScreenshotDetailViewModel,
    onBack: () -> Unit,
) {
    val tags by remember(screenshotId) { viewModel.tags(screenshotId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var newLabel by remember { mutableStateOf("") }

    fun submit() {
        viewModel.addTag(screenshotId, newLabel)
        newLabel = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tags") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AsyncImage(
                model = filePath,
                contentDescription = null,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            )

            if (tags.isEmpty()) {
                Text(
                    "No tags yet. Add one below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { tag -> TagChip(tag, onRemove = { viewModel.removeTag(tag.id) }) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newLabel,
                    onValueChange = { newLabel = it },
                    label = { Text("Add a tag") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { submit() }, enabled = newLabel.isNotBlank()) {
                    Icon(Icons.Default.Add, contentDescription = "Add tag")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagChip(tag: TagEntity, onRemove: () -> Unit) {
    val isUser = tag.source == TagSource.USER.name
    InputChip(
        selected = isUser,
        onClick = {},
        label = { Text(tag.label) },
        trailingIcon = {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove ${tag.label}",
                modifier = Modifier.clickable(onClick = onRemove),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = InputChipDefaults.inputChipColors(),
    )
}
