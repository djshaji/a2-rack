package org.acoustixaudio.a2rack

object AudioEngine {
    init {
        System.loadLibrary("a2rack")
    }

    external fun startEngine(): Boolean
    external fun stopEngine()

    external fun loadNamModel(path: String): Boolean
    external fun loadIrModel(path: String): Boolean

    external fun setInputGain(gain: Float)
    external fun setOutputGain(gain: Float)

    external fun setNamBypass(bypass: Boolean)
    external fun setIrBypass(bypass: Boolean)
}
