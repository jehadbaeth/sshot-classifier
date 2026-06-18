package com.okapiorbits.sshotclassifier.ui.detail

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.data.db.entity.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen image viewer: pinch / double-tap to zoom, drag to pan, rotate the view, and
 * share / open the file externally. View-only — rotation and zoom never modify the saved file.
 * Opened over the detail screen; [onClose] dismisses it.
 */
@Composable
fun FullScreenImageViewer(screenshot: ScreenshotEntity, onClose: () -> Unit) {
    val context = LocalContext.current
    val uri = remember(screenshot.file_path) { Uri.parse(screenshot.file_path) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember { mutableIntStateOf(0) }
    var showInfo by remember { mutableStateOf(false) }

    BackHandler(onBack = onClose)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AsyncImage(
            model = screenshot.file_path,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 6f)
                        // Pan only while zoomed in; snap back to centre at 1x.
                        offset = if (scale > 1f) offset + pan else Offset.Zero
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f; offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                    )
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    rotationZ = rotation.toFloat()
                },
        )

        // Toolbar over a subtle scrim at the top.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            Box(Modifier.weight(1f))
            IconButton(onClick = { rotation = (rotation + 90) % 360 }) {
                Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Rotate", tint = Color.White)
            }
            IconButton(onClick = { share(context, uri) }) {
                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
            }
            IconButton(onClick = { openExternally(context, uri) }) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open with", tint = Color.White)
            }
            IconButton(onClick = { showInfo = true }) {
                Icon(Icons.Default.Info, contentDescription = "Info", tint = Color.White)
            }
        }
    }

    if (showInfo) {
        ImageInfoDialog(screenshot = screenshot, uri = uri, onDismiss = { showInfo = false })
    }
}

@Composable
private fun ImageInfoDialog(screenshot: ScreenshotEntity, uri: Uri, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val name = remember(uri) { uri.lastPathSegment ?: screenshot.file_path.substringAfterLast('/') }
    // File size needs an I/O query; resolve it off the main thread when the dialog opens.
    val sizeText by produceState(initialValue = "…", uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) Formatter.formatShortFileSize(context, c.getLong(0)) else null
                }
            }.getOrNull() ?: "unknown"
        }
    }
    val source = if (screenshot.source_type == SourceType.CAMERA.name) "Camera capture" else "Screenshot"
    // MediaStore DATE_ADDED is seconds; other sources may store millis. Normalise.
    val millis = if (screenshot.date_added < 1_000_000_000_000L) screenshot.date_added * 1000 else screenshot.date_added
    val date = DateUtils.formatDateTime(
        context, millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR,
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Image info") },
        text = {
            Column {
                InfoLine("Name", name)
                InfoLine("Type", source)
                InfoLine("Added", date)
                InfoLine("Dimensions", "${screenshot.width} × ${screenshot.height}")
                InfoLine("Size", sizeText)
            }
        },
    )
}

@Composable
private fun InfoLine(label: String, value: String) {
    Text(
        buildString { append(label).append(": ").append(value) },
        style = MaterialTheme.typography.bodyMedium,
    )
}

private fun share(context: android.content.Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share image"))
}

private fun openExternally(context: android.content.Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Open with"))
}
