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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: A2RackViewModel,
    onStartAudio: () -> Unit,
    onStopAudio: () -> Unit = viewModel::stopAudio
) {
    val state by viewModel.uiState.collectAsState()
    MainScreen(state, viewModel, onStartAudio, onStopAudio)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreen(
    state: A2RackState,
    viewModel: A2RackViewModel,
    onStartAudio: () -> Unit,
    onStopAudio: () -> Unit
) {
    val busy = state.isBusy || state.isTransitioning
    val context = LocalContext.current
    var showExportOptions by remember { mutableStateOf(false) }

    val namLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.loadNamFile(context, it) }
    }

    val wavLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
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
                Text(state.audioStatus)
                when {
                    state.isEngineRunning -> Button(onClick = onStopAudio) {
                        Text("Stop Audio")
                    }
                    state.isWaitingForRoute -> OutlinedButton(onClick = viewModel::stopAudio) {
                        Text("Cancel reconnect")
                    }
                    !state.isTransitioning -> Button(onClick = onStartAudio, enabled = !busy) {
                        Text(if (state.audioFailed) "Retry" else "Start Audio")
                    }
                }
                if (busy) {
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
                    enabled = !busy
                )

                GainControl(
                    label = "Output Gain",
                    value = state.outputGain,
                    onValueChange = viewModel::updateOutputGain,
                    enabled = !busy
                )

                HorizontalDivider()

                // Bypasses
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val label = "Amp Bypass"
                    Text(label, modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = state.isAmpBypassed,
                        onCheckedChange = viewModel::toggleAmpBypass,
                        modifier = Modifier.semantics { contentDescription = label },
                        enabled = !busy
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val label = "Cab Bypass"
                    Text(label, modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleMedium)
                    Switch(
                        checked = state.isCabBypassed,
                        onCheckedChange = viewModel::toggleCabBypass,
                        modifier = Modifier.semantics { contentDescription = label },
                        enabled = !busy
                    )
                }

                HorizontalDivider()

                // File Pickers
                FilePickerItem(
                    label = "Neural Amp Model (.nam)",
                    fileName = state.namAsset?.name,
                    issue = state.namIssue,
                    onPickFile = { namLauncher.launch("*/*") },
                    enabled = state.isEngineRunning && !busy
                )

                FilePickerItem(
                    label = "Cab Impulse Response (mono .wav)",
                    fileName = state.irAsset?.name,
                    issue = state.irIssue,
                    onPickFile = { wavLauncher.launch(arrayOf("*/*")) },
                    enabled = state.isEngineRunning && !busy
                )

                HorizontalDivider()

                Text("Audio routing", style = MaterialTheme.typography.titleMedium)
                Text(state.routeLabel, style = MaterialTheme.typography.bodyMedium)
                AudioDeviceSelector("Input device", state.audioDevices.filter { it.input },
                    state.selectedInputId, !busy, viewModel::selectInputDevice)
                AudioDeviceSelector("Output device", state.audioDevices.filter { it.output },
                    state.selectedOutputId, !busy, viewModel::selectOutputDevice)

                HorizontalDivider()

                Text("Presets", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(enabled = state.isEngineRunning && !busy, onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                        Text("Import Preset")
                    }
                    Button(enabled = !busy, onClick = { showExportOptions = true }) {
                        Text("Export Preset")
                    }
                }

                AudioDiagnosticsPanel(viewModel)
            }
        }
    }
}

@Composable
fun GainControl(label: String, value: Float, onValueChange: (Float) -> Unit, enabled: Boolean = true) {
    val displayedValue = "%.2f".format(value)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleMedium)
            Text(displayedValue, modifier = Modifier.clearAndSetSemantics {})
        }
        Slider(
            modifier = Modifier.semantics {
                contentDescription = label
                stateDescription = displayedValue
            },
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

@Composable
private fun AudioDeviceSelector(
    label: String, devices: List<AudioEndpoint>, selectedId: Int?,
    enabled: Boolean, onSelect: (Int?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = if (selectedId == null) "Automatic"
        else devices.firstOrNull { it.id == selectedId }?.let { "${it.name} (#${it.id})" }
            ?: "Disconnected device (#$selectedId)"
    Column {
        Text(label, modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleSmall)
        Box {
            OutlinedButton(
                enabled = enabled,
                onClick = { expanded = true },
                modifier = Modifier.semantics {
                    contentDescription = label
                    stateDescription = selected
                }
            ) {
                Text(selected, modifier = Modifier.clearAndSetSemantics {})
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("Automatic") }, onClick = {
                    expanded = false
                    onSelect(null)
                })
                devices.forEach { device ->
                    DropdownMenuItem(text = { Text("${device.name} (#${device.id})") }, onClick = {
                        expanded = false
                        onSelect(device.id)
                    })
                }
            }
        }
        if (selectedId != null && devices.none { it.id == selectedId }) {
            Text("Choose an available device or Automatic.", color = MaterialTheme.colorScheme.error)
        }
    }
}
