package com.okapiorbits.sshotclassifier.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
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
    val ocrText by remember(screenshotId) { viewModel.ocrText(screenshotId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val suggestions by remember(screenshotId) { viewModel.suggestions(screenshotId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val capturePrefs by viewModel.capturePreferences.collectAsStateWithLifecycle(initialValue = null)
    val resolving by viewModel.resolving.collectAsStateWithLifecycle()
    val resolveMessage by viewModel.resolveMessage.collectAsStateWithLifecycle()
    var newLabel by remember { mutableStateOf("") }
    var viewerOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val fileUri = remember(filePath) { filePath.toUri() }

    fun submit() {
        viewModel.addTag(screenshotId, newLabel)
        newLabel = ""
    }

    // Full-screen zoom/share/rotate/info viewer opened by tapping the hero image.
    screenshot?.let { shot ->
        if (viewerOpen) {
            FullScreenImageViewer(screenshot = shot, onClose = { viewerOpen = false })
            return
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
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
                .imePadding(),
        ) {
            // Hero image — full bleed, aspect-ratio matched to the actual image dimensions.
            val imageModifier = screenshot
                ?.takeIf { it.width > 0 && it.height > 0 }
                ?.let { Modifier.aspectRatio(it.width.toFloat() / it.height) }
                ?: Modifier.heightIn(max = 320.dp)

            AsyncImage(
                model = filePath,
                contentDescription = "Tap to view full screen",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(imageModifier)
                    .clickable { viewerOpen = true },
            )

            // Quick-action strip: share, open, copy QR (when present).
            QuickActionsRow(
                fileUri = fileUri,
                qrPayload = screenshot?.qr_payload?.takeIf { it.isNotBlank() },
                onOpenViewer = { viewerOpen = true },
                onShare = {
                    context.startActivity(
                        Intent(Intent.ACTION_SEND)
                            .setType("image/*")
                            .putExtra(Intent.EXTRA_STREAM, fileUri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            .let { Intent.createChooser(it, null) }
                    )
                },
                onOpenExternal = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(fileUri, "image/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                },
            )

            HorizontalDivider()

            // Content sections — 16 dp horizontal gutter.
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {

                // Tags
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Tags",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (tags.isEmpty()) {
                        Text(
                            "No tags yet — add one below.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            tags.forEach { tag -> TagChip(tag, onRemove = { viewModel.removeTag(tag) }) }
                        }
                    }
                }

                // CLIP tag suggestions (model-driven, not yet applied).
                if (suggestions.isNotEmpty()) {
                    SuggestionSection(
                        suggestions = suggestions,
                        onAdd = { viewModel.addTag(screenshotId, it) },
                    )
                }

                // Camera capture description.
                screenshot?.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Description",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(description, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // QR / barcode section (camera captures only).
                screenshot?.qr_payload?.takeIf { it.isNotBlank() }?.let { payload ->
                    QrCaptureSection(
                        payload = payload,
                        resolved = screenshot?.qr_resolved_at != null,
                        title = screenshot?.qr_title,
                        description = screenshot?.qr_description,
                        imageUrl = screenshot?.qr_image_url,
                        resolveEnabled = capturePrefs?.resolveQrLinks == true,
                        downloadImages = capturePrefs?.downloadPreviewImages == true,
                        resolving = resolving,
                        message = resolveMessage,
                        onResolve = { viewModel.resolveLink(screenshotId) },
                    )
                }

                // Extracted OCR text.
                ocrText?.takeIf { it.isNotBlank() }?.let { text -> OcrSection(text) }

                // Add tag input.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
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
}

/** Share, open-externally, and copy-QR quick-action strip shown below the hero image. */
@Composable
private fun QuickActionsRow(
    fileUri: Uri,
    qrPayload: String?,
    onShare: () -> Unit,
    onOpenExternal: () -> Unit,
    onOpenViewer: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        IconButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = "Share image")
        }
        IconButton(onClick = onOpenExternal) {
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open in another app")
        }
        if (qrPayload != null) {
            IconButton(onClick = { clipboard.setText(AnnotatedString(qrPayload)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy QR payload")
            }
        }
    }
}

/** Read-only display of the extracted OCR text, selectable, with a one-tap copy. */
@Composable
private fun OcrSection(text: String) {
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Extracted text (OCR)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy text")
            }
        }
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

private fun isHttpUrl(s: String): Boolean =
    s.startsWith("http://", true) || s.startsWith("https://", true)

/**
 * QR/barcode block. Shows the decoded payload with a copy button; for a web link it offers
 * resolution. The og:image is only loaded when the user enabled preview images.
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
    val clipboard = LocalClipboardManager.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "QR / barcode",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(payload)) },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copy QR payload",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(payload, style = MaterialTheme.typography.bodyMedium)

        if (isHttpUrl(payload)) {
            when {
                resolved -> Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
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
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Tags the model suggests for this image (CLIP zero-shot, not yet applied). Tapping a chip adds
 * it as a user tag.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionSection(suggestions: List<String>, onAdd: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Suggested tags",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { label ->
                AssistChip(
                    onClick = { onAdd(label) },
                    label = { Text(label) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add $label",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
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
