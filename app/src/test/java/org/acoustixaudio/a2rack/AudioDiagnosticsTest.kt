package org.acoustixaudio.a2rack

import org.junit.Assert.*
import org.junit.Test

class AudioDiagnosticsTest {
    private fun packet() = doubleArrayOf(
        1.0, 1.0, 1.0, 0.0, 7.0, 10.0, 20.0, 48000.0,
        192.0, 384.0, 96.0, 128.0, 0.0, 2.0, 3.0, 8.0,
        0.5, 1.2, 0.0, 1.0, 4.0, 1.0, 0.0
    )

    @Test fun nativeSnapshotDecodesStreamSettingsCountersAndLevels() {
        val data = AudioDiagnostics.decode(packet())
        assertTrue(data.streamsOpen)
        assertTrue(data.running)
        assertFalse(data.muted)
        assertEquals(7L, data.streamId)
        assertEquals(10, data.inputDeviceId)
        assertEquals(20, data.outputDeviceId)
        assertEquals(48000, data.sampleRate)
        assertEquals(192, data.inputBufferFrames)
        assertEquals(384, data.outputBufferFrames)
        assertEquals(96, data.outputBurstFrames)
        assertEquals(128, data.callbackFrames)
        assertEquals(0L, data.inputXruns)
        assertEquals(2L, data.outputXruns)
        assertEquals(11.0, data.combinedLatencyMs!!, 0.001)
        assertEquals(0.5f, data.inputPeak)
        assertEquals(1.2f, data.outputPeak)
        assertTrue(data.outputClipping)
        assertEquals(4L, data.shortReads)
        assertTrue(data.namLoaded)
        assertFalse(data.irLoaded)
    }

    @Test fun unknownValuesRemainUnavailableInsteadOfClaimingZeroLatencyOrXruns() {
        val values = packet().apply {
            this[12] = -1.0
            this[14] = -1.0
            this[15] = Double.NaN
            this[7] = 0.0
        }
        val data = AudioDiagnostics.decode(values)
        assertNull(data.inputXruns)
        assertEquals(2L, data.outputXruns)
        assertNull(data.inputLatencyMs)
        assertNull(data.outputLatencyMs)
        assertNull(data.combinedLatencyMs)
        assertNull(data.sampleRate)
    }

    @Test fun corruptMeasurementsCannotCreateInvalidProgressOrCounters() {
        val data = AudioDiagnostics.decode(packet().apply {
            this[5] = Double.POSITIVE_INFINITY
            this[8] = -10.0
            this[13] = 1.5
            this[16] = Double.NaN
            this[17] = -1.0
            this[20] = -1.0
        })
        assertNull(data.inputDeviceId)
        assertNull(data.inputBufferFrames)
        assertNull(data.outputXruns)
        assertEquals(0f, data.inputPeak)
        assertEquals(0f, data.outputPeak)
        assertEquals(0L, data.shortReads)
    }

    @Test fun wrongSnapshotVersionOrLengthIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { AudioDiagnostics.decode(DoubleArray(2)) }
        assertThrows(IllegalArgumentException::class.java) {
            AudioDiagnostics.decode(packet().apply { this[0] = 2.0 })
        }
    }

    @Test fun dbMetersHandleSilenceAndPreLimiterOverload() {
        assertNull(AudioDiagnostics.peakDb(0f))
        assertEquals(0f, AudioDiagnostics.meterFraction(Float.NaN))
        assertEquals(0f, AudioDiagnostics.meterFraction(0.00001f))
        assertEquals(-6.0206, AudioDiagnostics.peakDb(0.5f)!!, 0.0001)
        assertEquals(1f, AudioDiagnostics.meterFraction(2f))
        assertEquals(6.0206, AudioDiagnostics.peakDb(2f)!!, 0.0001)
    }

    @Test fun clippingIsHeldForOneSecondThenExpires() {
        val tracker = DiagnosticsTracker()
        val snapshot = AudioDiagnostics.decode(packet())
        assertTrue(tracker.update(snapshot, "", 0).outputClipping)
        val quiet = snapshot.copy(outputClipping = false, outputPeak = 0.1f)
        assertTrue(tracker.update(quiet, "", 999).outputClipping)
        assertFalse(tracker.update(quiet, "", 1000).outputClipping)
    }

    @Test fun muteStopPauseAndNewStreamsClearStaleClipping() {
        val tracker = DiagnosticsTracker()
        val clip = AudioDiagnostics.decode(packet()).copy(inputClipping = true)
        tracker.update(clip, "", 0)
        val muted = tracker.update(clip.copy(muted = true), "", 100)
        assertEquals(0f, muted.outputPeak)
        assertFalse(muted.outputClipping)
        assertTrue(muted.inputClipping)
        val next = tracker.update(clip.copy(streamId = 8, inputClipping = false, outputClipping = false), "", 200)
        assertFalse(next.inputClipping)
        assertFalse(next.outputClipping)
        val stopped = tracker.update(clip.copy(running = false), "", 300)
        assertEquals(0f, stopped.inputPeak)
        assertFalse(stopped.inputClipping)
        tracker.update(clip, "", 400)
        tracker.pause()
        assertFalse(tracker.update(clip.copy(inputClipping = false, outputClipping = false), "", 500).outputClipping)
    }

    @Test fun latestEngineErrorSurvivesRecoveryUntilAnotherErrorIsRecorded() {
        val tracker = DiagnosticsTracker()
        val snapshot = AudioDiagnostics.decode(packet())
        assertEquals("Disconnected", tracker.update(snapshot, "Disconnected", 0).lastEngineError)
        assertEquals("Disconnected", tracker.update(snapshot.copy(streamId = 8), "", 250).lastEngineError)
        tracker.rememberError("Unsupported sample rate")
        assertEquals("Unsupported sample rate", tracker.update(snapshot, null, 500).lastEngineError)
    }

    @Test fun modelStatusNeverClaimsAnInactiveOrMissingModelIsActive() {
        assertEquals("Inactive — Amp", modelDiagnosticStatus("Amp", null, true, false, false))
        assertEquals("Unavailable — Amp", modelDiagnosticStatus("Amp", "Missing", false, false, true))
        assertEquals("Bypassed — Amp", modelDiagnosticStatus("Amp", null, true, true, true))
        assertEquals("Active — Amp", modelDiagnosticStatus("Amp", null, true, false, true))
        assertEquals("Not selected", modelDiagnosticStatus(null, null, false, false, true))
    }
}
