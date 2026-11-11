package org.acoustixaudio.a2rack

import kotlin.math.log10

data class AudioDiagnostics(
    val streamId: Long = 0,
    val streamsOpen: Boolean = false,
    val running: Boolean = false,
    val muted: Boolean = true,
    val inputDeviceId: Int? = null,
    val outputDeviceId: Int? = null,
    val inputName: String = "Unavailable",
    val outputName: String = "Unavailable",
    val sampleRate: Int? = null,
    val inputBufferFrames: Int? = null,
    val outputBufferFrames: Int? = null,
    val outputBurstFrames: Int? = null,
    val callbackFrames: Int? = null,
    val inputXruns: Long? = null,
    val outputXruns: Long? = null,
    val inputLatencyMs: Double? = null,
    val outputLatencyMs: Double? = null,
    val inputPeak: Float = 0f,
    val outputPeak: Float = 0f,
    val inputClipping: Boolean = false,
    val outputClipping: Boolean = false,
    val shortReads: Long = 0,
    val namLoaded: Boolean = false,
    val irLoaded: Boolean = false,
    val lastEngineError: String? = null,
    val collectionError: String? = null
) {
    val combinedLatencyMs: Double?
        get() = if (inputLatencyMs != null && outputLatencyMs != null)
            inputLatencyMs + outputLatencyMs else null

    fun silenced() = copy(running = false, muted = true,
        inputPeak = 0f, outputPeak = 0f, inputClipping = false, outputClipping = false)

    companion object {
        /** JNI layout v1; negative counters/latency mean unsupported, never zero. */
        internal fun decode(values: DoubleArray): AudioDiagnostics {
            require(values.size == 23 && values[0] == 1.0) { "Unsupported native diagnostics snapshot." }
            fun count(index: Int): Long? = values[index].takeIf {
                it.isFinite() && it >= 0 && it <= 0xffffffffL.toDouble() && it % 1.0 == 0.0
            }?.toLong()
            fun positive(index: Int): Int? = count(index)?.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
            fun latency(index: Int): Double? = values[index].takeIf { it.isFinite() && it >= 0 }
            fun peak(index: Int): Float = values[index].takeIf { it.isFinite() && it >= 0 }
                ?.coerceAtMost(Float.MAX_VALUE.toDouble())?.toFloat() ?: 0f
            return AudioDiagnostics(
                streamId = count(4) ?: 0,
                streamsOpen = values[1] == 1.0,
                running = values[2] == 1.0,
                muted = values[3] != 0.0,
                inputDeviceId = positive(5), outputDeviceId = positive(6),
                sampleRate = positive(7), inputBufferFrames = positive(8),
                outputBufferFrames = positive(9), outputBurstFrames = positive(10),
                callbackFrames = positive(11), inputXruns = count(12), outputXruns = count(13),
                inputLatencyMs = latency(14), outputLatencyMs = latency(15),
                inputPeak = peak(16), outputPeak = peak(17),
                inputClipping = values[18] == 1.0, outputClipping = values[19] == 1.0,
                shortReads = count(20) ?: 0,
                namLoaded = values[21] == 1.0, irLoaded = values[22] == 1.0
            )
        }

        fun peakDb(peak: Float): Double? =
            if (peak.isFinite() && peak > 0) 20.0 * log10(peak.toDouble()) else null

        fun meterFraction(peak: Float): Float =
            (((peakDb(peak) ?: -60.0) + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
    }
}

/** Main-thread presentation state; meter holds use a monotonic clock supplied by the caller. */
internal class DiagnosticsTracker {
    private var streamId = 0L
    private var inputClipUntil = Long.MIN_VALUE
    private var outputClipUntil = Long.MIN_VALUE
    private var lastError: String? = null

    fun rememberError(message: String?) {
        if (!message.isNullOrBlank()) lastError = message
    }

    fun pause() {
        inputClipUntil = Long.MIN_VALUE
        outputClipUntil = Long.MIN_VALUE
    }

    fun update(snapshot: AudioDiagnostics, error: String?, nowMs: Long): AudioDiagnostics {
        rememberError(error)
        if (snapshot.streamId != streamId) {
            pause()
            streamId = snapshot.streamId
        }
        val inputLive = snapshot.streamsOpen && snapshot.running
        val outputLive = inputLive && !snapshot.muted
        if (!inputLive) inputClipUntil = Long.MIN_VALUE
        else if (snapshot.inputClipping) inputClipUntil = nowMs + 1000
        if (!outputLive) outputClipUntil = Long.MIN_VALUE
        else if (snapshot.outputClipping) outputClipUntil = nowMs + 1000
        return snapshot.copy(
            inputPeak = if (inputLive) snapshot.inputPeak else 0f,
            outputPeak = if (outputLive) snapshot.outputPeak else 0f,
            inputClipping = inputLive && nowMs < inputClipUntil,
            outputClipping = outputLive && nowMs < outputClipUntil,
            lastEngineError = lastError, collectionError = null
        )
    }
}
