package org.acoustixaudio.a2rack

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class A2RackState(
    val inputGain: Float = 0.5f,
    val outputGain: Float = 0.5f,
    val isAmpBypassed: Boolean = false,
    val isCabBypassed: Boolean = false,
    val isA2Full: Boolean = false,
    val namFilePath: String? = null,
    val wavFilePath: String? = null,
    val isEngineRunning: Boolean = false,
    val isBusy: Boolean = false,
    val errorMessage: String? = null
)

class A2RackViewModel internal constructor(
    private val backend: AudioBackend,
    private val engineDispatcher: CoroutineDispatcher
) : ViewModel() {
    constructor() : this(AudioEngine, AudioEngine.dispatcher)

    private val _uiState = MutableStateFlow(A2RackState())
    val uiState = _uiState.asStateFlow()
    private var monitor: Job? = null

    fun startAudio() {
        if (_uiState.value.isBusy || _uiState.value.isEngineRunning) return
        _uiState.update { it.copy(isBusy = true, errorMessage = null) }
        viewModelScope.launch {
            val error = withContext(engineDispatcher) {
                if (backend.startEngine()) {
                    val state = _uiState.value
                    backend.setInputGain(state.inputGain)
                    backend.setOutputGain(state.outputGain)
                    backend.setNamBypass(state.isAmpBypassed)
                    backend.setIrBypass(state.isCabBypassed)
                    null
                } else backend.lastError().ifBlank { "Cannot start audio." }
            }
            _uiState.update {
                it.copy(isBusy = false, isEngineRunning = error == null,
                    namFilePath = null, wavFilePath = null, errorMessage = error)
            }
            if (error == null) monitorEngine()
        }
    }

    fun permissionDenied() {
        _uiState.update { it.copy(errorMessage = "Microphone permission is required to start audio.") }
    }

    private fun monitorEngine() {
        monitor?.cancel()
        monitor = viewModelScope.launch {
            while (true) {
                delay(500)
                val error = withContext(engineDispatcher) {
                    if (backend.isRunning()) null
                    else backend.lastError().ifBlank { "Audio stopped. Restart audio to continue." }
                }
                if (error != null) {
                    _uiState.update { it.copy(isEngineRunning = false, errorMessage = error) }
                    break
                }
            }
        }
    }

    override fun onCleared() {
        // Use the same queue as loading so native work finishes before shutdown.
        CoroutineScope(engineDispatcher).launch { backend.stopEngine() }
        super.onCleared()
    }

    private fun validGain(gain: Float) = if (gain.isFinite()) gain.coerceIn(0f, 1f) else 0f

    fun updateInputGain(gain: Float) {
        val value = validGain(gain)
        _uiState.update { it.copy(inputGain = value) }
        backend.setInputGain(value)
    }

    fun updateOutputGain(gain: Float) {
        val value = validGain(gain)
        _uiState.update { it.copy(outputGain = value) }
        backend.setOutputGain(value)
    }

    fun toggleAmpBypass(bypassed: Boolean) {
        _uiState.update { it.copy(isAmpBypassed = bypassed) }
        backend.setNamBypass(bypassed)
    }

    fun toggleCabBypass(bypassed: Boolean) {
        _uiState.update { it.copy(isCabBypassed = bypassed) }
        backend.setIrBypass(bypassed)
    }

    fun toggleA2Mode(isFull: Boolean) {
        _uiState.update { it.copy(isA2Full = isFull) }
    }

    fun loadNamFile(context: Context, uri: Uri) = loadFile(context, uri, true)
    fun loadWavFile(context: Context, uri: Uri) = loadFile(context, uri, false)

    private fun loadFile(context: Context, uri: Uri, isNam: Boolean) {
        if (!beginLoading()) return
        val appContext = context.applicationContext
        viewModelScope.launch {
            try {
                withContext(engineDispatcher) {
                    val file = copyUriToTempFile(appContext, uri, if (isNam) ".nam" else ".wav")
                    try {
                        val loaded = if (isNam) backend.loadNamModel(file.absolutePath)
                                     else backend.loadIrModel(file.absolutePath)
                        check(loaded) { backend.lastError().ifBlank { "Cannot load this file." } }
                        _uiState.update {
                            if (isNam) it.copy(namFilePath = file.absolutePath)
                            else it.copy(wavFilePath = file.absolutePath)
                        }
                    } catch (e: Exception) {
                        file.delete()
                        throw e
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "File loading failed.") }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun beginLoading(): Boolean {
        val state = _uiState.value
        if (state.isBusy) return false
        if (!state.isEngineRunning) {
            _uiState.update { it.copy(errorMessage = "Start audio before loading models or presets.") }
            return false
        }
        _uiState.update { it.copy(isBusy = true, errorMessage = null) }
        return true
    }

    fun exportPreset(context: Context, uri: Uri) {
        val state = _uiState.value
        val resolver = context.applicationContext.contentResolver
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("inputGain", state.inputGain.toDouble())
                    put("outputGain", state.outputGain.toDouble())
                    put("isAmpBypassed", state.isAmpBypassed)
                    put("isCabBypassed", state.isCabBypassed)
                    put("isA2Full", state.isA2Full)
                    put("namFilePath", state.namFilePath ?: JSONObject.NULL)
                    put("wavFilePath", state.wavFilePath ?: JSONObject.NULL)
                }
                val output = resolver.openOutputStream(uri) ?: error("Cannot open preset destination.")
                output.use { it.write(json.toString(4).toByteArray()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Preset export failed.") }
            }
        }
    }

    fun importPreset(context: Context, uri: Uri) {
        if (!beginLoading()) return
        val resolver = context.applicationContext.contentResolver
        viewModelScope.launch {
            try {
                withContext(engineDispatcher) {
                    val input = resolver.openInputStream(uri) ?: error("Cannot open preset.")
                    val json = input.use { JSONObject(it.bufferedReader().readText()) }
                    val nam = json.optString("namFilePath").takeUnless { it.isBlank() || it == "null" }
                    val wav = json.optString("wavFilePath").takeUnless { it.isBlank() || it == "null" }
                    // Commit each path only after that model is accepted. If a later
                    // load fails, the UI still describes the actual active models.
                    if (nam != null) {
                        check(backend.loadNamModel(nam)) { backend.lastError() }
                        _uiState.update { it.copy(namFilePath = nam) }
                    }
                    if (wav != null) {
                        check(backend.loadIrModel(wav)) { backend.lastError() }
                        _uiState.update { it.copy(wavFilePath = wav) }
                    }
                    val inputGain = validGain(json.optDouble("inputGain", 0.5).toFloat())
                    val outputGain = validGain(json.optDouble("outputGain", 0.5).toFloat())
                    val ampBypass = json.optBoolean("isAmpBypassed", false)
                    val cabBypass = json.optBoolean("isCabBypassed", false)
                    backend.setInputGain(inputGain)
                    backend.setOutputGain(outputGain)
                    backend.setNamBypass(ampBypass)
                    backend.setIrBypass(cabBypass)
                    _uiState.update {
                        it.copy(inputGain = inputGain, outputGain = outputGain,
                            isAmpBypassed = ampBypass, isCabBypassed = cabBypass,
                            isA2Full = json.optBoolean("isA2Full", false))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Preset import failed.") }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun copyUriToTempFile(context: Context, uri: Uri, suffix: String): File {
        val file = File.createTempFile("a2rack_", suffix, context.cacheDir)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot read selected file.")
            input.use { source -> file.outputStream().use { source.copyTo(it) } }
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }
}
