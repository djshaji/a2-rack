# Project Plan

A2 Rack — Android NAM A2 Guitar Processor (Starter)
Create Neural Amp Modeler A2 (Architecture 2) Realtime Guitar Effects Processor for Android using:
- oboe
- IR cabinet
- Load / Save presets
- material 3 design
Android project for Jetpack Compose Material 3 + Oboe + NeuralAmpModelerCore  + WAV cabinet IR + JSON presets.

Requirements:
- Android Studio with JDK, Android SDK, NDK, CMake
- Git, local Gradle

DSP Pipeline:
USB input (mono) -> input gain -> NAM A2 (or bypass) -> NAM Core WAV IR (or bypass) -> output gain -> hard safety clamp -> stereo output.

Current features to implement:
- Android Material 3 Compose UI
- Oboe input/output callback + duplex
- NAM `.nam` loading via C++ DSP Core with A2 fast path
- A2 Lite/Full switch when the file supports `SlimmableModel`
- WAV cabinet IR loading, including 1->2 channel WAV IR
- Input/output gain and amp/cab bypass
- JSON preset import/export via Storage Access Framework (SAF)

Implementation follow-ups (future/maybe partial):
- implement input/output device selectors, noise gate, tuner, metering, soft limiter, resampling.
- anti-feedback safeguard.
- C++ CMakeLists.txt custom compilation for NAM sources.

## Project Brief

# Project Brief: A2 Rack

**A2 Rack** is a Neural Amp Modeler A2 (Architecture 2) Realtime Guitar Effects Processor for Android, designed to provide low-latency, high-fidelity guitar amp and cabinet simulation via a modern, material design interface.

## Features

1. **Real-time Audio Pipeline (Oboe)**: Low-latency duplex audio streaming supporting USB mono input and stereo output with a hard safety clamp.
2. **Neural Amp Modeler (NAM) Engine**: Load and process `.nam` models using the C++ DSP Core, supporting the A2 fast path and a Lite/Full switch for models implementing `SlimmableModel`.
3. **Cabinet Simulation (IR Loader)**: Load WAV impulse responses (including 1->2 channel routing) to authentically simulate guitar cabinets in the DSP pipeline.
4. **DSP Controls**: Real-time control over input gain and output gain, alongside hardware-friendly bypass toggles for both the Amp (NAM) and Cabinet (IR) stages.
5. **Preset Management**: Import and export JSON-based processor presets leveraging the Android Storage Access Framework (SAF).

## High-Level Tech Stack

*   **Language & UI**: Kotlin, Jetpack Compose (Material 3)
*   **Navigation & Layout**: 
    *   **Jetpack Navigation 3** (strictly state-driven navigation)
    *   **Compose Material Adaptive** (for scalable and responsive layouts across devices)
*   **Asynchronous Operations**: Kotlin Coroutines (for managing SAF I/O and UI state without blocking the main thread)
*   **Native & Audio (C++ / JNI)**: 
    *   Android NDK & CMake (custom `CMakeLists.txt` compilation)
    *   **Oboe** (for low-latency, real-time C++ audio callbacks)
    *   **NeuralAmpModelerCore** (Core DSP processing for NAM models and WAV cabinet IRs)

## Implementation Steps

### Task_1_SetupOboeJNI: Initialize C++ project with CMake, NDK, and Oboe. Create JNI bridge. Setup basic duplex audio stream (input -> hard safety clamp -> output).
- **Status:** COMPLETED
- **Updates:** Coder agent successfully initialized the C++ project with CMake, NDK, and Oboe, created a JNI bridge, and set up a basic duplex audio stream. Project builds successfully.
- **Acceptance Criteria:**
  - Project builds successfully with CMake and NDK
  - Oboe audio stream can start and stop
  - Basic pass-through works without crash

### Task_2_IntegrateNAMCore: Add NAM Core to C++. Implement DSP pipeline: input gain -> NAM A2 (.nam loading, A2 Lite/Full switch) -> WAV IR cabinet (.wav loading) -> output gain -> clamp. Expose JNI methods.
- **Status:** COMPLETED
- **Updates:** Coder agent successfully integrated NeuralAmpModelerCore to C++, implemented the DSP pipeline with NAM A2, WAV IR cabinet, and gain controls, and exposed JNI methods. Build passes.
- **Acceptance Criteria:**
  - C++ NAM and IR modules compile
  - JNI methods for loading models and setting gains are implemented
  - Build pass

### Task_3_ComposeAdaptiveUI: Create Adaptive Material 3 UI with Jetpack Navigation 3. Add controls for input/output gain, amp/cab bypass, A2 Lite/Full switch, and SAF file pickers for .nam and .wav.
- **Status:** COMPLETED
- **Updates:** Coder agent successfully built the Adaptive Material 3 UI with SAF file pickers and ViewModel state management. File URIs are properly cached for C++ path access.
- **Acceptance Criteria:**
  - UI renders correctly and adapts to screen sizes
  - File pickers open using SAF
  - Sliders and toggles update ViewModel state
- **Duration:** N/A

### Task_4_IntegrationAndVerify: Wire ViewModel to JNI to control DSP in real-time. Implement JSON preset import/export using SAF. Run and verify application stability (no crashes), confirm alignment with requirements, and report critical UI issues.
- **Status:** IN_PROGRESS
- **Acceptance Criteria:**
  - UI controls alter real-time audio
  - Presets save and load correctly
  - make sure all existing tests pass
  - build pass
  - app does not crash
- **StartTime:** 2026-11-11 19:56:53 IST

