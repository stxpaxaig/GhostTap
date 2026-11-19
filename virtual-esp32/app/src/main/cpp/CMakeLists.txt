# libesp32_qemu_android — JNI lifecycle for the classic ESP32 QEMU backend.
#
# Two build modes:
#
#  1. STUB (default): QEMU_CORE_AVAILABLE undefined. Builds only the JNI
#     wrapper (esp32_qemu_jni.c). nativeStart() validates arguments, then
#     returns 0 with a precise "QEMU core not compiled in" error. The Java
#     layer keeps reporting the backend as unavailable. This mode always
#     builds and is what the GitHub Actions APK uses.
#
#  2. FULL: -DQEMU_CORE_AVAILABLE=ON -DQEMU_BUILD_DIR=<dir>. Expects the
#     Espressif QEMU cross-compile output (see qemu/android/BUILD.md):
#       <dir>/include   — QEMU public + generated headers
#       <dir>/lib       — libqemu_xtensa_softmmu.a and dependency archives
#     Then qemu_driver_real.c is compiled and linked against the QEMU
#     softmmu objects, and nativeStart() really boots the guest.
#
# Neither mode fakes execution: without the core, start fails loudly.
cmake_minimum_required(VERSION 3.22)

project(esp32_qemu_android C)

option(QEMU_CORE_AVAILABLE "Link the Espressif QEMU softmmu core" OFF)
set(QEMU_BUILD_DIR "" CACHE PATH "QEMU Android cross-compile output dir")

add_library(esp32_qemu_android SHARED
    esp32_qemu_jni.c
)

target_include_directories(esp32_qemu_android PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

if (QEMU_CORE_AVAILABLE)
    if (NOT QEMU_BUILD_DIR)
        message(FATAL_ERROR "QEMU_CORE_AVAILABLE=ON requires -DQEMU_BUILD_DIR=<dir>")
    endif()
    if (NOT EXISTS "${QEMU_BUILD_DIR}/lib/libqemu_xtensa_softmmu.a")
        message(FATAL_ERROR "QEMU softmmu archive not found at ${QEMU_BUILD_DIR}/lib/libqemu_xtensa_softmmu.a — run qemu/android/build-qemu-android.sh first")
    endif()

    target_sources(esp32_qemu_android PRIVATE qemu_driver_real.c)
    target_compile_definitions(esp32_qemu_android PRIVATE QEMU_CORE_AVAILABLE)
    target_include_directories(esp32_qemu_android PRIVATE
        ${QEMU_BUILD_DIR}/include
    )
    # QEMU softmmu archive first, then its dependency archives.
    target_link_libraries(esp32_qemu_android PRIVATE
        ${QEMU_BUILD_DIR}/lib/libqemu_xtensa_softmmu.a
        ${QEMU_BUILD_DIR}/lib/libqemu_deps.a
    )
    message(STATUS "Building libesp32_qemu_android WITH QEMU core from ${QEMU_BUILD_DIR}")
else()
    message(STATUS "Building libesp32_qemu_android in STUB mode (no QEMU core)")
endif()

find_library(log-lib log)
target_link_libraries(esp32_qemu_android PRIVATE ${log-lib})
