package org.acoustixaudio.a2rack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

internal enum class EndpointKind { USB, WIRED, BUILTIN }
internal data class AudioEndpoint(
    val id: Int, val kind: EndpointKind, val input: Boolean, val output: Boolean,
    val name: String, val address: String = ""
)

internal fun selectAudioRoute(devices: List<AudioEndpoint>): AudioRoute {
    val ordered = devices.sortedWith(compareBy<AudioEndpoint> { it.kind.ordinal }.thenBy { it.id })
    val output = ordered.firstOrNull { it.output }
    val input = ordered.firstOrNull {
        it.input && output?.kind == it.kind && output.address.isNotEmpty() && output.address == it.address
    } ?: ordered.firstOrNull { it.input && it.kind == output?.kind }
        ?: ordered.firstOrNull { it.input }
    if (input == null || output == null)
        return AudioRoute(label = "Audio device unavailable", available = false)
    val external = input.kind != EndpointKind.BUILTIN || output.kind != EndpointKind.BUILTIN
    val label = when {
        output.kind != EndpointKind.BUILTIN -> output.name
        input.kind != EndpointKind.BUILTIN -> "${input.name} → device speaker"
        else -> "Device speaker / microphone"
    }
    return AudioRoute(input.id, output.id, label, external,
        inputName = input.name, outputName = output.name)
}

/** Registered only while the activity is visible. The initial device callback is deduplicated by policy. */
internal class AudioRouteMonitor(
    context: Context,
    private val onRoute: (AudioRoute) -> Unit,
    private val onNoisy: () -> Unit
) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(AudioManager::class.java)
    private var registered = false
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onNoisy()
        }
    }

    fun start() {
        if (registered) return
        registered = true
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        ContextCompat.registerReceiver(context, receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun stop() {
        if (!registered) return
        registered = false
        manager.unregisterAudioDeviceCallback(callback)
        context.unregisterReceiver(receiver)
    }

    private fun refresh() {
        if (!registered) return
        val endpoints = manager.getDevices(AudioManager.GET_DEVICES_ALL).mapNotNull { device ->
            val kind = when (device.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
                AudioDeviceInfo.TYPE_USB_HEADSET -> EndpointKind.USB
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> EndpointKind.WIRED
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_MIC -> EndpointKind.BUILTIN
                else -> return@mapNotNull null
            }
            AudioEndpoint(device.id, kind, device.isSource, device.isSink,
                device.productName.toString(), device.address)
        }
        onRoute(selectAudioRoute(endpoints))
    }
}
