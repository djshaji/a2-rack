# A2 Rack

A prototype Android amp and cabinet processor built with Kotlin, Jetpack Compose, and a native C++ audio engine. Audio passes through input gain, an optional Neural Amp Modeler (NAM) model, an optional cabinet impulse response, and output gain before stereo playback.

## Current progress

| Area | Status |
| --- | --- |
| Compose interface | Input/output gain, amp/cab bypass, model selection, and preset controls implemented |
| Audio engine | Fixed-size processing buffers, atomic controls, and callback-boundary model swaps implemented |
| NAM models | Worker-thread loading, mono/rate validation, and stream-specific initialization implemented |
| Cabinet responses | WAV validation and buffer warm-up implemented before activation |
| Lite/Full mode | Switch updates UI state only; DSP behavior pending |
| Presets | JSON import/export implemented; asset storage needs improvement |
| Tests | ViewModel startup, retry, disconnection, shutdown, and gain-validation tests added; native/device coverage pending |

The engine uses an Oboe-adapted 128-frame callback quantum, preallocated engine buffers, and worker-thread DSP warm-up. Model swaps use an atomic handoff; retired models are destroyed on the worker thread. This does not yet establish reliable real-time performance. Real-device audio validation is still required.

## Build

Open the project in Android Studio and sync Gradle. The current configuration uses:

- Minimum Android API 29; compile and target API 37.
- Java source/target compatibility 11.
- CMake 3.22.1 and C++17.
- Oboe 1.8.1 through Prefab.
- NeuralAmpModelerCore under `app/src/main/cpp/NeuralAmpModelerCore`.

Install the SDK and native build tools requested by Gradle, and ensure the native dependency sources are present.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Both tasks passed after the audio-engine stabilization changes. The JVM suite has five passing tests, including four ViewModel regression tests. Device tests and audio latency measurements are still required to validate real-time behavior.

## Using the prototype

1. Launch the app and grant microphone permission.
2. Select a `.nam` amp model and a `.wav` cabinet impulse response.
3. Adjust input and output gain, or bypass either processing stage.
4. Use Import Preset or Export Preset to read or write JSON settings.

The engine attempts to start after microphone permission is granted. Startup and stream errors appear in the UI; use Start Audio to retry. Restarting clears the active models because the new stream may use a different sample rate. Reload the desired models after restarting.

Loading failures preserve the previous active model. Only mono NAM models are supported; models with a known incompatible sample rate are rejected unless they support arbitrary processing rates. The Lite/Full switch currently has no effect on audio processing.

Presets contain gain values, bypass states, the mode flag, and absolute paths to selected files copied into app cache storage. They do not include the model or IR assets. Cache cleanup or transfer to another device can invalidate these paths.

## Code layout

- [MainActivity.kt](app/src/main/java/org/acoustixaudio/a2rack/MainActivity.kt): activity, permission request, and navigation entry.
- [MainScreen.kt](app/src/main/java/org/acoustixaudio/a2rack/MainScreen.kt): Compose controls and document pickers.
- [A2RackViewModel.kt](app/src/main/java/org/acoustixaudio/a2rack/A2RackViewModel.kt): UI state, file copying, and preset serialization.
- [AudioEngine.kt](app/src/main/java/org/acoustixaudio/a2rack/AudioEngine.kt): Kotlin native API.
- [native-lib.cpp](app/src/main/cpp/native-lib.cpp): JNI bridge and engine ownership.
- [AudioEngine.cpp](app/src/main/cpp/AudioEngine.cpp): Oboe streams and DSP processing.
- [CMakeLists.txt](app/src/main/cpp/CMakeLists.txt): native dependency integration.

## Known gaps and next steps

1. **Validate native behavior on devices.** Measure latency and dropouts during control changes and model replacement. Verify callback allocation behavior across supported NAM architectures, invalid model/IR files, and sample-rate rejection.
2. **Complete lifecycle policy.** Define background behavior and automatic route/disconnection recovery. Current handling reports a stopped stream and provides manual restart.
3. **Make presets durable.** Store assets persistently and use stable references or bundle assets with exported presets. Preset import currently commits each successfully loaded model independently; it is not an atomic transaction.
4. **Implement Lite/Full processing.** Define each mode's behavior and connect the switch to the native engine.
5. **Extend test coverage.** Cover preset round trips, file-provider errors, native handoff races, real microphone permission flows, and physical-device recovery.

This progress summary is based on the current source review. No `plan.md` was available in the project during that review.
