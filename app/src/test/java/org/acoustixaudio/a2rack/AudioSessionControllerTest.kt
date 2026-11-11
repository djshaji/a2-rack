package org.acoustixaudio.a2rack

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioSessionControllerTest {
    private class Backend : AudioBackend {
        var running = false
        var outputMuted = true
        var starts = 0
        var stops = 0
        var unmutes = 0
        var recoverable = false
        var onCheck: (() -> Unit)? = null
        val devices = mutableListOf<Pair<Int, Int>>()
        override fun startEngine(): Boolean { starts++; running = true; return true }
        override fun stopEngine() { stops++; running = false }
        override fun setMuted(muted: Boolean) { this.outputMuted = muted; if (!muted) unmutes++ }
        override fun setDeviceIds(inputId: Int, outputId: Int) { devices += inputId to outputId }
        override fun diagnostics() = AudioDiagnostics(streamsOpen = running, running = running, muted = !running)
        override fun isRecoverableError() = recoverable
        override fun isRunning(): Boolean { onCheck?.invoke(); return running }
        override fun lastError() = "Device disconnected"
        override fun loadNamModel(path: String) = true
        override fun loadIrModel(path: String) = true
        override fun setInputGain(gain: Float) {}
        override fun setOutputGain(gain: Float) {}
        override fun setNamBypass(bypass: Boolean) {}
        override fun setIrBypass(bypass: Boolean) {}
    }

    private class Harness(scope: TestScope, restore: suspend () -> Unit = {}) {
        val backend = Backend()
        var restores = 0
        var flushes = 0
        var state = AudioSessionState()
        val controller = AudioSessionController(backend, StandardTestDispatcher(scope.testScheduler),
            scope.backgroundScope, Mutex(),
            restore = { restores++; restore() }, flush = { flushes++ }, changed = { state = it })
    }

    @Test fun startupRequiresBothVisibilityAndPermission() = runTest {
        val h = Harness(this)
        h.controller.start()
        h.controller.permission(true)
        runCurrent()
        assertEquals(0, h.backend.starts)
        h.controller.foreground(true, true)
        runCurrent()
        assertTrue(h.state.running)
        assertFalse(h.backend.outputMuted)
        assertEquals(1, h.restores)
        h.controller.permission(false)
        assertTrue(h.backend.outputMuted)
        runCurrent()
        assertFalse(h.backend.running)
    }

    @Test fun backgroundingDuringRestoreCannotUnmuteOrPublishRunningState() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = Harness(this) { gate.await() }
        h.controller.foreground(true, true)
        runCurrent()
        assertEquals(1, h.backend.starts)
        h.controller.foreground(false, true)
        assertTrue(h.backend.outputMuted)
        gate.complete(Unit)
        runCurrent()
        assertEquals(0, h.backend.unmutes)
        assertFalse(h.state.running)
        assertFalse(h.backend.running)
        assertFalse(h.state.transitioning)
    }

    @Test fun aLateHealthCheckCannotUnmuteAfterStop() = runTest {
        val h = Harness(this)
        h.backend.onCheck = {
            h.backend.onCheck = null
            h.controller.stop()
        }
        h.controller.foreground(true, true)
        runCurrent()
        assertEquals(0, h.backend.unmutes)
        assertFalse(h.backend.running)
        assertFalse(h.state.running)
    }

    @Test fun manualStopSurvivesBackgroundingAndReturningToForeground() = runTest {
        val h = Harness(this)
        h.controller.foreground(true, true)
        runCurrent()
        h.controller.stop()
        assertTrue(h.backend.outputMuted)
        runCurrent()
        h.controller.foreground(false, true)
        h.controller.foreground(true, true)
        runCurrent()
        assertEquals(1, h.backend.starts)
        assertFalse(h.state.running)
        h.controller.start()
        runCurrent()
        assertEquals(2, h.backend.starts)
        assertTrue(h.state.running)
    }

    @Test fun foregroundReturnRestoresAPreviouslyRunningSession() = runTest {
        val h = Harness(this)
        h.controller.foreground(true, true)
        runCurrent()
        h.controller.foreground(false, true)
        runCurrent()
        assertFalse(h.backend.running)
        assertTrue(h.flushes > 0)
        h.controller.foreground(true, true)
        runCurrent()
        assertEquals(2, h.restores)
        assertTrue(h.state.running)
    }

    @Test fun unpluggingExternalAudioWaitsForReconnectWithoutUsingSpeaker() = runTest {
        val h = Harness(this)
        h.controller.route(AudioRoute(10, 11, "USB", external = true))
        h.controller.foreground(true, true)
        runCurrent()
        assertEquals(10 to 11, h.backend.devices.last())
        h.controller.route(AudioRoute(1, 2, "Device speaker"))
        assertTrue(h.backend.outputMuted)
        advanceTimeBy(400)
        runCurrent()
        assertFalse(h.state.running)
        assertEquals(1, h.backend.starts)
        h.controller.route(AudioRoute(20, 21, "USB reconnected", external = true))
        advanceTimeBy(400)
        runCurrent()
        assertTrue(h.state.running)
        assertEquals(listOf(10 to 11, 20 to 21), h.backend.devices)
        assertEquals(2, h.restores)
    }

    @Test fun stopWhileWaitingPreventsRestartWhenTheInterfaceReturns() = runTest {
        val h = Harness(this)
        h.controller.route(AudioRoute(10, 11, "USB", external = true))
        h.controller.foreground(true, true)
        runCurrent()
        h.controller.route(AudioRoute(1, 2, "Speaker"))
        advanceTimeBy(400)
        runCurrent()
        assertTrue(h.state.requested)
        assertFalse(h.state.running)
        h.controller.stop()
        runCurrent()
        assertFalse(h.state.requested)
        h.controller.route(AudioRoute(20, 21, "USB", external = true))
        advanceTimeBy(400)
        runCurrent()
        assertEquals(1, h.backend.starts)
        assertFalse(h.state.running)
    }

    @Test fun noisyBroadcastMutesBeforeDeviceListChanges() = runTest {
        val h = Harness(this)
        val route = AudioRoute(10, 11, "Headset", external = true)
        h.controller.route(route)
        h.controller.foreground(true, true)
        runCurrent()
        h.controller.becomingNoisy()
        assertTrue(h.backend.outputMuted)
        h.controller.route(route)
        runCurrent()
        assertFalse(h.state.running)
        assertEquals(1, h.backend.starts)
    }

    @Test fun disconnectionRecoveryIsBoundedAndReloadsModels() = runTest {
        val h = Harness(this)
        h.controller.foreground(true, true)
        runCurrent()
        repeat(3) {
            h.backend.running = false
            h.backend.recoverable = true
            advanceTimeBy(250)
            runCurrent()
            advanceTimeBy(400)
            runCurrent()
        }
        assertEquals(3, h.backend.starts)
        assertEquals(3, h.restores)
        assertFalse(h.state.running)
        assertTrue(h.backend.outputMuted)
        assertNotNull(h.state.error)
    }

    @Test fun restorationFailureStaysMutedAndDoesNotLoop() = runTest {
        val h = Harness(this) { error("Model needs 48000 Hz; stream is 44100 Hz") }
        h.controller.foreground(true, true)
        runCurrent()
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(1, h.backend.starts)
        assertTrue(h.backend.outputMuted)
        assertFalse(h.state.running)
        assertTrue(h.state.error!!.contains("48000"))
    }
}
