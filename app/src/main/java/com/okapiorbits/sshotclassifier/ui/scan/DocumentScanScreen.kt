package com.okapiorbits.sshotclassifier.ui.scan

import android.graphics.PointF
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Full-screen corner-drag crop editor: shows the source image with a draggable
 * quadrilateral overlay defaulting to a centered inset, then perspective-warps
 * the enclosed region into a straight rectangle and indexes it as a scan.
 * Used both right after a "Scan" mode camera capture and for turning an
 * existing gallery image into a scan.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScanScreen(
    viewModel: DocumentScanViewModel,
    sourceUri: Uri,
    origin: ScanOrigin,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(sourceUri) { viewModel.load(sourceUri, origin) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    BackHandler {
        viewModel.cancel()
        onCancel()
    }
    LaunchedEffect(state.done) { if (state.done) onDone() }
    LaunchedEffect(state.error) {
        state.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan document") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.cancel(); onCancel() }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.bitmap != null -> {
                    val bitmap = state.bitmap!!
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CropEditor(
                                corners = state.corners,
                                bitmapWidth = bitmap.width,
                                bitmapHeight = bitmap.height,
                                onCornerDrag = viewModel::moveCorner,
                                modifier = Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height),
                                imageBitmap = remember(bitmap) { bitmap.asImageBitmap() },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(onClick = { viewModel.resetCorners() }, enabled = !state.saving) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(" Reset", modifier = Modifier.padding(start = 4.dp))
                            }
                            Button(onClick = { viewModel.confirm() }, enabled = !state.saving) {
                                if (state.saving) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text("Use scan")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Touch radius, in local (screen) pixels, within which a drag grabs a corner handle. */
private const val HANDLE_TOUCH_RADIUS_PX = 80f

/** How much the loupe magnifies the image content under the finger. */
private const val LOUPE_ZOOM = 2.5f
private val LOUPE_DIAMETER = 176.dp
private val LOUPE_FINGER_OFFSET = 140.dp

@Composable
private fun CropEditor(
    corners: List<PointF>,
    bitmapWidth: Int,
    bitmapHeight: Int,
    onCornerDrag: (Int, PointF) -> Unit,
    imageBitmap: androidx.compose.ui.graphics.ImageBitmap,
    modifier: Modifier = Modifier,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var activeIndex by remember { mutableStateOf<Int?>(null) }
    // Raw finger position (local coords) while dragging a handle, for the magnifier loupe.
    var dragPosition by remember { mutableStateOf<Offset?>(null) }

    fun toLocal(p: PointF): Offset {
        if (boxSize.width == 0 || boxSize.height == 0) return Offset.Zero
        val sx = boxSize.width / bitmapWidth.toFloat()
        val sy = boxSize.height / bitmapHeight.toFloat()
        return Offset(p.x * sx, p.y * sy)
    }

    fun toBitmap(o: Offset): PointF {
        val sx = bitmapWidth / boxSize.width.toFloat()
        val sy = bitmapHeight / boxSize.height.toFloat()
        return PointF(o.x * sx, o.y * sy)
    }

    Box(
        modifier = modifier
            .onSizeChanged { boxSize = it }
            .pointerInput(corners.size) {
                detectDragGestures(
                    onDragStart = { start ->
                        val idx = corners
                            .map { toLocal(it) }
                            .withIndex()
                            .minByOrNull { (_, pt) -> (pt - start).getDistance() }
                            ?.takeIf { (_, pt) -> (pt - start).getDistance() < HANDLE_TOUCH_RADIUS_PX }
                            ?.index
                        activeIndex = idx
                        dragPosition = if (idx != null) start else null
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val idx = activeIndex ?: return@detectDragGestures
                        dragPosition = change.position
                        onCornerDrag(idx, toBitmap(change.position))
                    },
                    onDragEnd = { activeIndex = null; dragPosition = null },
                    onDragCancel = { activeIndex = null; dragPosition = null },
                )
            },
    ) {
        Image(bitmap = imageBitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (corners.size != 4) return@Canvas
            val pts = corners.map { toLocal(it) }
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                lineTo(pts[1].x, pts[1].y)
                lineTo(pts[2].x, pts[2].y)
                lineTo(pts[3].x, pts[3].y)
                close()
            }
            drawPath(path, color = Color.White.copy(alpha = 0.18f))
            drawPath(path, color = Color.White, style = Stroke(width = 4f))
            pts.forEach { pt ->
                drawCircle(color = Color.White, radius = 24f, center = pt)
                drawCircle(color = Color(0xFF2196F3), radius = 14f, center = pt)
            }

            val finger = dragPosition ?: return@Canvas
            val margin = 8.dp.toPx()
            val loupeRadius = LOUPE_DIAMETER.toPx() / 2f
            val fingerOffsetPx = LOUPE_FINGER_OFFSET.toPx()

            var loupeCenterY = finger.y - fingerOffsetPx
            if (loupeCenterY - loupeRadius < margin) loupeCenterY = finger.y + fingerOffsetPx
            loupeCenterY = loupeCenterY.coerceIn(loupeRadius + margin, size.height - loupeRadius - margin)
            val loupeCenterX = finger.x.coerceIn(loupeRadius + margin, size.width - loupeRadius - margin)
            val loupeCenter = Offset(loupeCenterX, loupeCenterY)

            // Source crop is centered on the finger's bitmap-space position, sized so it
            // fills the loupe at LOUPE_ZOOM magnification.
            val scale = boxSize.width / bitmapWidth.toFloat()
            val bitmapPoint = toBitmap(finger)
            val halfSrc = (loupeRadius / LOUPE_ZOOM) / scale
            val maxHalfSrcW = bitmapWidth / 2f
            val maxHalfSrcH = bitmapHeight / 2f
            val clampedHalfSrc = halfSrc.coerceAtMost(minOf(maxHalfSrcW, maxHalfSrcH))
            val srcLeft = bitmapPoint.x.coerceIn(clampedHalfSrc, bitmapWidth - clampedHalfSrc) - clampedHalfSrc
            val srcTop = bitmapPoint.y.coerceIn(clampedHalfSrc, bitmapHeight - clampedHalfSrc) - clampedHalfSrc
            val srcSize = (clampedHalfSrc * 2f).toInt().coerceAtLeast(1)

            clipPath(Path().apply { addOval(Rect(center = loupeCenter, radius = loupeRadius)) }) {
                drawImage(
                    image = imageBitmap,
                    srcOffset = IntOffset(srcLeft.toInt(), srcTop.toInt()),
                    srcSize = IntSize(srcSize, srcSize),
                    dstOffset = IntOffset((loupeCenterX - loupeRadius).toInt(), (loupeCenterY - loupeRadius).toInt()),
                    dstSize = IntSize(LOUPE_DIAMETER.toPx().toInt(), LOUPE_DIAMETER.toPx().toInt()),
                )
            }
            drawCircle(color = Color.White, radius = loupeRadius, center = loupeCenter, style = Stroke(width = 6f))
            drawCircle(color = Color(0xFF2196F3), radius = loupeRadius, center = loupeCenter, style = Stroke(width = 3f))
            val crosshair = 14f
            drawLine(
                Color(0xFF2196F3),
                Offset(loupeCenterX - crosshair, loupeCenterY),
                Offset(loupeCenterX + crosshair, loupeCenterY),
                strokeWidth = 4f,
            )
            drawLine(
                Color(0xFF2196F3),
                Offset(loupeCenterX, loupeCenterY - crosshair),
                Offset(loupeCenterX, loupeCenterY + crosshair),
                strokeWidth = 4f,
            )
        }
    }
}
