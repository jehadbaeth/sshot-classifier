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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
    val screenshot by remember(screenshotId) { viewModel.screenshot(screenshotId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val capturePrefs by viewModel.capturePreferences.collectAsStateWithLifecycle(initialValue = null)
    val resolving by viewModel.resolving.collectAsStateWithLifecycle()
    val resolveMessage by viewModel.resolveMessage.collectAsStateWithLifecycle()
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

            // Camera captures carry a composed description and (optionally) a decoded QR payload.
            screenshot?.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(description, style = MaterialTheme.typography.bodyLarge)
            }
            screenshot?.let { shot ->
                shot.qr_payload?.takeIf { it.isNotBlank() }?.let { payload ->
                    QrCaptureSection(
                        payload = payload,
                        resolved = shot.qr_resolved_at != null,
                        title = shot.qr_title,
                        description = shot.qr_description,
                        imageUrl = shot.qr_image_url,
                        resolveEnabled = capturePrefs?.resolveQrLinks == true,
                        downloadImages = capturePrefs?.downloadPreviewImages == true,
                        resolving = resolving,
                        message = resolveMessage,
                        onResolve = { viewModel.resolveLink(screenshotId) },
                    )
                }
            }

            if (tags.isEmpty()) {
                Text(
                    "No tags yet. Add one below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tags.forEach { tag -> TagChip(tag, onRemove = { viewModel.removeTag(tag) }) }
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

private fun isHttpUrl(s: String): Boolean =
    s.startsWith("http://", true) || s.startsWith("https://", true)

/**
 * QR/barcode block on a capture. Shows the decoded payload; for a web link it offers
 * resolution (a preview card once resolved). The og:image is only loaded when the user
 * enabled preview images, so the toggle genuinely controls the network fetch.
 */
@Composable
private fun QrCaptureSection(
    payload: String,
    resolved: Boolean,
    title: String?,
    description: String?,
    imageUrl: String?,
    resolveEnabled: Boolean,
    downloadImages: Boolean,
    resolving: Boolean,
    message: String?,
    onResolve: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "QR / barcode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(payload, style = MaterialTheme.typography.bodyMedium)

        if (isHttpUrl(payload)) {
            when {
                resolved -> Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (downloadImages && !imageUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = imageUrl,
                                contentDescription = null,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                            )
                        }
                        if (!title.isNullOrBlank()) {
                            Text(title, style = MaterialTheme.typography.titleSmall)
                        }
                        if (!description.isNullOrBlank()) {
                            Text(description, style = MaterialTheme.typography.bodySmall)
                        }
                        if (!downloadImages && !imageUrl.isNullOrBlank()) {
                            Text(
                                "Preview image hidden (enable in Settings)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                resolveEnabled -> OutlinedButton(onClick = onResolve, enabled = !resolving) {
                    if (resolving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Resolve link")
                    }
                }

                else -> Text(
                    "Link resolution is off. Turn it on in Settings to preview where this goes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (message != null) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
