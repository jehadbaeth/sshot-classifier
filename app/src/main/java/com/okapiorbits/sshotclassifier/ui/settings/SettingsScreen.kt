package com.okapiorbits.sshotclassifier.ui.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.okapiorbits.sshotclassifier.data.db.entity.CustomCategoryEntity
import com.okapiorbits.sshotclassifier.data.media.WatchableFolder
import com.okapiorbits.sshotclassifier.data.prefs.CapturePreferences
import com.okapiorbits.sshotclassifier.data.prefs.DescriptionSource
import com.okapiorbits.sshotclassifier.data.prefs.OcrLanguage
import com.okapiorbits.sshotclassifier.data.prefs.ResolveTrigger
import com.okapiorbits.sshotclassifier.data.prefs.ReorgMode
import com.okapiorbits.sshotclassifier.data.prefs.ReorgPreferences

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
    val reorgPrefs by viewModel.reorgPrefs.collectAsStateWithLifecycle()
    val undoableMoves by viewModel.undoableMoves.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val watchedFolders by viewModel.watchedFolders.collectAsStateWithLifecycle()
    val availableFolders by viewModel.availableFolders.collectAsStateWithLifecycle()
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val capturePrefs by viewModel.capturePrefs.collectAsStateWithLifecycle()
    val backupStatus by viewModel.backupStatus.collectAsStateWithLifecycle()
    val generativeUi by viewModel.generativeUi.collectAsStateWithLifecycle()
    val vlmInstalled by viewModel.vlmModelInstalled.collectAsStateWithLifecycle()
    val vlmImport by viewModel.vlmImport.collectAsStateWithLifecycle()
    val devMode by viewModel.devMode.collectAsStateWithLifecycle()
    val logExportStatus by viewModel.logExportStatus.collectAsStateWithLifecycle()
    val ocrLanguage by viewModel.ocrLanguage.collectAsStateWithLifecycle()
    val ocrReprocessStatus by viewModel.ocrReprocessStatus.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadFolders() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::exportTagsTo) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importTagsFrom) }
    // The VLM model has no standard MIME type (.task); accept any file and validate by size.
    val vlmImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importVlmModel) }
    val logExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> uri?.let(viewModel::exportDebugLogsTo) }

    // MOVE mode: launch the system delete-consent dialog when one is pending.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.onDeleteApproved()
        else viewModel.onDeleteCancelled()
    }
    LaunchedEffect(pendingDelete) {
        pendingDelete?.let { deleteLauncher.launch(it.request) }
    }

    // Each section is a collapsible card so the screen opens as a short, scannable list
    // instead of one long scroll. Library starts open (cheap stats); AI models opens itself
    // when a download is in flight so its progress isn't hidden behind a closed card.
    var libraryExpanded by rememberSaveable { mutableStateOf(true) }
    var foldersExpanded by rememberSaveable { mutableStateOf(false) }
    var modelsExpanded by rememberSaveable { mutableStateOf(download is DownloadState.Running) }
    var maintenanceExpanded by rememberSaveable { mutableStateOf(false) }
    var ocrExpanded by rememberSaveable { mutableStateOf(false) }
    var reorgExpanded by rememberSaveable { mutableStateOf(false) }
    var cameraExpanded by rememberSaveable { mutableStateOf(false) }
    var categoriesExpanded by rememberSaveable { mutableStateOf(false) }
    var backupExpanded by rememberSaveable { mutableStateOf(false) }
    var appearanceExpanded by rememberSaveable { mutableStateOf(false) }
    var developerExpanded by rememberSaveable { mutableStateOf(false) }
    var aboutExpanded by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SettingsCard("Library", libraryExpanded, { libraryExpanded = !libraryExpanded }) {
            Stat("Screenshots indexed", total.toString())
            if (pending > 0) Stat("Waiting to be processed", pending.toString())
            if (reprocessable > 0 && models.imageInstalled) Stat("Awaiting visual tags", reprocessable.toString())
            }

            SettingsCard("Watched folders", foldersExpanded, { foldersExpanded = !foldersExpanded }) {
            WatchedFoldersSection(
                available = availableFolders,
                watched = watchedFolders,
                onToggle = viewModel::setFolderWatched,
            )
            }

            SettingsCard("AI models", modelsExpanded, { modelsExpanded = !modelsExpanded }) {
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
            // Developer aid: app updates keep the downloaded models in internal storage, so the
            // download flow can't be re-tested without clearing data. This removes them in-app.
            if (devMode && (models.imageInstalled || models.textInstalled) && download !is DownloadState.Running) {
                OutlinedButton(
                    onClick = viewModel::removeDownloadedModels,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Remove downloaded models (re-test download)") }
            }
            }

            SettingsCard("Maintenance", maintenanceExpanded, { maintenanceExpanded = !maintenanceExpanded }) {
            if (reprocessable > 0 && models.imageInstalled) {
                OutlinedButton(onClick = viewModel::reprocessMissing, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Reprocess $reprocessable older screenshots")
                }
            }
            OutlinedButton(onClick = viewModel::scanNow, modifier = Modifier.padding(top = 4.dp)) {
                Text(if (pending > 0) "Processing…" else "Scan for new screenshots")
            }
            Text(
                "A background pass also runs every 6 hours.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            }

            SettingsCard("Text recognition (OCR)", ocrExpanded, { ocrExpanded = !ocrExpanded }) {
            OcrLanguageSection(
                current = ocrLanguage,
                reprocessStatus = ocrReprocessStatus,
                onSelect = viewModel::setOcrLanguage,
                onReprocess = viewModel::reprocessAllForOcr,
                onDismissStatus = viewModel::clearOcrReprocessStatus,
            )
            }

            if (viewModel.reorganizeSupported) {
                SettingsCard("Reorganization", reorgExpanded, { reorgExpanded = !reorgExpanded }) {
                ReorganizationSection(
                    prefs = reorgPrefs,
                    moveSupported = viewModel.moveSupported,
                    status = reorganizeStatus,
                    running = reorganizeStatus == SettingsViewModel.RUNNING,
                    undoableMoves = undoableMoves,
                    onRun = viewModel::reorganize,
                    onUndo = viewModel::undoMoves,
                    onModeChange = viewModel::setReorgMode,
                    onAlbumRootChange = viewModel::setAlbumRoot,
                    onNeedsReviewChange = viewModel::setNeedsReviewToUncategorized,
                    onAutoRunChange = viewModel::setAutoRun,
                )
                }
            }

            SettingsCard("Camera capture", cameraExpanded, { cameraExpanded = !cameraExpanded }) {
            CameraCaptureSection(
                prefs = capturePrefs,
                generativeUi = generativeUi,
                vlmInstalled = vlmInstalled,
                vlmModelBytes = viewModel.vlmModelBytes(),
                vlmImport = vlmImport,
                onDownloadModel = viewModel::downloadVlmModel,
                onImportModel = { vlmImportLauncher.launch(arrayOf("*/*")) },
                onDeleteModel = viewModel::deleteVlmModel,
                onDecodeQrChange = viewModel::setDecodeQrCodes,
                onResolveQrLinksChange = viewModel::setResolveQrLinks,
                onTriggerChange = viewModel::setResolveTrigger,
                onWifiOnlyChange = viewModel::setResolveOnWifiOnly,
                onDownloadImagesChange = viewModel::setDownloadPreviewImages,
                onDescriptionSourceChange = viewModel::setDescriptionSource,
                onAlbumRootChange = viewModel::setCaptureAlbumRoot,
            )
            }

            SettingsCard("Custom categories", categoriesExpanded, { categoriesExpanded = !categoriesExpanded }) {
            CategoriesSection(
                categories = categories,
                canAdd = models.textInstalled,
                status = categoryStatus,
                onAdd = viewModel::addCategory,
                onRemove = viewModel::removeCategory,
                onDismissStatus = viewModel::clearCategoryStatus,
            )
            }

            SettingsCard("Backup", backupExpanded, { backupExpanded = !backupExpanded }) {
            Text(
                "Export your manual tags and custom categories to a file you can keep or move to a " +
                    "new device, then import them back. Tags re-attach to the same images by content, " +
                    "so they survive a reinstall. Auto tags are not exported (they regenerate on a scan).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("sshot-tags-backup.json") }) {
                    Text("Export")
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) {
                    Text("Import")
                }
            }
            backupStatus?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            }

            SettingsCard("Appearance", appearanceExpanded, { appearanceExpanded = !appearanceExpanded }) {
            Text(
                "Theme",
                style = MaterialTheme.typography.bodyMedium,
            )
            com.okapiorbits.sshotclassifier.data.prefs.AppTheme.entries.forEach { t ->
                val isDynamic = t == com.okapiorbits.sshotclassifier.data.prefs.AppTheme.DYNAMIC
                RadioRow(
                    label = t.label,
                    subtitle = when {
                        isDynamic && !viewModel.dynamicColorSupported ->
                            "Needs Android 12+; uses the brand palette here."
                        isDynamic -> "Colors follow your wallpaper. Light/dark follow the system."
                        else -> "A fixed palette, independent of your wallpaper."
                    },
                    selected = appTheme == t,
                    enabled = true,
                    onSelect = { viewModel.setAppTheme(t) },
                )
            }
            }

            SettingsCard("Developer", developerExpanded, { developerExpanded = !developerExpanded }) {
            LabeledSwitch(
                label = "Developer mode",
                subtitle = "Unlocks experimental configs on devices that don't meet the " +
                    "recommended bar (they may be slow or crash) and lets you export debug logs. " +
                    "For testing.",
                checked = devMode,
                enabled = true,
                onCheckedChange = viewModel::setDevMode,
            )
            if (devMode) {
                Text(
                    "Export this session's app logs to a file you can share when reporting how a " +
                        "device behaved (especially the experimental generative captions).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedButton(
                    onClick = { logExportLauncher.launch("sshot-debug-log.txt") },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Export debug logs") }
                logExportStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
            }

            SettingsCard("About", aboutExpanded, { aboutExpanded = !aboutExpanded }) {
            Stat("Version", viewModel.versionName)
            Text(
                "Fully offline. Screenshots are classified and searched on this device; " +
                    "the only network use is the one-time model download above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReorganizationSection(
    prefs: ReorgPreferences,
    moveSupported: Boolean,
    status: String?,
    running: Boolean,
    undoableMoves: Int,
    onRun: () -> Unit,
    onUndo: () -> Unit,
    onModeChange: (ReorgMode) -> Unit,
    onAlbumRootChange: (String) -> Unit,
    onNeedsReviewChange: (Boolean) -> Unit,
    onAutoRunChange: (Boolean) -> Unit,
) {
    val moving = prefs.mode == ReorgMode.MOVE && moveSupported
    val runLabel = if (moving) "Move into tag albums" else "Copy into tag albums"

    OutlinedButton(onClick = onRun, enabled = !running, modifier = Modifier.padding(top = 4.dp)) {
        Text(runLabel)
    }
    Text(
        if (moving) {
            "Copies each screenshot into Pictures/${prefs.albumRoot}/<tag>/, then asks you " +
                "to confirm deleting the originals. You can undo a move afterwards."
        } else {
            "Copies each screenshot into Pictures/${prefs.albumRoot}/<tag>/ " +
                "(uncategorized when unsure). Originals are kept; nothing is deleted."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    status?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
    }
    if (undoableMoves > 0) {
        OutlinedButton(onClick = onUndo, enabled = !running, modifier = Modifier.padding(top = 4.dp)) {
            Text("Undo last move ($undoableMoves files)")
        }
    }

    // Album root name.
    var root by remember(prefs.albumRoot) { mutableStateOf(prefs.albumRoot) }
    OutlinedTextField(
        value = root,
        onValueChange = { root = it },
        label = { Text("Album folder name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onAlbumRootChange(root) }),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )

    LabeledSwitch(
        label = "Delete originals after copying (move)",
        subtitle = if (moveSupported) {
            "Off keeps originals (copy). On deletes them after you confirm."
        } else {
            "Move needs Android 11 or newer; this device can only copy."
        },
        checked = moving,
        enabled = moveSupported,
        onCheckedChange = { onModeChange(if (it) ReorgMode.MOVE else ReorgMode.COPY) },
    )
    LabeledSwitch(
        label = "File low-confidence shots into 'uncategorized'",
        subtitle = "Off skips screenshots flagged for review instead of filing them.",
        checked = prefs.needsReviewToUncategorized,
        enabled = true,
        onCheckedChange = onNeedsReviewChange,
    )
    LabeledSwitch(
        label = "Organize automatically after each scan",
        subtitle = "Always copies (never deletes) in the background; moves stay manual.",
        checked = prefs.autoRun,
        enabled = true,
        onCheckedChange = onAutoRunChange,
    )
}

@Composable
private fun CameraCaptureSection(
    prefs: CapturePreferences,
    generativeUi: SettingsViewModel.GenerativeUi,
    vlmInstalled: Boolean,
    vlmModelBytes: Long,
    vlmImport: SettingsViewModel.VlmImportState,
    onDownloadModel: () -> Unit,
    onImportModel: () -> Unit,
    onDeleteModel: () -> Unit,
    onDecodeQrChange: (Boolean) -> Unit,
    onResolveQrLinksChange: (Boolean) -> Unit,
    onTriggerChange: (ResolveTrigger) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onDownloadImagesChange: (Boolean) -> Unit,
    onDescriptionSourceChange: (DescriptionSource) -> Unit,
    onAlbumRootChange: (String) -> Unit,
) {
    Text(
        "Photograph real-world things (storefronts, signs, products, QR codes) into the same " +
            "inventory as screenshots. Everything here is offline by default.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    LabeledSwitch(
        label = "Decode QR codes and barcodes",
        subtitle = "On-device and offline. Tags a capture 'qr code' and stores the value. " +
            "Reading the code never opens the link.",
        checked = prefs.decodeQrCodes,
        enabled = true,
        onCheckedChange = onDecodeQrChange,
    )

    LabeledSwitch(
        label = "Resolve QR links to previews",
        subtitle = "Off keeps everything offline. On lets the app fetch a link's title and " +
            "description over the network so you can preview where it goes.",
        checked = prefs.resolveQrLinks,
        enabled = true,
        onCheckedChange = onResolveQrLinksChange,
    )

    if (prefs.resolveQrLinks) {
        LabeledSwitch(
            label = "Resolve automatically",
            subtitle = "Off resolves only when you tap 'Resolve link' on a capture. " +
                "On resolves while a capture is processed.",
            checked = prefs.resolveTrigger == ResolveTrigger.AUTOMATIC,
            enabled = true,
            onCheckedChange = { onTriggerChange(if (it) ResolveTrigger.AUTOMATIC else ResolveTrigger.MANUAL) },
        )
        LabeledSwitch(
            label = "Wi-Fi only",
            subtitle = "Restrict link resolution to an unmetered connection.",
            checked = prefs.resolveOnWifiOnly,
            enabled = true,
            onCheckedChange = onWifiOnlyChange,
        )
        LabeledSwitch(
            label = "Show preview images",
            subtitle = "Off shows only the link's text. On loads the link's preview image " +
                "from its host (a second network fetch).",
            checked = prefs.downloadPreviewImages,
            enabled = true,
            onCheckedChange = onDownloadImagesChange,
        )
    }

    Text(
        "Description source",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 12.dp),
    )
    RadioRow(
        label = "Structured (offline)",
        subtitle = "Composed on-device from text, tags, and any QR link.",
        selected = prefs.descriptionSource == DescriptionSource.STRUCTURED,
        enabled = true,
        onSelect = { onDescriptionSourceChange(DescriptionSource.STRUCTURED) },
    )
    RadioRow(
        label = "Generative (experimental)",
        subtitle = generativeUi.note,
        selected = prefs.descriptionSource == DescriptionSource.GENERATIVE,
        enabled = generativeUi.selectable,
        onSelect = { onDescriptionSourceChange(DescriptionSource.GENERATIVE) },
    )

    // Model import: shown on a capable device, or on any device once Developer mode forces it.
    // The model is large and never bundled, so the user imports a file they downloaded themselves
    // (see docs/spikes/vlm-device-research.md).
    if (generativeUi.controlsVisible) {
        VlmModelControls(
            installed = vlmInstalled,
            modelBytes = vlmModelBytes,
            importState = vlmImport,
            onDownload = onDownloadModel,
            onImport = onImportModel,
            onDelete = onDeleteModel,
        )
    }

    var root by remember(prefs.captureAlbumRoot) { mutableStateOf(prefs.captureAlbumRoot) }
    OutlinedTextField(
        value = root,
        onValueChange = { root = it },
        label = { Text("Capture album folder name") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onAlbumRootChange(root) }),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    Text(
        "Photos are saved to Pictures/${prefs.captureAlbumRoot}/Captures.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun VlmModelControls(
    installed: Boolean,
    modelBytes: Long,
    importState: SettingsViewModel.VlmImportState,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onDelete: () -> Unit,
) {
    val running = importState as? SettingsViewModel.VlmImportState.Running
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            if (installed) {
                "Model installed (${"%.1f".format(modelBytes / 1_000_000_000.0)} GB). " +
                    "You can switch the description source to Generative above."
            } else {
                "No model yet. Download the Gemma 3n model (~3 GB, verified by checksum) over the " +
                    "network, or import a .task file you downloaded yourself. The model is never " +
                    "bundled with the app. Keep this screen open while it downloads."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (running != null) {
            LinearProgressIndicator(
                progress = { running.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                "Working… ${(running.progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (!installed) {
                    Button(onClick = onDownload) { Text("Download (~3 GB)") }
                    OutlinedButton(onClick = onImport) { Text("Import file") }
                } else {
                    OutlinedButton(onClick = onDelete) { Text("Remove model") }
                }
            }
        }
        (importState as? SettingsViewModel.VlmImportState.Failed)?.let {
            Text(
                it.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun OcrLanguageSection(
    current: OcrLanguage,
    reprocessStatus: String?,
    onSelect: (OcrLanguage) -> Unit,
    onReprocess: () -> Unit,
    onDismissStatus: () -> Unit,
) {
    Text(
        "Which script the app reads from images. Arabic uses a separate engine (Tesseract) since " +
            "the default can't read it. Note: auto-tagging and visual search stay English/Latin-tuned, " +
            "so Arabic gains text extraction, display, and keyword search — not automatic categories.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    RadioRow(
        label = "Latin (default)",
        subtitle = "English and other Latin-script text. Fast, on-device.",
        selected = current == OcrLanguage.LATIN,
        enabled = true,
        onSelect = { onSelect(OcrLanguage.LATIN) },
    )
    RadioRow(
        label = "Arabic (and mixed)",
        subtitle = "Reads Arabic and Latin together in one pass — best for images that mix both " +
            "scripts. Right-to-left text is preserved. Slower than Latin.",
        selected = current == OcrLanguage.ARABIC,
        enabled = true,
        onSelect = { onSelect(OcrLanguage.ARABIC) },
    )
    RadioRow(
        label = "Latin + Arabic (max)",
        subtitle = "Runs the Latin engine and the Arabic+Latin engine and combines them. Most " +
            "coverage, slowest; rarely needed over 'Arabic (and mixed)'.",
        selected = current == OcrLanguage.BOTH,
        enabled = true,
        onSelect = { onSelect(OcrLanguage.BOTH) },
    )
    RadioRow(
        label = "Auto (recommended)",
        subtitle = "Reads Latin first (fast); for images that aren't plain Latin it falls back to " +
            "the Arabic+Latin engine, which also handles mixed text. For a library with many " +
            "mixed-script images, 'Arabic (and mixed)' is more reliable.",
        selected = current == OcrLanguage.AUTO,
        enabled = true,
        onSelect = { onSelect(OcrLanguage.AUTO) },
    )
    Text(
        "A change applies to newly scanned images. To re-read images already processed, re-run OCR:",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    OutlinedButton(onClick = onReprocess, modifier = Modifier.padding(top = 4.dp)) {
        Text("Re-run OCR on existing images")
    }
    reprocessStatus?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        LaunchedEffect(it) { kotlinx.coroutines.delay(4000); onDismissStatus() }
    }
}

@Composable
private fun RadioRow(
    label: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LabeledSwitch(
    label: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun WatchedFoldersSection(
    available: List<WatchableFolder>,
    watched: Set<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    Text(
        "Choose which image folders are watched for new screenshots. New files in a " +
            "watched folder are tagged automatically.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Always show watched folders even if MediaStore lists none yet (e.g. an empty
    // Screenshots bucket on a fresh device); merge them in with an unknown count.
    val byName = available.associateBy { it.name }
    val names = (available.map { it.name } + watched).distinct()
    if (names.isEmpty()) {
        Text(
            "No image folders found on this device yet.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        return
    }

    // Watched folders first, then the busiest — so the relevant ones are up top and the long
    // tail of tiny folders is collapsed behind a "show all".
    val ordered = names.sortedWith(
        compareByDescending<String> { it in watched }.thenByDescending { byName[it]?.imageCount ?: 0 },
    )
    val collapsedCount = 5
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    // Search box appears once there are enough folders to be worth filtering.
    if (names.size > collapsedCount) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search folders") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }

    val filtered = if (query.isBlank()) ordered else ordered.filter { it.contains(query, ignoreCase = true) }
    // While searching, show all matches; otherwise collapse the long tail.
    val showAll = query.isNotBlank() || expanded || filtered.size <= collapsedCount
    val visible = if (showAll) filtered else filtered.take(collapsedCount)

    if (filtered.isEmpty()) {
        Text(
            "No folders match \"$query\".",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    for (name in visible) {
        val count = byName[name]?.imageCount
        LabeledSwitch(
            label = name,
            subtitle = if (count != null) "$count images" else "watched",
            checked = name in watched,
            enabled = true,
            onCheckedChange = { onToggle(name, it) },
        )
    }
    if (query.isBlank() && filtered.size > collapsedCount) {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.padding(top = 4.dp)) {
            Text(if (expanded) "Show fewer" else "Show all ${filtered.size} folders")
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(bottom = 12.dp)) { content() }
            }
        }
    }
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
