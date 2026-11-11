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
| Presets | Persistent model library, versioned JSON, portable ZIP bundles, and automatic session restoration implemented |
| Tests | JVM coverage for storage, bundle validation, session restoration, startup, shutdown, and controls; native/device coverage pending |

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

Both tasks passed after the persistent-storage and preset changes, with 20 JVM tests passing. Device tests and audio latency measurements are still required to validate real-time behavior.

## Using the prototype

1. Launch the app and grant microphone permission.
2. Select a `.nam` amp model and a `.wav` cabinet impulse response.
3. Adjust input and output gain, or bypass either processing stage.
4. Use Import Preset to open settings JSON or a portable preset bundle.
5. Use Export Preset and choose Portable bundle to include model files, or Settings only for JSON references to the local library.

The engine attempts to start after microphone permission is granted. Startup and stream errors appear in the UI; use Start Audio to retry. After a successful start, the app restores the last session's models and controls against the new stream's sample rate. Output is muted while the model chain is restored.

Loading failures preserve the previous active model. Only mono NAM models are supported; models with a known incompatible sample rate are rejected unless they support arbitrary processing rates. The Lite/Full switch currently has no effect on audio processing.

## Model library and presets

Selected models are copied into app-private persistent storage under `files/rack-library/assets`. SHA-256 content IDs deduplicate identical files; original filenames remain available for display. Clearing the app cache does not remove these assets. Uninstalling the app or clearing its data does.

Version 1 JSON presets contain validated gains, bypass states, the mode flag, and asset IDs/display names. They contain no absolute paths. A JSON preset needs its assets in the destination library; choose Portable bundle when transferring to another device.

Portable `.a2rack` files are ZIP archives containing `preset.json`, and `assets/amp.nam` / `assets/cab.wav` when those slots are selected. Import validates the schema, expected entries, size limits, and content checksums before publishing assets. Limits are 256 KiB for JSON and 128 MiB per model.

The last session is saved by atomic file replacement after model/preset changes, after a short debounce for controls, and on normal ViewModel shutdown. Abrupt process termination may lose the most recent debounced control changes.

Missing, damaged, or unsupported models remain named in the UI with a Replace File action. Their processing slots are cleared while other available models and settings are restored. A portable export requires all referenced assets to be available. Settings-only export can preserve missing references.

Legacy path-based JSON presets can migrate generated files that still exist in this installation's app cache. Missing or external paths require replacement; imported paths cannot read arbitrary private files.

## Code layout

- [MainActivity.kt](app/src/main/java/org/acoustixaudio/a2rack/MainActivity.kt): activity, permission request, and navigation entry.
- [MainScreen.kt](app/src/main/java/org/acoustixaudio/a2rack/MainScreen.kt): Compose controls and document pickers.
- [A2RackViewModel.kt](app/src/main/java/org/acoustixaudio/a2rack/A2RackViewModel.kt): UI state, model loading, and session restoration/autosave.
- [PresetRepository.kt](app/src/main/java/org/acoustixaudio/a2rack/PresetRepository.kt): content-addressed model storage, versioned presets, and portable bundles.
- [AudioEngine.kt](app/src/main/java/org/acoustixaudio/a2rack/AudioEngine.kt): Kotlin native API.
- [native-lib.cpp](app/src/main/cpp/native-lib.cpp): JNI bridge and engine ownership.
- [AudioEngine.cpp](app/src/main/cpp/AudioEngine.cpp): Oboe streams and DSP processing.
- [CMakeLists.txt](app/src/main/cpp/CMakeLists.txt): native dependency integration.

## Known gaps and next steps

1. **Validate native behavior on devices.** Measure latency and dropouts during control changes and model replacement. Verify callback allocation behavior across supported NAM architectures, invalid model/IR files, and sample-rate rejection.
2. **Complete lifecycle policy.** Define background behavior and automatic route/disconnection recovery. Current handling reports a stopped stream and provides manual restart.
3. **Extend library management.** Add a model browser and explicit removal of unused assets. Model slots are restored sequentially while output is muted; the native engine does not provide a transactional two-model swap.
4. **Implement Lite/Full processing.** Define each mode's behavior and connect the switch to the native engine.
5. **Extend device coverage.** Verify document-provider interactions, replacement pickers, native handoff races, real microphone permission flows, and physical-device recovery.

This progress summary is based on the current source review. No `plan.md` was available in the project during that review.
