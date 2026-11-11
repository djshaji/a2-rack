package org.acoustixaudio.a2rack

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class A2RackViewModelTest {
    private class Backend : AudioBackend {
        var starts = 0
        var stops = 0
        var canStart = true
        var running = false
        var acceptModels = true
        val namPaths = mutableListOf<String>()
        val irPaths = mutableListOf<String>()
        var input = 0f
        var output = 0f
        override fun startEngine(): Boolean {
            starts++
            running = canStart
            return running
        }
        override fun stopEngine() { stops++; running = false }
        override fun isRunning() = running
        override fun lastError() = "Audio device unavailable"
        override fun loadNamModel(path: String): Boolean { namPaths += path; return path.isEmpty() || acceptModels }
        override fun loadIrModel(path: String): Boolean { irPaths += path; return path.isEmpty() || acceptModels }
        override fun setInputGain(gain: Float) { input = gain }
        override fun setOutputGain(gain: Float) { output = gain }
        override fun setNamBypass(bypass: Boolean) {}
        override fun setIrBypass(bypass: Boolean) {}
    }

    @get:org.junit.Rule val temporary = org.junit.rules.TemporaryFolder()

    @Test fun savedModelsAndSettingsAreRestoredOnlyAfterSuccessfulStartup() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = PresetRepository(temporary.newFolder())
        val ref = repo.importAsset("model".byteInputStream(), "Original Amp.nam", AssetKind.NAM)
        val saved = RackPreset(inputGain = 0.2f, outputGain = 0.8f, isCabBypassed = true, nam = ref)
        repo.saveSession(saved)
        val backend = Backend().apply { canStart = false }
        val vm = A2RackViewModel(backend, dispatcher, repo)
        val store = ViewModelStore().apply { put("engine", vm) }
        try {
            vm.startAudio()
            runCurrent()
            assertTrue(backend.namPaths.isEmpty())
            assertEquals(saved, repo.readSession())
            backend.canStart = true
            vm.startAudio()
            runCurrent()
            assertEquals(saved, vm.uiState.value.preset())
            assertEquals(repo.resolve(ref, AssetKind.NAM)!!.absolutePath, backend.namPaths.last())
            assertEquals(0.2f, backend.input)
            assertNull(vm.uiState.value.namIssue)
            vm.updateInputGain(0.6f)
            advanceTimeBy(300)
            runCurrent()
            assertEquals(0.6f, repo.readSession()!!.inputGain)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun shutdownFlushesTheLastChangeBeforeItsDebounceExpires() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = PresetRepository(temporary.newFolder())
        val backend = Backend()
        val vm = A2RackViewModel(backend, dispatcher, repo)
        val store = ViewModelStore().apply { put("engine", vm) }
        try {
            vm.startAudio()
            runCurrent()
            vm.updateOutputGain(0.4f)
            vm.toggleAmpBypass(true)
            store.clear()
            runCurrent()
            assertEquals(0.4f, repo.readSession()!!.outputGain)
            assertTrue(repo.readSession()!!.isAmpBypassed)
            assertEquals(1, backend.stops)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun unavailableAssetsStayNamedAndTheirSlotsAreCleared() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val repo = PresetRepository(temporary.newFolder())
        val missing = AssetRef("a".repeat(64), "Missing Amp.nam")
        val rejected = repo.importAsset("cab".byteInputStream(), "Rejected Cab.wav", AssetKind.IR)
        repo.saveSession(RackPreset(nam = missing, ir = rejected))
        val backend = Backend().apply { acceptModels = false }
        val vm = A2RackViewModel(backend, dispatcher, repo)
        val store = ViewModelStore().apply { put("engine", vm) }
        try {
            vm.startAudio()
            runCurrent()
            assertTrue(vm.uiState.value.isEngineRunning)
            assertEquals("Missing Amp.nam", vm.uiState.value.namAsset!!.name)
            assertNotNull(vm.uiState.value.namIssue)
            assertNotNull(vm.uiState.value.irIssue)
            assertNull(vm.uiState.value.namFilePath)
            assertNull(vm.uiState.value.wavFilePath)
            assertEquals("", backend.namPaths.last())
            assertEquals("", backend.irPaths.last())
            assertEquals(missing, repo.readSession()!!.nam)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun startupIsExplicitAndDuplicateStartsAreIgnored() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val backend = Backend()
        val vm = A2RackViewModel(backend, dispatcher)
        val store = ViewModelStore().apply { put("engine", vm) }
        try {
            assertEquals(0, backend.starts)
            vm.startAudio()
            vm.startAudio()
            runCurrent()
            assertEquals(1, backend.starts)
            assertTrue(vm.uiState.value.isEngineRunning)
            assertFalse(vm.uiState.value.isBusy)
            assertEquals(0.5f, backend.input)
            vm.startAudio()
            assertEquals(1, backend.starts)
        } finally {
            store.clear()
            runCurrent()
            assertEquals(1, backend.stops)
            Dispatchers.resetMain()
        }
    }

    @Test fun failedStartupCanBeRetried() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val backend = Backend().apply { canStart = false }
        val vm = A2RackViewModel(backend, dispatcher)
        val store = ViewModelStore().apply { put("engine", vm) }
        try {
            vm.startAudio()
            runCurrent()
            assertFalse(vm.uiState.value.isEngineRunning)
            assertFalse(vm.uiState.value.isBusy)
            assertEquals("Audio device unavailable", vm.uiState.value.errorMessage)
            backend.canStart = true
            vm.startAudio()
            runCurrent()
            assertTrue(vm.uiState.value.isEngineRunning)
            assertNull(vm.uiState.value.errorMessage)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun disconnectionIsReportedWithoutRestartingAutomatically() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val backend = Backend()
        val vm = A2RackViewModel(backend, dispatcher)
        val store = ViewModelStore().apply { put("engine", vm) }
        try {
            vm.startAudio()
            runCurrent()
            backend.running = false
            advanceTimeBy(500)
            runCurrent()
            assertFalse(vm.uiState.value.isEngineRunning)
            assertEquals("Audio device unavailable", vm.uiState.value.errorMessage)
            assertEquals(1, backend.starts)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun permissionDenialDoesNotStartEngineAndGainsAreValidated() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val backend = Backend()
        val vm = A2RackViewModel(backend, dispatcher)
        val store = ViewModelStore().apply { put("engine", vm) }
        try {
            vm.permissionDenied()
            assertEquals(0, backend.starts)
            assertNotNull(vm.uiState.value.errorMessage)
            vm.updateInputGain(Float.NaN)
            vm.updateOutputGain(2f)
            assertEquals(0f, backend.input)
            assertEquals(1f, backend.output)
            assertEquals(backend.input, vm.uiState.value.inputGain)
            assertEquals(backend.output, vm.uiState.value.outputGain)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
