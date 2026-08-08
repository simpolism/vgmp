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

# Headless mGBA core and PSF container loader for GSF/miniGSF.
set(LIBMGBA_ONLY ON CACHE BOOL "" FORCE)
set(MINIMAL_CORE 3 CACHE STRING "" FORCE)
set(M_CORE_GBA ON CACHE BOOL "" FORCE)
set(M_CORE_GB OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
set(BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(DISABLE_DEPS ON CACHE BOOL "" FORCE)
set(ENABLE_EXTRA ON CACHE BOOL "" FORCE)
set(BINARY_NAME mgba-gsf CACHE STRING "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/mgba-gsf mgba_gsf_build)

add_library(psflib_gsf STATIC
    ${CMAKE_CURRENT_SOURCE_DIR}/psflib/psflib.c
)
target_include_directories(psflib_gsf PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/psflib
)
target_link_libraries(psflib_gsf z)

# vio2sf is a stripped DeSmuME core for 2SF/mini2SF playback.
set(VIO2SF_DIR ${CMAKE_CURRENT_SOURCE_DIR}/vio2sf/src/vio2sf/desmume)
add_library(vio2sf STATIC
    ${VIO2SF_DIR}/armcpu.c
    ${VIO2SF_DIR}/arm_instructions.c
    ${VIO2SF_DIR}/barray.c
    ${VIO2SF_DIR}/bios.c
    ${VIO2SF_DIR}/cp15.c
    ${VIO2SF_DIR}/FIFO.c
    ${VIO2SF_DIR}/GPU.c
    ${VIO2SF_DIR}/isqrt.c
    ${VIO2SF_DIR}/matrix.c
    ${VIO2SF_DIR}/mc.c
    ${VIO2SF_DIR}/MMU.c
    ${VIO2SF_DIR}/NDSSystem.c
    ${VIO2SF_DIR}/resampler.c
    ${VIO2SF_DIR}/state.c
    ${VIO2SF_DIR}/thumb_instructions.c
    ${VIO2SF_DIR}/SPU.cpp
)
set_target_properties(vio2sf PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(vio2sf PUBLIC ${VIO2SF_DIR})

# LazyUSF2 provides Nintendo 64 USF/miniUSF emulation. Android uses its
# portable cached interpreter; audio is resampled by the core to VGMP's rate.
set(LAZYUSF2_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lazyusf2)
add_library(lazyusf2 STATIC
    ${LAZYUSF2_DIR}/ai/ai_controller.c
    ${LAZYUSF2_DIR}/api/callbacks.c
    ${LAZYUSF2_DIR}/debugger/dbg_decoder.c
    ${LAZYUSF2_DIR}/main/main.c
    ${LAZYUSF2_DIR}/main/rom.c
    ${LAZYUSF2_DIR}/main/savestates.c
    ${LAZYUSF2_DIR}/main/util.c
    ${LAZYUSF2_DIR}/memory/memory.c
    ${LAZYUSF2_DIR}/pi/cart_rom.c
    ${LAZYUSF2_DIR}/pi/pi_controller.c
    ${LAZYUSF2_DIR}/r4300/cached_interp.c
    ${LAZYUSF2_DIR}/r4300/cp0.c
    ${LAZYUSF2_DIR}/r4300/cp1.c
    ${LAZYUSF2_DIR}/r4300/exception.c
    ${LAZYUSF2_DIR}/r4300/interupt.c
    ${LAZYUSF2_DIR}/r4300/mi_controller.c
    ${LAZYUSF2_DIR}/r4300/pure_interp.c
    ${LAZYUSF2_DIR}/r4300/r4300.c
    ${LAZYUSF2_DIR}/r4300/r4300_core.c
    ${LAZYUSF2_DIR}/r4300/recomp.c
    ${LAZYUSF2_DIR}/r4300/reset.c
    ${LAZYUSF2_DIR}/r4300/tlb.c
    ${LAZYUSF2_DIR}/rdp/rdp_core.c
    ${LAZYUSF2_DIR}/ri/rdram.c
    ${LAZYUSF2_DIR}/ri/rdram_detection_hack.c
    ${LAZYUSF2_DIR}/ri/ri_controller.c
    ${LAZYUSF2_DIR}/rsp/rsp_core.c
    ${LAZYUSF2_DIR}/rsp_hle/alist.c
    ${LAZYUSF2_DIR}/rsp_hle/alist_audio.c
    ${LAZYUSF2_DIR}/rsp_hle/alist_naudio.c
    ${LAZYUSF2_DIR}/rsp_hle/alist_nead.c
    ${LAZYUSF2_DIR}/rsp_hle/audio.c
    ${LAZYUSF2_DIR}/rsp_hle/cicx105.c
    ${LAZYUSF2_DIR}/rsp_hle/hle.c
    ${LAZYUSF2_DIR}/rsp_hle/hvqm.c
    ${LAZYUSF2_DIR}/rsp_hle/jpeg.c
    ${LAZYUSF2_DIR}/rsp_hle/memory.c
    ${LAZYUSF2_DIR}/rsp_hle/mp3.c
    ${LAZYUSF2_DIR}/rsp_hle/musyx.c
    ${LAZYUSF2_DIR}/rsp_hle/plugin.c
    ${LAZYUSF2_DIR}/rsp_hle/re2.c
    ${LAZYUSF2_DIR}/rsp_lle/rsp.c
    ${LAZYUSF2_DIR}/si/cic.c
    ${LAZYUSF2_DIR}/si/game_controller.c
    ${LAZYUSF2_DIR}/si/n64_cic_nus_6105.c
    ${LAZYUSF2_DIR}/si/pif.c
    ${LAZYUSF2_DIR}/si/si_controller.c
    ${LAZYUSF2_DIR}/usf/usf.c
    ${LAZYUSF2_DIR}/usf/barray.c
    ${LAZYUSF2_DIR}/usf/resampler.c
    ${LAZYUSF2_DIR}/vi/vi_controller.c
)
set_target_properties(lazyusf2 PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(lazyusf2 PUBLIC ${LAZYUSF2_DIR})
target_compile_options(lazyusf2 PRIVATE -Wno-return-type)

# JNI glue shared library
add_library(vgmplayer SHARED vgmplayer_jni.cpp gsf_backend.cpp twosf_backend.cpp usf_backend.cpp)

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
    ${CMAKE_CURRENT_SOURCE_DIR}/mgba-gsf/include
    ${CMAKE_CURRENT_BINARY_DIR}/mgba_gsf_build/include
    ${CMAKE_CURRENT_SOURCE_DIR}/psflib
    ${VIO2SF_DIR}
    ${LAZYUSF2_DIR}
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
    mgba-gsf
    psflib_gsf
    vio2sf
    lazyusf2
    log
    android
    z
)

# Allow multiple definitions to resolve conflicts between libgme's emu2413 and libkss's emu2413
# Both libraries have their own emu2413 implementation with the same symbols
target_link_options(vgmplayer PRIVATE "-Wl,--allow-multiple-definition")
