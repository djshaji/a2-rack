package org.acoustixaudio.a2rack

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class A2RackState(
    val inputGain: Float = 0.5f,
    val outputGain: Float = 0.5f,
    val isAmpBypassed: Boolean = false,
    val isCabBypassed: Boolean = false,
    val isA2Full: Boolean = false,
    val namFilePath: String? = null,
    val wavFilePath: String? = null,
    val namAsset: AssetRef? = null,
    val irAsset: AssetRef? = null,
    val namIssue: String? = null,
    val irIssue: String? = null,
    val isEngineRunning: Boolean = false,
    val isBusy: Boolean = false,
    val errorMessage: String? = null
) {
    fun preset() = RackPreset(inputGain = inputGain, outputGain = outputGain,
        isAmpBypassed = isAmpBypassed, isCabBypassed = isCabBypassed,
        isA2Full = isA2Full, nam = namAsset, ir = irAsset)
}

class A2RackViewModel internal constructor(
    private val backend: AudioBackend,
    private val engineDispatcher: CoroutineDispatcher,
    private var repository: PresetRepository? = null
) : ViewModel() {
    constructor() : this(AudioEngine, AudioEngine.dispatcher)

    private val _uiState = MutableStateFlow(A2RackState())
    val uiState = _uiState.asStateFlow()
    private var monitor: Job? = null
    private var saveJob: Job? = null
    private var sessionLoaded = false

    fun initializeStorage(context: Context) {
        if (repository == null) {
            val app = context.applicationContext
            repository = PresetRepository(File(app.filesDir, "rack-library"), app.cacheDir)
        }
    }

    fun startAudio() {
        if (_uiState.value.isBusy || _uiState.value.isEngineRunning) return
        saveJob?.cancel()
        _uiState.update { it.copy(isBusy = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val preset = withContext(engineDispatcher) {
                    // Avoid playing a dry or partially restored chain during startup.
                    backend.setOutputGain(0f)
                    check(backend.startEngine()) { backend.lastError().ifBlank { "Cannot start audio." } }
                    if (!sessionLoaded) repository?.readSession() ?: _uiState.value.preset()
                    else _uiState.value.preset()
                }
                sessionLoaded = true
                applyPreset(preset)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val running = withContext(engineDispatcher) {
                    backend.setOutputGain(_uiState.value.outputGain)
                    backend.isRunning()
                }
                // A bad saved document must not prevent subsequent edits from being saved.
                if (running) sessionLoaded = true
                _uiState.update { it.copy(isEngineRunning = running, errorMessage = e.message ?: "Session restoration failed.") }
            } finally {
                _uiState.update { it.copy(isBusy = false) }
                if (_uiState.value.isEngineRunning) monitorEngine()
            }
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
        saveJob?.cancel()
        val preset = _uiState.value.preset()
        val save = sessionLoaded
        // Flush the final snapshot on the shared worker before shutting down.
        CoroutineScope(engineDispatcher).launch {
            try {
                if (save) repository?.saveSession(preset)
            } catch (e: Exception) {
                android.util.Log.e("A2Rack", "Cannot save final session", e)
            } finally { backend.stopEngine() }
        }
        super.onCleared()
    }

    private fun validGain(gain: Float) = if (gain.isFinite()) gain.coerceIn(0f, 1f) else 0f

    fun updateInputGain(gain: Float) {
        if (_uiState.value.isBusy) return
        val value = validGain(gain)
        _uiState.update { it.copy(inputGain = value) }
        backend.setInputGain(value)
        scheduleSave()
    }

    fun updateOutputGain(gain: Float) {
        if (_uiState.value.isBusy) return
        val value = validGain(gain)
        _uiState.update { it.copy(outputGain = value) }
        backend.setOutputGain(value)
        scheduleSave()
    }

    fun toggleAmpBypass(bypassed: Boolean) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isAmpBypassed = bypassed) }
        backend.setNamBypass(bypassed)
        scheduleSave()
    }

    fun toggleCabBypass(bypassed: Boolean) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isCabBypassed = bypassed) }
        backend.setIrBypass(bypassed)
        scheduleSave()
    }

    fun toggleA2Mode(isFull: Boolean) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(isA2Full = isFull) }
        scheduleSave()
    }

    private fun scheduleSave() {
        if (!sessionLoaded || repository == null) return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(300)
            saveSession()
        }
    }

    private suspend fun saveSession() {
        val preset = _uiState.value.preset()
        try {
            withContext(engineDispatcher) { repository?.saveSession(preset) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "Session could not be saved: ${e.message}") }
        }
    }

    fun loadNamFile(context: Context, uri: Uri) = loadFile(context, uri, AssetKind.NAM)
    fun loadWavFile(context: Context, uri: Uri) = loadFile(context, uri, AssetKind.IR)

    private fun loadFile(context: Context, uri: Uri, kind: AssetKind) {
        initializeStorage(context)
        if (!beginLoading()) return
        val resolver = context.applicationContext.contentResolver
        viewModelScope.launch {
            try {
                val loaded = withContext(engineDispatcher) {
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        val column = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0 && it.moveToFirst()) it.getString(column) else null
                    } ?: "model.${kind.extension}"
                    val input = resolver.openInputStream(uri) ?: error("Cannot read selected file.")
                    val ref = repository!!.importAsset(input, name, kind)
                    val file = repository!!.resolve(ref, kind) ?: error("Imported model is unavailable.")
                    check(loadNative(kind, file.absolutePath)) { backend.lastError().ifBlank { "Cannot load this model." } }
                    ref to file.absolutePath
                }
                _uiState.update {
                    if (kind == AssetKind.NAM) it.copy(namAsset = loaded.first, namFilePath = loaded.second, namIssue = null)
                    else it.copy(irAsset = loaded.first, wavFilePath = loaded.second, irIssue = null)
                }
                sessionLoaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Model loading failed.") }
            } finally {
                if (sessionLoaded) saveSession()
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun beginLoading(): Boolean {
        if (_uiState.value.isBusy) return false
        if (!_uiState.value.isEngineRunning) {
            _uiState.update { it.copy(errorMessage = "Start audio before loading models or presets.") }
            return false
        }
        saveJob?.cancel()
        _uiState.update { it.copy(isBusy = true, errorMessage = null) }
        return true
    }

    fun exportPreset(context: Context, uri: Uri, portable: Boolean = true) {
        initializeStorage(context)
        if (_uiState.value.isBusy) return
        val preset = _uiState.value.preset()
        val resolver = context.applicationContext.contentResolver
        _uiState.update { it.copy(isBusy = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                withContext(engineDispatcher) {
                    val output = resolver.openOutputStream(uri) ?: error("Cannot open preset destination.")
                    output.use {
                        if (portable) repository!!.writeBundle(preset, it)
                        else repository!!.writeJson(preset, it)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Preset export failed.") }
            } finally { _uiState.update { it.copy(isBusy = false) } }
        }
    }

    fun importPreset(context: Context, uri: Uri) {
        initializeStorage(context)
        if (!beginLoading()) return
        val resolver = context.applicationContext.contentResolver
        viewModelScope.launch {
            try {
                val preset = withContext(engineDispatcher) {
                    val input = resolver.openInputStream(uri) ?: error("Cannot open preset.")
                    repository!!.readPreset(input)
                }
                applyPreset(preset)
                sessionLoaded = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = e.message ?: "Preset import failed.") }
            } finally {
                if (sessionLoaded) saveSession()
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    private fun loadNative(kind: AssetKind, path: String): Boolean =
        if (kind == AssetKind.NAM) backend.loadNamModel(path) else backend.loadIrModel(path)

    private data class LoadedSlot(val path: String? = null, val issue: String? = null)

    private fun restoreSlot(ref: AssetRef?, kind: AssetKind): LoadedSlot {
        if (ref == null) {
            check(loadNative(kind, "")) { backend.lastError() }
            return LoadedSlot()
        }
        val file = repository?.resolve(ref, kind)
        if (file != null && loadNative(kind, file.absolutePath)) return LoadedSlot(file.absolutePath)
        val issue = if (file == null) "Missing or damaged file: ${ref.name}. Choose a replacement."
                    else "Cannot load ${ref.name}: ${backend.lastError()}"
        // A missing/rejected target must not leave a previous preset's model active.
        check(loadNative(kind, "")) { backend.lastError() }
        return LoadedSlot(issue = issue)
    }

    private suspend fun applyPreset(preset: RackPreset) {
        preset.validate()
        val previousOutputGain = _uiState.value.outputGain
        val loaded = withContext(engineDispatcher) {
            backend.setOutputGain(0f)
            var applied = false
            try {
                val nam = restoreSlot(preset.nam, AssetKind.NAM)
                val ir = restoreSlot(preset.ir, AssetKind.IR)
                backend.setInputGain(preset.inputGain)
                backend.setNamBypass(preset.isAmpBypassed)
                backend.setIrBypass(preset.isCabBypassed)
                applied = true
                nam to ir
            } finally {
                backend.setOutputGain(if (applied) preset.outputGain else previousOutputGain)
            }
        }
        _uiState.update {
            it.copy(inputGain = preset.inputGain, outputGain = preset.outputGain,
                isAmpBypassed = preset.isAmpBypassed, isCabBypassed = preset.isCabBypassed,
                isA2Full = preset.isA2Full, namAsset = preset.nam, irAsset = preset.ir,
                namFilePath = loaded.first.path, wavFilePath = loaded.second.path,
                namIssue = loaded.first.issue, irIssue = loaded.second.issue,
                isEngineRunning = true,
                errorMessage = if (loaded.first.issue != null || loaded.second.issue != null)
                    "Some preset models are unavailable. Replace the files shown below." else null)
        }
    }
}
