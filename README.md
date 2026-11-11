# A2 Rack

A prototype Android amp and cabinet processor built with Kotlin, Jetpack Compose, and a native C++ audio engine. Audio passes through input gain, an optional Neural Amp Modeler (NAM) model, an optional cabinet impulse response, and output gain before stereo playback.

## Current progress

| Area | Status |
| --- | --- |
| Compose interface | Input/output gain, amp/cab bypass, model selection, and preset controls implemented |
| Audio engine | Fixed buffers, atomic controls/muting, callback-boundary model swaps, and foreground/route recovery implemented |
| NAM models | Worker-thread loading, mono/rate validation, and stream-specific initialization implemented |
| Cabinet responses | WAV validation and buffer warm-up implemented before activation |
| Lite/Full mode | Switch updates UI state only; DSP behavior pending |
| Presets | Persistent model library, versioned JSON, portable ZIP bundles, and automatic session restoration implemented |
| Diagnostics | Stream/device settings, latency estimates, xruns, signal meters, clipping, model status, and retained errors |
| Tests | JVM coverage for storage, sessions, lifecycle/recovery, diagnostics, and controls; native/device coverage pending |

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

The debug build passes with all 44 JVM tests passing after diagnostics integration. Device tests and audio latency measurements are still required to validate real-time behavior.

## Using the prototype

1. Launch the app and grant microphone permission.
2. Select a `.nam` amp model and a `.wav` cabinet impulse response.
3. Adjust input and output gain, or bypass either processing stage.
4. Use Import Preset to open settings JSON or a portable preset bundle.
5. Use Export Preset and choose Portable bundle to include model files, or Settings only for JSON references to the local library.

The engine starts when the activity is visible and microphone permission is granted. Leaving the activity, including opening a document picker, immediately mutes output and queues stream shutdown. Returning restores the previously running session; picker results wait for restoration before applying files.

Stop Audio mutes immediately and disables automatic restart, including while waiting for an unplugged device. This choice survives activity recreation through the ViewModel. Start Audio explicitly re-enables playback. Output remains muted while streams reopen and the session is restored against the negotiated sample rate.

Loading failures preserve the previous active model. Only mono NAM models are supported; models with a known incompatible sample rate are rejected unless they support arbitrary processing rates. The Lite/Full switch currently has no effect on audio processing.

## Audio routes and recovery

The app prefers connected USB audio, then wired headphones/headsets, then the device speaker and microphone. It pins AAudio streams to the selected device IDs and refuses a mismatched route. The current route and recovery status appear above the controls.

Disconnect errors silence the native callback immediately. Device-list and audio-becoming-noisy callbacks also mute independently of model-loading work. After external audio is removed, the app waits for an external device rather than automatically switching to the speaker. Reconnecting reopens streams and reloads models. To intentionally use the speaker after unplugging, press Stop Audio, then Start Audio.

Route events are debounced for 400 ms. Native disconnect faults can trigger up to two recovery attempts per route/foreground session; startup or unrecoverable processing failures remain stopped with an error and manual retry. Older streams' error callbacks are tagged so they cannot invalidate a newer stream.

## Audio diagnostics

Open **Show audio diagnostics** below the preset controls. Only the diagnostics content subscribes to meter updates, so the main controls do not recompose for each reading. Collection runs on the management worker four times per second while the activity is visible and pauses in the background.

The panel displays:

- Actual input/output device IDs with names from the route monitor, sample rate, input/output buffer sizes, output burst size, and callback size.
- Estimated input/output stream latency and their sum. Unavailable timestamps remain unavailable; these estimates exclude extra external-device latency. Round-trip measurement requires a physical loopback.
- Input/output xrun counters and input short-read callbacks, reset when streams reopen. Xruns report overruns/underruns and can indicate glitches; short reads count callbacks with missing input, including startup. Unsupported counters are shown as unavailable.
- Input peaks before gain and output peaks before limiting, on a −60 to 0 dBFS meter. Levels above full scale are shown numerically and flagged. Clipping indicators hold for one second; muted output and inactive streams show silent meters.
- Active, bypassed, unloaded, or unavailable model status, plus the latest recorded engine error retained through recovery.

Stopped streams retain their final format/counters as **Last stream • inactive**. A new stream resets meter holds and counters. Snapshot failures are reported in the panel without stopping audio. Diagnostics are transient UI state and are not saved in presets.

The native callback only accumulates peaks, clipping flags, and short-read counts through lock-free atomics. Stream queries, timestamp/latency calculation, JNI allocation, and formatting stay off the callback.

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
- [AudioSessionController.kt](app/src/main/java/org/acoustixaudio/a2rack/AudioSessionController.kt): foreground/permission policy, Start/Stop intent, and serialized recovery.
- [AudioRouteMonitor.kt](app/src/main/java/org/acoustixaudio/a2rack/AudioRouteMonitor.kt): device selection and Android route/noisy callbacks.
- [native-lib.cpp](app/src/main/cpp/native-lib.cpp): JNI bridge and engine ownership.
- [AudioEngine.cpp](app/src/main/cpp/AudioEngine.cpp): Oboe streams, DSP processing, and native diagnostics snapshots.
- [AudioDiagnostics.kt](app/src/main/java/org/acoustixaudio/a2rack/AudioDiagnostics.kt): snapshot decoding, meter scaling, and clipping/error presentation state.
- [AudioDiagnosticsPanel.kt](app/src/main/java/org/acoustixaudio/a2rack/AudioDiagnosticsPanel.kt): diagnostics display and signal meters.
- [CMakeLists.txt](app/src/main/cpp/CMakeLists.txt): native dependency integration.

## Known gaps and next steps

1. **Validate native behavior on devices.** Measure latency and dropouts during control changes and model replacement. Verify callback allocation behavior across supported NAM architectures, invalid model/IR files, and sample-rate rejection.
2. **Verify lifecycle and routes on hardware.** Exercise USB/headphone unplug/reconnect, route sample-rate changes, permission revocation, and backgrounding during model loading. No running Android device was available during implementation.
3. **Extend library management.** Add a model browser and explicit removal of unused assets. Model slots are restored sequentially while output is muted; the native engine does not provide a transactional two-model swap.
4. **Implement Lite/Full processing.** Define each mode's behavior and connect the switch to the native engine.
5. **Extend device coverage.** Verify document-provider interactions, replacement pickers, native handoff races, real microphone permission flows, and physical-device recovery.

## Physical-device verification

1. Grant microphone permission, load a NAM/IR pair, and confirm the displayed route.
2. Press Stop Audio; reconnect devices and rotate or background/foreground the app. Confirm it stays stopped until Start Audio is pressed.
3. While running through headphones or USB, unplug the device. Confirm immediate silence and a reconnect status, with no speaker fallback. Reconnect and confirm model restoration.
4. Send the app to the background during startup/model loading; confirm silence, microphone release after queued work completes, and restoration on return.
5. Open and return from each file picker; confirm the selected file is applied after foreground restoration.
6. Change between interfaces with different sample rates. Confirm incompatible models are named with errors rather than processed at the wrong rate.
7. Open audio diagnostics and verify device names/IDs and sample rate against the connected hardware. Confirm meters respond, output reads silent when muted/stopped, and deliberate overload holds CLIP briefly.
8. Watch xrun and short-read counters under realistic NAM load. Confirm counters reset after reopening streams. Measure round-trip latency with loopback hardware and inspect logs for crashes during repeated changes.

This progress summary is based on the current source review. No `plan.md` was available in the project during that review.
