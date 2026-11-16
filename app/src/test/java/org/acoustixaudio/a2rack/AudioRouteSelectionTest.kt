package org.acoustixaudio.a2rack

import org.junit.Assert.*
import org.junit.Test

class AudioRouteSelectionTest {
    private val mic = AudioEndpoint(1, EndpointKind.BUILTIN, true, false, "Mic")
    private val speaker = AudioEndpoint(2, EndpointKind.BUILTIN, false, true, "Speaker")

    @Test fun manualSelectionsOverrideAutomaticPreferencesIndependently() {
        val usbInput = AudioEndpoint(10, EndpointKind.USB, true, false, "USB input")
        val usbOutput = AudioEndpoint(20, EndpointKind.USB, false, true, "USB output")
        val devices = listOf(mic, speaker, usbInput, usbOutput)
        val mixed = selectAudioRoute(devices, inputId = 1, outputId = 20)
        assertEquals(1, mixed.inputId)
        assertEquals(20, mixed.outputId)
        val outputOnly = selectAudioRoute(devices, outputId = 2)
        assertEquals(2, outputOnly.outputId)
        assertTrue(outputOnly.available)
    }

    @Test fun otherAndroidDevicesCanBeSelectedWithoutChangingAutomaticPreference() {
        val hdmi = AudioEndpoint(30, EndpointKind.OTHER, false, true, "HDMI")
        val devices = listOf(mic, speaker, hdmi)
        assertEquals(2, selectAudioRoute(devices).outputId)
        assertEquals(30, selectAudioRoute(devices, outputId = 30).outputId)
    }

    @Test fun missingOrWrongDirectionSelectionsDoNotFallBack() {
        val devices = listOf(mic, speaker)
        assertFalse(selectAudioRoute(devices, inputId = 99).available)
        assertFalse(selectAudioRoute(devices, outputId = 99).available)
        assertFalse(selectAudioRoute(devices, inputId = 2).available)
        assertFalse(selectAudioRoute(devices, outputId = 1).available)
        assertTrue(selectAudioRoute(devices).available)
    }

    @Test fun usbIsPreferredAndMatchingInputIsSelected() {
        val usbOutput = AudioEndpoint(20, EndpointKind.USB, false, true, "Interface", "usb-b")
        val wrongInput = AudioEndpoint(10, EndpointKind.USB, true, false, "Other", "usb-a")
        val rightInput = AudioEndpoint(21, EndpointKind.USB, true, false, "Interface", "usb-b")
        val route = selectAudioRoute(listOf(mic, speaker, usbOutput, wrongInput, rightInput))
        assertEquals(21, route.inputId)
        assertEquals(20, route.outputId)
        assertTrue(route.external)
    }

    @Test fun headphonesWithoutMicrophoneUseBuiltInInput() {
        val headphones = AudioEndpoint(8, EndpointKind.WIRED, false, true, "Headphones")
        val route = selectAudioRoute(listOf(speaker, mic, headphones))
        assertEquals(1, route.inputId)
        assertEquals(8, route.outputId)
        assertTrue(route.external)
    }

    @Test fun incompleteRoutesAreUnavailableAndInputOrderingIsStable() {
        assertFalse(selectAudioRoute(listOf(mic)).available)
        assertEquals(selectAudioRoute(listOf(mic, speaker)), selectAudioRoute(listOf(speaker, mic)))
        assertFalse(selectAudioRoute(listOf(mic, speaker)).external)
    }
}
