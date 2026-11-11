package org.acoustixaudio.a2rack

import android.Manifest
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

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.startAudio()
        } else {
            viewModel.permissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.initializeStorage(applicationContext)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            viewModel.startAudio()
        }

        setContent {
            A2RackTheme {
                val backStack = remember { mutableStateListOf("Home") }

                Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
                    NavDisplay(
                        backStack = backStack,
                        entryProvider = { route ->
                            when (route) {
                                "Home" -> NavEntry(route) {
                                    MainScreen(viewModel = viewModel, onStartAudio = {
                                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                            viewModel.startAudio()
                                        } else {
                                            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
}
