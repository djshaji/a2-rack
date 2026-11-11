package org.acoustixaudio.a2rack

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: A2RackViewModel, onStartAudio: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showExportOptions by remember { mutableStateOf(false) }

    val namLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.loadNamFile(context, it) }
    }

    val wavLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.loadWavFile(context, it) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        uri?.let { viewModel.exportPreset(context, it) }
    }

    val jsonExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { viewModel.exportPreset(context, it, portable = false) }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.importPreset(context, it) }
    }

    if (showExportOptions) {
        AlertDialog(
            onDismissRequest = { showExportOptions = false },
            title = { Text("Export preset") },
            text = { Text("Portable bundles include the amp and cabinet files. Settings-only JSON uses models already in this app's library.") },
            confirmButton = {
                TextButton(onClick = {
                    showExportOptions = false
                    exportLauncher.launch("preset.a2rack")
                }) { Text("Portable bundle") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportOptions = false
                    jsonExportLauncher.launch("preset.json")
                }) { Text("Settings only") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("A2 Rack") })
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(if (state.isEngineRunning) "Audio running" else "Audio stopped")
                if (!state.isEngineRunning) {
                    Button(onClick = onStartAudio, enabled = !state.isBusy) {
                        Text("Start Audio")
                    }
                }
                if (state.isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                state.errorMessage?.let { message ->
                    Text(message, color = MaterialTheme.colorScheme.error)
                }

                // Gains
                GainControl(
                    label = "Input Gain",
                    value = state.inputGain,
                    onValueChange = viewModel::updateInputGain,
                    enabled = !state.isBusy
                )

                GainControl(
                    label = "Output Gain",
                    value = state.outputGain,
                    onValueChange = viewModel::updateOutputGain,
                    enabled = !state.isBusy
                )

                HorizontalDivider()

                // Bypasses
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Amp Bypass", style = MaterialTheme.typography.titleMedium)
                    Switch(enabled = !state.isBusy, checked = state.isAmpBypassed, onCheckedChange = viewModel::toggleAmpBypass)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Cab Bypass", style = MaterialTheme.typography.titleMedium)
                    Switch(enabled = !state.isBusy, checked = state.isCabBypassed, onCheckedChange = viewModel::toggleCabBypass)
                }

                HorizontalDivider()

                // Mode switch
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("A2 Mode", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Lite")
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(enabled = !state.isBusy, checked = state.isA2Full, onCheckedChange = viewModel::toggleA2Mode)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Full")
                    }
                }

                HorizontalDivider()

                // File Pickers
                FilePickerItem(
                    label = "Neural Amp Model (.nam)",
                    fileName = state.namAsset?.name,
                    issue = state.namIssue,
                    onPickFile = { namLauncher.launch("*/*") },
                    enabled = state.isEngineRunning && !state.isBusy
                )

                FilePickerItem(
                    label = "Cab Impulse Response (.wav)",
                    fileName = state.irAsset?.name,
                    issue = state.irIssue,
                    onPickFile = { wavLauncher.launch("audio/wav") },
                    enabled = state.isEngineRunning && !state.isBusy
                )

                HorizontalDivider()

                // Import / Export
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(enabled = state.isEngineRunning && !state.isBusy, onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Text("Import Preset")
                    }
                    Button(enabled = !state.isBusy, onClick = { showExportOptions = true }) {
                        Text("Export Preset")
                    }
                }
            }
        }
    }
}

@Composable
fun GainControl(label: String, value: Float, onValueChange: (Float) -> Unit, enabled: Boolean = true) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(String.format("%.2f", value))
        }
        Slider(
            enabled = enabled,
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f
        )
    }
}

@Composable
fun FilePickerItem(label: String, fileName: String?, onPickFile: () -> Unit, enabled: Boolean = true, issue: String? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                text = fileName ?: "No file selected",
                style = MaterialTheme.typography.bodyMedium,
                color = if (fileName == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
            issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = onPickFile, enabled = enabled, modifier = Modifier.align(Alignment.End)) {
                Text(if (issue == null) "Select File" else "Replace File")
            }
        }
    }
}
