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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val audioRequested: Boolean = true,
    val isBusy: Boolean = false,
    val isTransitioning: Boolean = false,
    val audioStatus: String = "Audio stopped",
    val routeLabel: String = "System audio",
    val errorMessage: String? = null
) {
    fun preset() = RackPreset(inputGain = inputGain, outputGain = outputGain,
        isAmpBypassed = isAmpBypassed, isCabBypassed = isCabBypassed,
        isA2Full = isA2Full, nam = namAsset, ir = irAsset)
}

class A2RackViewModel internal constructor(
    private val backend: AudioBackend,
    private val engineDispatcher: CoroutineDispatcher,
    private var repository: PresetRepository? = null,
    private val diagnosticsClock: () -> Long = { System.nanoTime() / 1_000_000 }
) : ViewModel() {
    constructor() : this(AudioEngine, AudioEngine.dispatcher)

    private val _uiState = MutableStateFlow(A2RackState())
    val uiState = _uiState.asStateFlow()
    private val _diagnostics = MutableStateFlow(AudioDiagnostics())
    val diagnostics = _diagnostics.asStateFlow()
    private val diagnosticsTracker = DiagnosticsTracker()
    private val deviceNames = mutableMapOf<Int, String>()
    private var diagnosticsJob: Job? = null
    private var saveJob: Job? = null
    private var sessionLoaded = false
    private val operationMutex = Mutex()
    private val visible = MutableStateFlow(false)
    private val session = AudioSessionController(
        backend, engineDispatcher, viewModelScope, operationMutex,
        restore = { restoreSession() },
        flush = { if (sessionLoaded) saveSession() },
        changed = { next ->
            _uiState.update {
                it.copy(isEngineRunning = next.running, audioRequested = next.requested,
                    isTransitioning = next.transitioning,
                    audioStatus = next.status, routeLabel = next.route,
                    errorMessage = next.error ?: if (next.transitioning) null else it.errorMessage)
            }
        },
        onEngineError = ::rememberEngineError
    )

    private fun rememberEngineError(message: String) {
        diagnosticsTracker.rememberError(message)
        _diagnostics.update { it.copy(lastEngineError = message) }
    }

    private fun startDiagnostics() {
        if (diagnosticsJob?.isActive == true) return
        diagnosticsJob = viewModelScope.launch {
            while (visible.value) {
                try {
                    val (snapshot, error) = withContext(engineDispatcher) {
                        backend.diagnostics() to backend.lastError()
                    }
                    if (!visible.value) break
                    val previous = _diagnostics.value
                    fun name(id: Int?, previousId: Int?, previousName: String): String =
                        deviceNames[id] ?: if (id != null && id == previousId) previousName
                        else id?.let { "Device #$it" } ?: "Unavailable"
                    _diagnostics.value = diagnosticsTracker.update(snapshot, error, diagnosticsClock()).copy(
                        inputName = name(snapshot.inputDeviceId, previous.inputDeviceId, previous.inputName),
                        outputName = name(snapshot.outputDeviceId, previous.outputDeviceId, previous.outputName)
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _diagnostics.update { it.silenced().copy(collectionError = e.message ?: "Diagnostics unavailable.") }
                }
                delay(250)
            }
        }
    }

    fun initializeStorage(context: Context) {
        if (repository == null) {
            val app = context.applicationContext
            repository = PresetRepository(File(app.filesDir, "rack-library"), app.cacheDir)
        }
    }

    fun startAudio() = session.start()
    fun stopAudio() = session.stop()
    fun onRouteChanged(route: AudioRoute) {
        if (route.inputId > 0) deviceNames[route.inputId] = route.inputName
        if (route.outputId > 0) deviceNames[route.outputId] = route.outputName
        session.route(route)
    }
    fun onAudioBecomingNoisy() = session.becomingNoisy()

    fun onForegroundChanged(isVisible: Boolean, permissionGranted: Boolean) {
        visible.value = isVisible
        session.foreground(isVisible, permissionGranted)
        if (isVisible) startDiagnostics()
        else {
            diagnosticsJob?.cancel()
            diagnosticsJob = null
            diagnosticsTracker.pause()
            _diagnostics.update { it.silenced().copy(streamsOpen = false) }
        }
    }

    fun permissionGranted() = session.permission(true)
    fun permissionDenied() {
        session.permission(false)
        _uiState.update { it.copy(errorMessage = "Microphone permission is required to start audio.") }
    }

    private suspend fun restoreSession() {
        var restoreError: String? = null
        val preset = withContext(engineDispatcher) {
            if (!sessionLoaded) {
                try {
                    repository?.readSession() ?: _uiState.value.preset()
                } catch (e: Exception) {
                    restoreError = "Saved session could not be read: ${e.message}"
                    _uiState.value.preset()
                }
            } else _uiState.value.preset()
        }
        applyPreset(preset)
        sessionLoaded = true
        restoreError?.let { error -> _uiState.update { it.copy(errorMessage = error) } }
    }

    override fun onCleared() {
        session.muteForShutdown()
        diagnosticsJob?.cancel()
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
        if (_uiState.value.isBusy || _uiState.value.isTransitioning) return
        val value = validGain(gain)
        _uiState.update { it.copy(inputGain = value) }
        backend.setInputGain(value)
        scheduleSave()
    }

    fun updateOutputGain(gain: Float) {
        if (_uiState.value.isBusy || _uiState.value.isTransitioning) return
        val value = validGain(gain)
        _uiState.update { it.copy(outputGain = value) }
        backend.setOutputGain(value)
        scheduleSave()
    }

    fun toggleAmpBypass(bypassed: Boolean) {
        if (_uiState.value.isBusy || _uiState.value.isTransitioning) return
        _uiState.update { it.copy(isAmpBypassed = bypassed) }
        backend.setNamBypass(bypassed)
        scheduleSave()
    }

    fun toggleCabBypass(bypassed: Boolean) {
        if (_uiState.value.isBusy || _uiState.value.isTransitioning) return
        _uiState.update { it.copy(isCabBypassed = bypassed) }
        backend.setIrBypass(bypassed)
        scheduleSave()
    }

    fun toggleA2Mode(isFull: Boolean) {
        if (_uiState.value.isBusy || _uiState.value.isTransitioning) return
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
        val resolver = context.applicationContext.contentResolver
        viewModelScope.launch {
            visible.first { it }
            uiState.first { !it.isTransitioning }
            operationMutex.withLock {
                if (!beginLoading()) return@withLock
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
    }

    private fun beginLoading(): Boolean {
        if (_uiState.value.isBusy || _uiState.value.isTransitioning) return false
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
        // Document results may arrive while foreground restoration is running.
        val resolver = context.applicationContext.contentResolver
        viewModelScope.launch {
            visible.first { it }
            uiState.first { !it.isTransitioning }
            operationMutex.withLock {
                val preset = _uiState.value.preset()
                _uiState.update { it.copy(isBusy = true, errorMessage = null) }
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
    }

    fun importPreset(context: Context, uri: Uri) {
        initializeStorage(context)
        val resolver = context.applicationContext.contentResolver
        viewModelScope.launch {
            visible.first { it }
            uiState.first { !it.isTransitioning }
            operationMutex.withLock {
                if (!beginLoading()) return@withLock
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
                errorMessage = if (loaded.first.issue != null || loaded.second.issue != null)
                    "Some preset models are unavailable. Replace the files shown below." else null)
        }
    }
}
