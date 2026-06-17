cmake_minimum_required(VERSION 3.22.1)

project(auraltune_audio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Phase 1 build flags. -fno-finite-math-only is mandatory: the NaN guard in
# AuralTuneEQEngine::process() relies on real isfinite checks and must not be
# removed by the optimizer.
set(NATIVE_COMMON_FLAGS
    -O3
    -fno-finite-math-only
    -ffunction-sections
    -fdata-sections
    -Wall
    -Wextra
    -Wno-unused-parameter
)

# ---------------------------------------------------------------------------
# Shared DSP core (multi-frontend M0).
#
# JNI-free, OS-portable C++17. Built as an OBJECT library so multiple adapters
# (the JNI .so below, and a future effect/HAL .so) can link the SAME core
# objects without recompiling. `<android/log.h>` usage in the core is guarded
# by `#if defined(__ANDROID__)` with a host no-op, so this also compiles for
# host unit tests and a future effect/HAL port.
# ---------------------------------------------------------------------------
add_library(auraltune_core OBJECT
    core/BiquadFilter.cpp
    core/AuralTuneEQEngine.cpp
    # Stage C: ISO 226:2023 Loudness Compensation
    core/loudness/IsoContours.cpp
    core/loudness/LoudnessCompensator.cpp
    # Stage D: BS.1770 Auto-Leveler (KWeighting + Detector + GainComputer +
    # GainSmoother + LoudnessEqualizer). Detector/GainComputer/GainSmoother/
    # LoudnessEqualizer are header-only (templates of inline math).
    core/loudness/KWeightingFilter.cpp
)
target_compile_options(auraltune_core PRIVATE ${NATIVE_COMMON_FLAGS})
# PUBLIC so linking adapters resolve `#include "AuralTuneEQEngine.h"` etc.
# against core/ without editing their source.
target_include_directories(auraltune_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/core)

# ---------------------------------------------------------------------------
# Track B: JNI adapter — app-internal NDK .so (current shipping artifact).
# Name kept as `auraltune_audio` so build.gradle.kts, packaging and the 16 KB
# page-size CI check (libauraltune_audio.so) are unaffected.
# ---------------------------------------------------------------------------
add_library(auraltune_audio SHARED
    jni/AudioEngineJNI.cpp
)
target_compile_options(auraltune_audio PRIVATE ${NATIVE_COMMON_FLAGS})

# Linker:
#   --gc-sections : drop unused sections from -ffunction-sections / -fdata-sections.
#   max-page-size=16384 + common-page-size=16384 : Android 15+ requires 16 KB page
#       size compatibility. Without these flags, ELF LOAD segments are 4 KB aligned
#       and the loader rejects the .so on devices with 16 KB pages. Verify post-link
#       via `llvm-readelf -l libauraltune_audio.so | grep LOAD` — Align must be 0x4000.
target_link_options(auraltune_audio PRIVATE
    "-Wl,--gc-sections"
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)

find_library(ANDROID_LOG_LIB log)

# Linking the OBJECT library pulls in its objects AND propagates its PUBLIC
# include directory (CMake 3.12+).
target_link_libraries(auraltune_audio PRIVATE
    auraltune_core
    ${ANDROID_LOG_LIB}
)

# ---------------------------------------------------------------------------
# Track A (FUTURE / Phase F — boryu): effect/HAL adapter .so.
# Reuses the same auraltune_core objects; registered as a standard
# audio_effect.h post-processing effect via a Magisk module on rooted devices.
# Intentionally NOT built in this workstream.
# ---------------------------------------------------------------------------
# add_library(auraltune_effect SHARED effect/AuralTuneEffect.cpp)
# target_compile_options(auraltune_effect PRIVATE ${NATIVE_COMMON_FLAGS})
# target_link_options(auraltune_effect PRIVATE
#     "-Wl,--gc-sections"
#     "-Wl,-z,max-page-size=16384"
#     "-Wl,-z,common-page-size=16384")
# target_link_libraries(auraltune_effect PRIVATE auraltune_core ${ANDROID_LOG_LIB})
