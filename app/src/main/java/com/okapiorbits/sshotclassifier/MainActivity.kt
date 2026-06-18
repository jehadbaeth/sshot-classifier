package com.okapiorbits.sshotclassifier

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okapiorbits.sshotclassifier.ui.AppearanceViewModel
import com.okapiorbits.sshotclassifier.ui.camera.CameraCaptureScreen
import com.okapiorbits.sshotclassifier.ui.camera.CameraCaptureViewModel
import com.okapiorbits.sshotclassifier.ui.gallery.GalleryScreen
import com.okapiorbits.sshotclassifier.ui.gallery.GalleryViewModel
import com.okapiorbits.sshotclassifier.ui.search.SearchScreen
import com.okapiorbits.sshotclassifier.ui.search.SearchViewModel
import com.okapiorbits.sshotclassifier.ui.settings.SettingsScreen
import com.okapiorbits.sshotclassifier.ui.settings.SettingsViewModel
import com.okapiorbits.sshotclassifier.ui.theme.ScreenshotClassifierTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearance: AppearanceViewModel = hiltViewModel()
            val appTheme by appearance.appTheme.collectAsStateWithLifecycle()
            ScreenshotClassifierTheme(theme = appTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

private fun imagePermission(): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val permission = imagePermission()

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> granted = isGranted }

    if (granted) {
        MainScaffold()
    } else {
        PermissionGate(onRequest = { launcher.launch(permission) })
    }
}

private enum class Tab(val label: String) { Gallery("Gallery"), Search("Search"), Settings("Settings") }

@Composable
private fun MainScaffold() {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) },
                    label = { Text(Tab.Gallery.label) },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text(Tab.Search.label) },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(Tab.Settings.label) },
                )
            }
        },
    ) { padding ->
        Surface(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> {
                    var showCamera by rememberSaveable { mutableStateOf(false) }
                    if (showCamera) {
                        val camVm: CameraCaptureViewModel = hiltViewModel()
                        CameraCaptureScreen(camVm, onClose = { showCamera = false })
                    } else {
                        val vm: GalleryViewModel = hiltViewModel()
                        GalleryScreen(vm, onOpenCamera = { showCamera = true })
                    }
                }
                1 -> {
                    val vm: SearchViewModel = hiltViewModel()
                    SearchScreen(vm)
                }
                else -> {
                    val vm: SettingsViewModel = hiltViewModel()
                    SettingsScreen(vm)
                }
            }
        }
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("This app needs access to your images to find and classify screenshots. Everything stays on your device.")
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) {
            Text("Grant access")
        }
    }
}
