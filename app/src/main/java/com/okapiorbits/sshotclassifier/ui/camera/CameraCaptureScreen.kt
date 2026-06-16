package com.okapiorbits.sshotclassifier.ui.camera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Full-screen camera capture for the real-world inventory. Photos are written to the
 * user's gallery (Pictures/ScreenshotClassifier/Captures) and handed to the pipeline
 * for OCR, on-device QR decoding, CLIP tagging, and description. The screen stays open
 * after a shot so several things can be captured in a row.
 */
@Composable
fun CameraCaptureScreen(viewModel: CameraCaptureViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onClose)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    if (!hasPermission) {
        PermissionGate(onRequest = { launcher.launch(Manifest.permission.CAMERA) }, onClose = onClose)
        return
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    var capturing by remember { mutableStateOf(false) }
    val captured by viewModel.capturedCount.collectAsStateWithLifecycle()
    val relativePath by viewModel.captureRelativePath.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    try {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture,
                        )
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "Camera unavailable: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Close camera", tint = Color.White)
        }

        if (captured > 0) {
            Text(
                text = "Captured $captured",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
            )
        }

        FloatingActionButton(
            onClick = {
                if (!capturing) {
                    capturing = true
                    takePicture(
                        context = context,
                        imageCapture = imageCapture,
                        relativePath = relativePath,
                        onSaved = { uri ->
                            capturing = false
                            viewModel.onCaptured(uri)
                        },
                        onError = { msg ->
                            capturing = false
                            Toast.makeText(context, "Capture failed: $msg", Toast.LENGTH_LONG).show()
                        },
                    )
                }
            },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).size(72.dp),
            shape = CircleShape,
        ) {
            Icon(Icons.Default.PhotoCamera, contentDescription = "Capture")
        }
    }
}

private fun takePicture(
    context: android.content.Context,
    imageCapture: ImageCapture,
    relativePath: String,
    onSaved: (android.net.Uri) -> Unit,
    onError: (String) -> Unit,
) {
    val name = "capture_${System.currentTimeMillis()}.jpg"
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
        }
    }
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val output = ImageCapture.OutputFileOptions
        .Builder(context.contentResolver, collection, values)
        .build()

    imageCapture.takePicture(
        output,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(results: ImageCapture.OutputFileResults) {
                val uri = results.savedUri
                if (uri != null) onSaved(uri) else onError("no output uri")
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception.message ?: "unknown error")
            }
        },
    )
}

@Composable
private fun PermissionGate(onRequest: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Camera access is needed to photograph and classify things. Photos stay on your device.")
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) { Text("Grant camera access") }
        Button(onClick = onClose, modifier = Modifier.padding(top = 8.dp)) { Text("Back") }
    }
}
