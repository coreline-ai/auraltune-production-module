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

add_library(auraltune_audio SHARED
    BiquadFilter.cpp
    AuralTuneEQEngine.cpp
    AudioEngineJNI.cpp
)

target_compile_options(auraltune_audio PRIVATE ${NATIVE_COMMON_FLAGS})

target_include_directories(auraltune_audio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

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

target_link_libraries(auraltune_audio
    ${ANDROID_LOG_LIB}
)
