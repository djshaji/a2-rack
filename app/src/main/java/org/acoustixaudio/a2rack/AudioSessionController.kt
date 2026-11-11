package org.acoustixaudio.a2rack

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AudioRoute(
    val inputId: Int = 0,
    val outputId: Int = 0,
    val label: String = "System audio",
    val external: Boolean = false,
    val available: Boolean = true
)

internal data class AudioSessionState(
    val running: Boolean = false,
    val requested: Boolean = true,
    val transitioning: Boolean = false,
    val status: String = "Audio stopped",
    val route: String = "System audio",
    val error: String? = null
)

/** Main-thread policy; stream/DSP operations share the ViewModel's worker and mutex. */
internal class AudioSessionController(
    private val backend: AudioBackend,
    private val dispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
    private val mutex: Mutex,
    private val restore: suspend () -> Unit,
    private val flush: suspend () -> Unit,
    private val changed: (AudioSessionState) -> Unit
) {
    private var foreground = false
    private var permission = false
    private var wanted = true
    private var externalRequired = false
    private var noisy = false
    private var faulted = false
    private var retries = 0
    private var revision = 0L
    private var route = AudioRoute()
    private var monitor: Job? = null
    private var state = AudioSessionState()

    private fun publish(next: AudioSessionState) {
        state = next.copy(requested = wanted)
        changed(state)
    }
    private fun ready() = foreground && permission && wanted && !faulted &&
        !noisy && route.available && (!externalRequired || route.external)

    fun foreground(visible: Boolean, granted: Boolean) {
        if (foreground == visible && permission == granted) return
        foreground = visible
        permission = granted
        faulted = false
        retries = 0
        schedule()
    }

    fun permission(granted: Boolean) {
        if (permission == granted) return
        permission = granted
        faulted = false
        schedule()
    }

    fun start() {
        if (state.running || state.transitioning && ready()) return
        wanted = true
        noisy = false
        faulted = false
        retries = 0
        schedule()
    }

    fun stop() {
        wanted = false
        externalRequired = false
        noisy = false
        faulted = false
        schedule()
    }

    fun route(next: AudioRoute) {
        if (route == next) return
        route = next
        noisy = false
        faulted = false
        retries = 0
        schedule(400)
    }

    fun becomingNoisy() {
        if (!wanted) return
        noisy = true
        externalRequired = true
        schedule()
    }

    private fun stoppedStatus() = when {
        !wanted -> "Audio stopped"
        !foreground -> "Audio paused in background"
        !permission -> "Microphone permission required"
        noisy || !route.available || externalRequired && !route.external ->
            "Reconnect headphones or an audio interface"
        else -> "Audio stopped"
    }

    private fun schedule(debounceMs: Long = 0) {
        val epoch = ++revision
        // This atomic gate is independent of the worker queue: backgrounding,
        // unplugging and Stop take effect even during a long model load.
        backend.setMuted(true)
        monitor?.cancel()
        publish(AudioSessionState(transitioning = true,
            status = if (ready()) "Starting audio…" else stoppedStatus(), route = route.label))
        scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            mutex.withLock {
                if (epoch != revision) return@withLock
                withContext(dispatcher) { backend.stopEngine() }
                flush()
                if (epoch != revision) return@withLock
                if (!ready()) {
                    publish(state.copy(transitioning = false, status = stoppedStatus()))
                    return@withLock
                }
                val selected = route
                if (selected.external) externalRequired = true
                try {
                    withContext(dispatcher) {
                        backend.setDeviceIds(selected.inputId, selected.outputId)
                        check(backend.startEngine()) { backend.lastError().ifBlank { "Cannot open audio device." } }
                    }
                    if (epoch != revision) return@withLock
                    restore()
                    if (epoch != revision || !ready()) return@withLock
                    check(withContext(dispatcher) { backend.isRunning() }) { "Audio device disconnected during restoration." }
                    if (epoch != revision || !ready()) return@withLock
                    backend.setMuted(false)
                    publish(AudioSessionState(running = true, status = "Audio running", route = selected.label))
                    monitor(epoch)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    backend.setMuted(true)
                    withContext(dispatcher) { backend.stopEngine() }
                    if (epoch == revision) {
                        faulted = true
                        publish(AudioSessionState(status = "Audio stopped", route = selected.label,
                            error = e.message ?: "Audio recovery failed."))
                    }
                }
            }
        }
    }

    private fun monitor(epoch: Long) {
        monitor = scope.launch {
            while (epoch == revision && state.running) {
                delay(250)
                val failed = withContext(dispatcher) {
                    if (backend.isRunning()) null
                    else backend.lastError().ifBlank { "Audio stopped." } to backend.isRecoverableError()
                } ?: continue
                if (epoch != revision) return@launch
                backend.setMuted(true)
                if (failed.second && retries < 2 && ready()) {
                    retries++
                    schedule(400)
                } else {
                    faulted = true
                    // Close on the worker, even for nonrecoverable DSP faults.
                    schedule()
                    publish(state.copy(error = failed.first))
                }
                return@launch
            }
        }
    }

    fun muteForShutdown() {
        revision++
        monitor?.cancel()
        backend.setMuted(true)
    }
}
