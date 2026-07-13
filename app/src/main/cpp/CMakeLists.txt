cmake_minimum_required(VERSION 3.22.1)
project(vgmplayer)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 14)

# Silence common libvgm warnings on Android
add_compile_options(
    -Wno-unused-parameter
    -Wno-sign-compare
    -Wno-unused-variable
    -Wno-unused-function
    -Wno-unknown-pragmas
    -Wno-implicit-function-declaration
    -Wno-missing-field-initializers
    -Wno-deprecated-declarations
    -Wno-format
)

add_definitions(
    -DHAVE_STDINT_H
    -DVGM_LITTLE_ENDIAN
    -D_CRT_SECURE_NO_WARNINGS
)

# Include libvgm's own CMake configuration
set(UTIL_CHARSET_CONV OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/libvgm vgm_build)

# Include libgme for NSF and other formats
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GME_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(GME_BUILD_STATIC ON CACHE BOOL "" FORCE)
set(GME_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GME_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(GME_ZLIB OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/libgme gme_build)

# Include libopenmpt for tracker formats (MOD, XM, S3M, IT, etc.)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/libopenmpt openmpt_build)

# Include libkss for KSS format (MSX music files)
# Note: libkss has its own emu2413/emu8950 which conflicts with libgme's emu2413
# We use linker option --allow-multiple-definition to resolve this
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/libkss kss_build)

# Include libADLMIDI for MIDI files (OPL3 FM synthesis)
set(LIBADLMIDI_STATIC ON CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/libADLMIDI adlmidi_build)

# Include libMusDoom for Doom MUS files (OPL2/OPL3 FM synthesis)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/libMusDoom musdoom_build)

# Include libpsf for PSF/PSF1 files (PlayStation music)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/libpsf psf_build)

# JNI glue shared library
add_library(vgmplayer SHARED vgmplayer_jni.cpp)

target_include_directories(vgmplayer PRIVATE 
    ${CMAKE_CURRENT_SOURCE_DIR}/libvgm
    ${CMAKE_CURRENT_SOURCE_DIR}/libgme/gme
    ${CMAKE_CURRENT_SOURCE_DIR}/libopenmpt
    ${CMAKE_CURRENT_SOURCE_DIR}/libopenmpt/libopenmpt
    ${CMAKE_CURRENT_SOURCE_DIR}/libkss
    ${CMAKE_CURRENT_SOURCE_DIR}/libkss/src
    ${CMAKE_CURRENT_SOURCE_DIR}/libkss/modules
    ${CMAKE_CURRENT_SOURCE_DIR}/libADLMIDI/include
    ${CMAKE_CURRENT_SOURCE_DIR}/libMusDoom/src
    ${CMAKE_CURRENT_SOURCE_DIR}/libpsf
    ${CMAKE_CURRENT_SOURCE_DIR}/libpsf/spu
)

target_link_libraries(vgmplayer
    vgm-player
    vgm-emu
    vgm-utils
    gme_static
    openmpt_static
    kss
    ADLMIDI_static
    musdoom
    psf
    log
    android
    z
)

# Allow multiple definitions to resolve conflicts between libgme's emu2413 and libkss's emu2413
# Both libraries have their own emu2413 implementation with the same symbols
target_link_options(vgmplayer PRIVATE "-Wl,--allow-multiple-definition")
