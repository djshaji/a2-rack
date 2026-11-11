package org.acoustixaudio.a2rack

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class A2RackState(
    val inputGain: Float = 0.5f,
    val outputGain: Float = 0.5f,
    val isAmpBypassed: Boolean = false,
    val isCabBypassed: Boolean = false,
    val isA2Full: Boolean = false,
    val namFilePath: String? = null,
    val wavFilePath: String? = null
)

class A2RackViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(A2RackState())
    val uiState: StateFlow<A2RackState> = _uiState.asStateFlow()

    init {
        AudioEngine.startEngine()
        // Initialize DSP states to defaults
        val initialState = _uiState.value
        AudioEngine.setInputGain(initialState.inputGain)
        AudioEngine.setOutputGain(initialState.outputGain)
        AudioEngine.setNamBypass(initialState.isAmpBypassed)
        AudioEngine.setIrBypass(initialState.isCabBypassed)
    }

    override fun onCleared() {
        super.onCleared()
        AudioEngine.stopEngine()
    }

    fun updateInputGain(gain: Float) {
        _uiState.update { it.copy(inputGain = gain) }
        AudioEngine.setInputGain(gain)
    }

    fun updateOutputGain(gain: Float) {
        _uiState.update { it.copy(outputGain = gain) }
        AudioEngine.setOutputGain(gain)
    }

    fun toggleAmpBypass(bypassed: Boolean) {
        _uiState.update { it.copy(isAmpBypassed = bypassed) }
        AudioEngine.setNamBypass(bypassed)
    }

    fun toggleCabBypass(bypassed: Boolean) {
        _uiState.update { it.copy(isCabBypassed = bypassed) }
        AudioEngine.setIrBypass(bypassed)
    }

    fun toggleA2Mode(isFull: Boolean) {
        _uiState.update { it.copy(isA2Full = isFull) }
        // Depending on what A2 mode does, might need an engine method, but we will leave it
    }

    fun loadNamFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            val tempFile = copyUriToTempFile(context, uri, "nam_model", ".nam")
            if (tempFile != null) {
                _uiState.update { it.copy(namFilePath = tempFile.absolutePath) }
                AudioEngine.loadNamModel(tempFile.absolutePath)
            }
        }
    }

    fun loadWavFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            val tempFile = copyUriToTempFile(context, uri, "cab_ir", ".wav")
            if (tempFile != null) {
                _uiState.update { it.copy(wavFilePath = tempFile.absolutePath) }
                AudioEngine.loadIrModel(tempFile.absolutePath)
            }
        }
    }

    fun exportPreset(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                val jsonObject = JSONObject().apply {
                    put("inputGain", state.inputGain.toDouble())
                    put("outputGain", state.outputGain.toDouble())
                    put("isAmpBypassed", state.isAmpBypassed)
                    put("isCabBypassed", state.isCabBypassed)
                    put("isA2Full", state.isA2Full)
                    put("namFilePath", state.namFilePath ?: JSONObject.NULL)
                    put("wavFilePath", state.wavFilePath ?: JSONObject.NULL)
                }
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(jsonObject.toString(4).toByteArray())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun importPreset(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                } ?: return@launch

                val jsonObject = JSONObject(jsonString)
                val inputGain = jsonObject.optDouble("inputGain", 0.5).toFloat()
                val outputGain = jsonObject.optDouble("outputGain", 0.5).toFloat()
                val isAmpBypassed = jsonObject.optBoolean("isAmpBypassed", false)
                val isCabBypassed = jsonObject.optBoolean("isCabBypassed", false)
                val isA2Full = jsonObject.optBoolean("isA2Full", false)

                val namFilePath = if (jsonObject.isNull("namFilePath")) null else jsonObject.optString("namFilePath")
                val wavFilePath = if (jsonObject.isNull("wavFilePath")) null else jsonObject.optString("wavFilePath")

                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            inputGain = inputGain,
                            outputGain = outputGain,
                            isAmpBypassed = isAmpBypassed,
                            isCabBypassed = isCabBypassed,
                            isA2Full = isA2Full,
                            namFilePath = namFilePath,
                            wavFilePath = wavFilePath
                        )
                    }

                    AudioEngine.setInputGain(inputGain)
                    AudioEngine.setOutputGain(outputGain)
                    AudioEngine.setNamBypass(isAmpBypassed)
                    AudioEngine.setIrBypass(isCabBypassed)
                    if (namFilePath != null) AudioEngine.loadNamModel(namFilePath)
                    if (wavFilePath != null) AudioEngine.loadIrModel(wavFilePath)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun copyUriToTempFile(context: Context, uri: Uri, prefix: String, suffix: String): File? {
        return withContext(Dispatchers.IO) {
            try {
                val tempFile = File.createTempFile(prefix, suffix, context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}
