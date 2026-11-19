package org.acoustixaudio.a2rack

import android.Manifest
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.Dispatchers
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import org.acoustixaudio.a2rack.ui.theme.A2RackTheme

class MainActivity : ComponentActivity() {
    private val viewModel: A2RackViewModel by viewModels()
    private lateinit var routeMonitor: AudioRouteMonitor
    private lateinit var proBilling: ProBilling

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.permissionGranted()
        } else {
            viewModel.permissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        proBilling = ProBilling(applicationContext)
        viewModel.initializeStorage(applicationContext)
        routeMonitor = AudioRouteMonitor(this, viewModel::onAudioDevicesChanged, viewModel::onAudioBecomingNoisy)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            A2RackTheme {
                val backStack = remember { mutableStateListOf("Home") }
                val pro by proBilling.state.collectAsStateWithLifecycle()
                var showPro by rememberSaveable { mutableStateOf(false) }
                if (showPro && !pro.owned) {
                    ProPurchaseDialog(pro, onBuy = { proBilling.buy(this@MainActivity) },
                        onRetry = proBilling::refresh, onDismiss = { showPro = false })
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                    NavDisplay(
                        backStack = backStack,
                        entryProvider = { route ->
                            when (route) {
                                "Home" -> NavEntry(route) {
                                    MainScreen(viewModel = viewModel, onStartAudio = ::requestAudioStart,
                                        onStopAudio = {
                                            viewModel.stopAudio()
                                            if (!pro.owned) {
                                                showPro = true
                                                proBilling.refresh()
                                            }
                                        })
                                }
                                else -> NavEntry(route) { }
                            }
                        }
                    )
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        routeMonitor.start()
        viewModel.onForegroundChanged(true, hasMicrophonePermission())
    }

    override fun onStop() {
        viewModel.onForegroundChanged(false, hasMicrophonePermission())
        routeMonitor.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        proBilling.refresh()
    }

    override fun onDestroy() {
        proBilling.close()
        super.onDestroy()
    }

    private fun requestAudioStart() {
        viewModel.startAudio()
        if (hasMicrophonePermission()) viewModel.permissionGranted()
        else requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun hasMicrophonePermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

}

@Preview(name = "A2 Rack • Light", showBackground = true)
@Preview(name = "A2 Rack • Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MainActivityPreview() = RackScreenPreview(A2RackState())

@Preview(name = "Compact phone • Large text", widthDp = 320, heightDp = 640, fontScale = 1.5f, showBackground = true)
@Composable
private fun CompactLargeTextPreview() = RackScreenPreview(
    A2RackState(isEngineRunning = true, audioStatus = "Audio running", routeLabel = "USB audio interface")
)

@Preview(name = "Device disconnected", widthDp = 360, heightDp = 720, showBackground = true)
@Composable
private fun DisconnectedDevicePreview() = RackScreenPreview(
    A2RackState(
        isWaitingForRoute = true,
        audioStatus = "Reconnect headphones or an audio interface",
        routeLabel = "Audio device unavailable",
        selectedOutputId = 7
    )
)

@Composable
private fun RackScreenPreview(state: A2RackState) {
    val previewViewModel = remember {
        A2RackViewModel(PreviewAudioBackend, Dispatchers.Main.immediate)
    }
    A2RackTheme {
        MainScreen(state, previewViewModel, onStartAudio = {}, onStopAudio = {})
    }
}

// Layoutlib cannot load the Android native audio library or access microphone hardware.
private object PreviewAudioBackend : AudioBackend {
    override fun startEngine() = false
    override fun stopEngine() {}
    override fun setMuted(muted: Boolean) {}
    override fun setDeviceIds(inputId: Int, outputId: Int) {}
    override fun isRecoverableError() = false
    override fun isRunning() = false
    override fun lastError() = ""
    override fun diagnostics() = AudioDiagnostics()
    override fun loadNamModel(path: String) = false
    override fun loadIrModel(path: String) = false
    override fun setInputGain(gain: Float) {}
    override fun setOutputGain(gain: Float) {}
    override fun setNamBypass(bypass: Boolean) {}
    override fun setIrBypass(bypass: Boolean) {}
}
