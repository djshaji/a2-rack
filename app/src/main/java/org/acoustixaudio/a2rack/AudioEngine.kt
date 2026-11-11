package org.acoustixaudio.a2rack

internal interface AudioBackend {
    fun startEngine(): Boolean
    fun stopEngine()
    fun isRunning(): Boolean
    fun lastError(): String
    fun loadNamModel(path: String): Boolean
    fun loadIrModel(path: String): Boolean
    fun setInputGain(gain: Float)
    fun setOutputGain(gain: Float)
    fun setNamBypass(bypass: Boolean)
    fun setIrBypass(bypass: Boolean)
}

internal object AudioEngine : AudioBackend {
    // Shared ordering also covers shutdown when a ViewModel is cleared.
    internal val dispatcher = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)

    init {
        System.loadLibrary("a2rack")
    }

    external override fun startEngine(): Boolean
    external override fun stopEngine()
    external override fun isRunning(): Boolean
    external override fun lastError(): String

    external override fun loadNamModel(path: String): Boolean
    external override fun loadIrModel(path: String): Boolean

    external override fun setInputGain(gain: Float)
    external override fun setOutputGain(gain: Float)

    external override fun setNamBypass(bypass: Boolean)
    external override fun setIrBypass(bypass: Boolean)
}
