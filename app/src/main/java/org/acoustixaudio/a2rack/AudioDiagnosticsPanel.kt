package org.acoustixaudio.a2rack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

@Composable
fun AudioDiagnosticsPanel(viewModel: A2RackViewModel) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide audio diagnostics" else "Show audio diagnostics")
            }
            if (expanded) DiagnosticsContent(viewModel)
        }
    }
}

@Composable
private fun DiagnosticsContent(viewModel: A2RackViewModel) {
    // Only this child subscribes to the 4 Hz meter stream.
    val data by viewModel.diagnostics.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val live = data.running && state.isEngineRunning && !state.isTransitioning && data.collectionError == null
    Text(
        when {
            live && data.muted -> "Live stream • muted"
            live -> "Live stream • refreshes 4 times/second"
            data.streamId > 0 -> "Last stream • inactive"
            else -> "No stream data yet"
        },
        style = MaterialTheme.typography.titleSmall
    )
    Text("Input: ${deviceDescription(data.inputName, data.inputDeviceId)}")
    Text("Output: ${deviceDescription(data.outputName, data.outputDeviceId)}")
    Text("Sample rate: ${data.sampleRate?.let { "$it Hz" } ?: "Unavailable"}")
    Text("Buffers: input ${frames(data.inputBufferFrames)} / output ${frames(data.outputBufferFrames)}")
    Text("Output burst: ${frames(data.outputBurstFrames)} • callback: ${frames(data.callbackFrames)}")
    Text("Estimated input latency: ${milliseconds(data.inputLatencyMs)}")
    Text("Estimated output latency: ${milliseconds(data.outputLatencyMs)}")
    Text("Input + output estimate: ${milliseconds(data.combinedLatencyMs)}")
    Text("Stream estimates exclude extra external-device latency. Round-trip latency requires a loopback measurement.",
        style = MaterialTheme.typography.bodySmall)
    Text("Xruns — input / output: ${data.inputXruns ?: "Unavailable"} / ${data.outputXruns ?: "Unavailable"}")
    Text("Input short reads: ${if (data.streamId > 0) data.shortReads.toString() else "Unavailable"}")
    Text("Xruns indicate overruns/underruns. Short reads count callbacks with missing input, including startup. Counts reset when streams reopen.",
        style = MaterialTheme.typography.bodySmall)

    LevelMeter("Input • before gain", if (live) data.inputPeak else 0f, live && data.inputClipping)
    LevelMeter("Output • before limiting", if (live && !data.muted) data.outputPeak else 0f,
        live && !data.muted && data.outputClipping)
    Text("Peak meters span −60 to 0 dBFS. Clipping indicators hold for one second.",
        style = MaterialTheme.typography.bodySmall)

    Text("Amp: ${modelDiagnosticStatus(state.namAsset?.name, state.namIssue, data.namLoaded,
        state.isAmpBypassed, live)}")
    state.namIssue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text("Cabinet: ${modelDiagnosticStatus(state.irAsset?.name, state.irIssue, data.irLoaded,
        state.isCabBypassed, live)}")
    state.irIssue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text("Latest engine error: ${data.lastEngineError ?: "None recorded"}")
    data.collectionError?.let { Text("Diagnostics unavailable: $it", color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun LevelMeter(label: String, peak: Float, clipped: Boolean) {
    val db = AudioDiagnostics.peakDb(peak)?.let { String.format(Locale.ROOT, "%.1f dBFS", it) } ?: "−∞ dBFS"
    Text("$label: $db${if (clipped) " • CLIP" else ""}",
        color = if (clipped) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    LinearProgressIndicator(
        progress = { AudioDiagnostics.meterFraction(peak) },
        modifier = Modifier.fillMaxWidth(),
        color = if (clipped) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    )
}

internal fun modelDiagnosticStatus(name: String?, issue: String?, loaded: Boolean, bypassed: Boolean, live: Boolean): String =
    when {
        issue != null -> "Unavailable — ${name ?: "model"}"
        name == null -> "Not selected"
        !live -> "Inactive — $name"
        !loaded -> "Not loaded — $name"
        bypassed -> "Bypassed — $name"
        else -> "Active — $name"
    }

private fun deviceDescription(name: String, id: Int?) = id?.let { "$name (#$it)" } ?: "Unavailable"
private fun frames(value: Int?) = value?.let { "$it frames" } ?: "Unavailable"
private fun milliseconds(value: Double?) = value?.let { String.format(Locale.ROOT, "%.1f ms", it) } ?: "Unavailable"
