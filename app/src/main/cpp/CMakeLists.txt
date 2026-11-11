cmake_minimum_required(VERSION 3.22.1)

project("a2rack")

find_package(oboe REQUIRED CONFIG)

set(NAM_DIR "NeuralAmpModelerCore")
file(GLOB NAM_SOURCES_TOP "${NAM_DIR}/NAM/*.cpp")
file(GLOB NAM_SOURCES_SUB "${NAM_DIR}/NAM/*/*.cpp")
file(GLOB AUDIO_DSP_TOOLS_SRCS "${NAM_DIR}/Dependencies/AudioDSPTools/dsp/*.cpp")

# Exclude NoiseGate.cpp
list(REMOVE_ITEM AUDIO_DSP_TOOLS_SRCS "${CMAKE_CURRENT_SOURCE_DIR}/${NAM_DIR}/Dependencies/AudioDSPTools/dsp/NoiseGate.cpp")
list(REMOVE_ITEM AUDIO_DSP_TOOLS_SRCS "${CMAKE_CURRENT_SOURCE_DIR}/${NAM_DIR}/Dependencies/AudioDSPTools/dsp/NoiseGate.cpp")

add_library(a2rack SHARED
            native-lib.cpp
            AudioEngine.cpp
            ${NAM_SOURCES_TOP}
            ${NAM_SOURCES_SUB}
            ${AUDIO_DSP_TOOLS_SRCS}
)

target_include_directories(a2rack PRIVATE
    ${NAM_DIR}
    ${NAM_DIR}/Dependencies/nlohmann
    ${NAM_DIR}/Dependencies/eigen
    ${NAM_DIR}/Dependencies/AudioDSPTools
)

target_compile_definitions(a2rack PRIVATE
    NAM_SAMPLE_FLOAT=1
    DSP_SAMPLE_FLOAT=1
    NAM_ENABLE_A2_FAST=1
)

target_compile_options(a2rack PRIVATE
    -Wno-error
    # Preserve NaN/Inf checks at the audio boundary.
    -O3
)

target_link_libraries(a2rack
                      oboe::oboe
                      log)
