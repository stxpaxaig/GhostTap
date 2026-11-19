# Classic ESP32 QEMU integration boundary

## Feasibility

Espressif's fork is the relevant core for the original Xtensa LX6 ESP32 and is a better match for ESP32-SOLO-1C than the newer `esp-emulator` chip set. The fork documents a `qemu-system-xtensa` target and Linux-style host build. It does not document Android as a host target. Android integration is technically plausible, but requires a QEMU Android/Bionic port built with the Android NDK, compatible builds of QEMU dependencies, and a JNI lifecycle/IO layer. A normal Linux QEMU executable cannot simply be copied into an APK. GPL obligations and notices for QEMU plus dependency licenses also need review before distribution.

**Update 2026-10-09:** the NDK/JNI/CMake scaffolding now exists in
`app/src/main/cpp/` (`esp32_qemu_jni.c`, `qemu_session.h`,
`qemu_driver_real.c`, `CMakeLists.txt`) and is wired into `app/build.gradle`
(`externalNativeBuild`, arm64-v8a). The driver was written against the real
Espressif QEMU 9.2 APIs, verified in `espressif/qemu@esp-develop` source
(full API table in `app/src/main/cpp/qemu/android/BUILD.md`). The port
procedure and dependency chain (libffi → pcre2 → glib2 → pixman → QEMU
`xtensa-softmmu` with `-Dtcg_interpreter=true`) are scripted in
`app/src/main/cpp/qemu/android/build-qemu-android.sh`.

What is still missing: the actual Android/Bionic cross-compile of the QEMU
core. Until `libesp32_qemu_android.so` is built with `QEMU_CORE_AVAILABLE`,
`ClassicEsp32QemuBackend` is a deliberately unavailable JNI boundary; the
runtime refuses to report a guest as running until `nativeStart` returns a
non-zero handle, which only the real driver can produce. The stub precisely
reports what is missing via the new `nativeGetLastError()` instead of
faking success.

## Minimum boot image

The Espressif machine supplies the first-stage ROM bootloader. A conventional merged flash image therefore needs the second-stage bootloader at `0x1000`, an ESP-IDF partition table at `0x8000` (default offset), and an application image at an offset listed in that table (commonly `0x10000`). OTA metadata/data partitions are needed when the selected image/boot flow uses OTA. The flash backing size must be one supported by the machine (2, 4, 8, or 16 MiB). Espressif documents making the image with `esptool.py --chip esp32 merge_bin --fill-flash-size 4MB -o flash_image.bin @flash_args` and launching with `-machine esp32 -drive file=flash_image.bin,if=mtd,format=raw`. A standalone application `.bin` is not a merged boot image. Encrypted images additionally need compatible eFuse/security state; the QEMU eFuse backing documented for classic ESP32 is 124 bytes.

The importer copies images to app-private storage, checks the supported size, checks for an ESP image header at `0x1000`, parses the default partition table, and looks for an app image header at a listed app offset. This is only a structural screening check: it does not validate image checksums/signatures, secure boot, encryption, custom partition-table offsets, firmware compatibility, or successful guest boot.

## HAL bridge map

| Subsystem | Current bridge | Limit / next native work |
|---|---|---|
| Flash | Imported merged image stored privately; native contract receives its path | QEMU must own the guest's SPI NOR image. Existing JSON NVS is separate and is not translated into ESP NVS sectors. Persist QEMU writes back to the image. |
| UART | Loopback TCP server and Android terminal TX/RX adapter are implemented | Native QEMU must connect serial to `tcp:127.0.0.1:<port>` (or JNI must expose the character backend directly). Bridge is dormant until QEMU is present. |
| GPIO | Existing virtual GPIO state remains available to the Android runtime | Espressif classic QEMU does not model the general GPIO matrix/IOMUX; no guest GPIO→Android GPIO mapping is claimed. |
| Timers/events | Existing Android software timers/events remain available | ESP32 timer groups are emulated inside QEMU, but no guest timer callback is currently exported to Android HAL. |
| Networking | Android host network status/scanning remains in the existing runtime | QEMU documents OpenCores Ethernet with user-mode networking (emulated Ethernet, not ESP Wi-Fi). A future adapter can bridge guest TCP/UDP via slirp/user networking. It is not equivalent to Android Wi-Fi radio or Layer-2 access. |
| BLE | Existing Android GATT provisioning server remains independently available | Classic ESP32 QEMU does not emulate Bluetooth. The Android BLE server is not driven by guest ESP-IDF Bluetooth code. |

The Java runtime's UART bridge listens on an ephemeral loopback port before calling native start. The JNI implementation creates the `esp32` QEMU machine, attaches the merged image as the first MTD flash drive, attaches the 124-byte eFuse file via `-global nvram.esp32.efuse.drive=...` when provided, runs QEMU on a managed worker thread, and configures UART0 as a TCP client to the returned port (`-serial tcp:127.0.0.1:<port>`). Stop/reset marshal to QEMU's main loop (`qemu_system_shutdown_request` / `qemu_system_reset_request`) and join the worker before closing bridge sockets. The JNI contract establishes the lifecycle boundary; the QEMU core itself is supplied by the Android/Bionic cross-compile described in `app/src/main/cpp/qemu/android/BUILD.md`.

## Native build modes

`app/src/main/cpp/CMakeLists.txt` builds `libesp32_qemu_android` in two modes:

- **STUB (default):** JNI wrapper only. `nativeStart` validates arguments and
  returns 0 with a precise reason from `nativeGetLastError()`. The APK always
  builds in this mode; the app honestly reports the backend as unavailable.
- **FULL:** `-DQEMU_CORE_AVAILABLE=ON -DQEMU_BUILD_DIR=<install dir>`.
  Compiles `qemu_driver_real.c` and links the cross-compiled QEMU softmmu
  archives. Only this mode can return a live session handle.

## What is verified here

Host tests can verify Java/source contracts and image-layout parsing rules. They cannot prove Android Bluetooth behavior, QEMU CPU execution, or firmware boot. Those require an Android build and two-device/live emulator tests. See `README.md` for current status and exact test results.
