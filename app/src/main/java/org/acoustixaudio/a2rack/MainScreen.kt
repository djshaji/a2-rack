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
fun MainScreen(viewModel: A2RackViewModel) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val namLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.loadNamFile(context, it) }
    }

    val wavLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.loadWavFile(context, it) }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { viewModel.exportPreset(context, it) }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.importPreset(context, it) }
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
                // Gains
                GainControl(
                    label = "Input Gain",
                    value = state.inputGain,
                    onValueChange = viewModel::updateInputGain
                )

                GainControl(
                    label = "Output Gain",
                    value = state.outputGain,
                    onValueChange = viewModel::updateOutputGain
                )

                HorizontalDivider()

                // Bypasses
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Amp Bypass", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = state.isAmpBypassed, onCheckedChange = viewModel::toggleAmpBypass)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Cab Bypass", style = MaterialTheme.typography.titleMedium)
                    Switch(checked = state.isCabBypassed, onCheckedChange = viewModel::toggleCabBypass)
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
                        Switch(checked = state.isA2Full, onCheckedChange = viewModel::toggleA2Mode)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Full")
                    }
                }

                HorizontalDivider()

                // File Pickers
                FilePickerItem(
                    label = "Neural Amp Model (.nam)",
                    filePath = state.namFilePath,
                    onPickFile = { namLauncher.launch("*/*") }
                )

                FilePickerItem(
                    label = "Cab Impulse Response (.wav)",
                    filePath = state.wavFilePath,
                    onPickFile = { wavLauncher.launch("audio/wav") }
                )

                HorizontalDivider()

                // Import / Export
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Button(onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) }) {
                        Text("Import Preset")
                    }
                    Button(onClick = { exportLauncher.launch("preset.json") }) {
                        Text("Export Preset")
                    }
                }
            }
        }
    }
}

@Composable
fun GainControl(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(String.format("%.2f", value))
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = 0f..1f
        )
    }
}

@Composable
fun FilePickerItem(label: String, filePath: String?, onPickFile: () -> Unit) {
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
                text = filePath?.substringAfterLast('/') ?: "No file selected",
                style = MaterialTheme.typography.bodyMedium,
                color = if (filePath == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
            Button(onClick = onPickFile, modifier = Modifier.align(Alignment.End)) {
                Text("Select File")
            }
        }
    }
}
