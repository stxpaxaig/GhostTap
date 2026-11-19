# Virtual ESP32 for Android

A standalone native Android app that hosts an ESP32-compatible virtual device runtime. It does not execute ESP32 machine code and is independent of Levoit and GHOST TAP. No third-party Android app dependencies are used. The supplied Espressif source archive and its Apache 2.0 license are included under `protocol/espressif-2.2.3/` as protocol references.

## Espressif reference reverse-map

The supplied ZIP and TAR.GZ were fully extracted and compared: each contains the same 203 files with identical relative paths and file hashes. Both supplied APKs were also checked as ZIP/APK files, including their manifest and `classes.dex`. The client app source imports Espressif provisioning library 2.2.3; the source archive also includes that library's Java and `.proto` sources. The important opposite-side behavior is:

| Client path | Device protocol | Implemented here |
|---|---|---|
| Discovery | BLE scan results contain a `PROV_` local name and primary service UUID | Connectable BLE advertisement, `PROV_PHONE32`, advertised primary UUID `7ab1ffff-6b3d-4f60-9e2f-13572468f00d` |
| Endpoint map | Each GATT characteristic has a User Description (`0x2901`) containing the endpoint name; client writes then reads the same characteristic | Device-side GATT server exposes names and response reads. UUID suffixes: session FF51, config FF52, version FF53, scan FF54, control FF55 |
| Version | Client writes to `proto-ver`, then reads UTF-8 JSON `prov.ver`, `prov.sec_ver`, `prov.sec_patch_ver`, and `prov.cap` | Returns v1.1, Security 0, and truthful `no_pop`; includes `wifi_scan` only after Android location permission |
| Session | `Session.init()` sends a Security 0 `SessionData.sec0.sc` command, then expects `SessionData.sec0.sr` with success | Security 0 handshake implemented from `session.proto` and `sec0.proto`; data is plaintext and unauthenticated |
| Wi-Fi scan | Protobuf command enum 0 + `cmd_scan_wifi_start` (field 10), status enum 2, paged results enum 4, four AP entries per page in this client | Uses Android `WifiManager` via `AndroidNetworkHal`. Returns actual OS scan observations/cached results, with SSID, channel, RSSI, BSSID and auth mode. Fresh scan timing is controlled/throttled by Android |
| Wi-Fi config | `NetworkConfigPayload`: get status enum 0; set config enum 2 (SSID field 1/passphrase field 2); apply enum 4 | Config writes persist in virtual NVS. Status is connected only when the host phone is currently on the same Wi-Fi SSID and config was applied; otherwise it truthfully reports disconnected |
| Control | `NetworkCtrlPayload`: reset Wi-Fi enum 1 and re-provision enum 3 | Reset clears virtual credentials/state; reprovision is acknowledged and logged |
| Custom data | Client sends opaque app-specific bytes to the named endpoint and reads response bytes | `custom-data` echoes raw bytes and persists the last request in Base64 |
| Extra HAL endpoints | Not standard Espressif endpoints; discoverable through `0x2901` | `virtual-uart` UTF-8 append/`OK`; `virtual-gpio` JSON pin read/write; `virtual-flash` JSON NVS read/write/erase; `virtual-state` JSON state snapshot |

The Android project includes source protocol schemas, and the app uses a minimal protobuf wire-format codec for the implemented messages. The supplied source defines Security 1 as Curve25519 + AES-CTR, Security 2 as SRP6a + AES-GCM. Neither is implemented or advertised. Security 0 provides no confidentiality or authentication; the app warns that Wi-Fi credentials are sent in plaintext. Do not use it for sensitive networks.

## Android runtime and HAL

`VirtualDeviceService` exposes BLE through Android `BluetoothGattServer` and `BluetoothLeAdvertiser`. `AndroidNetworkHal` is the network adapter; `VirtualHal` owns persistent virtual device state. Virtual GPIO values do not drive electrical pins. UART is an internal terminal; physical USB-UART would require a later USB Host adapter. Timers/events are software-only. NVS and flash are app-private `SharedPreferences` JSON, not ESP flash geometry. State can be imported/exported as JSON using Android's Storage Access Framework. The UI reads live Android connectivity and persistent device values.

SoftAP/HTTP Protocomm, Thread provisioning, NFC, GPS, real SPI/I2C, ADC/DAC, ESP-NOW, raw ESP radio behavior, camera/mic integration, and GPIO voltage are not claimed. Android has BLE peripheral and local-only hotspot APIs, but LocalOnlyHotspot is a managed hotspot, not arbitrary ESP SoftAP behavior; a SoftAP HTTP transport has not been built here. The virtual device can use the phone's existing network only where host networking exposes it.

## Classic ESP32 firmware emulation status

This update adds the Android NDK/JNI/CMake scaffolding for the native backend
(`app/src/main/cpp/`: `esp32_qemu_jni.c`, `qemu_session.h`,
`qemu_driver_real.c`, `CMakeLists.txt`, wired into `app/build.gradle` via
`externalNativeBuild`, arm64-v8a). The JNI driver was written against the
real Espressif QEMU 9.2 APIs — machine type `esp32`, `qemu_init` /
`qemu_main_loop` / `qemu_cleanup`, reset/shutdown requests, UART0 as TCP
client to the Java loopback bridge, merged image as MTD flash with
read-write persistence, eFuse via `-global` — all verified in
`espressif/qemu@esp-develop` source (API table in
`app/src/main/cpp/qemu/android/BUILD.md`). The cross-compile procedure
(libffi → pcre2 → glib2 → pixman → QEMU `xtensa-softmmu` with the TCI
interpreter, which sidesteps Android's SELinux JIT denial) is scripted in
`app/src/main/cpp/qemu/android/build-qemu-android.sh`.

It does **not** yet include a compiled QEMU core: the Android/Bionic
cross-compile is the remaining work. Until `libesp32_qemu_android.so` is
built with `QEMU_CORE_AVAILABLE`, the Run action correctly reports
unavailable — now with the precise reason via `nativeGetLastError()` instead
of a generic message. No execution is faked at any layer: `nativeStart`
returns 0 unless a real QEMU machine thread is running.

Espressif's classic ESP32 QEMU machine is the closest existing match for ESP32-SOLO-1C. Its documented feature matrix includes Xtensa CPU, UART, NOR SPI flash, eFuse, timer groups and selected peripherals, but excludes Wi-Fi, Bluetooth, and general GPIO matrix/IOMUX. Its network option is OpenCores Ethernet, not ESP32 Wi-Fi. So any guest BLE/Wi-Fi/GPIO firmware functionality cannot be bridged as though QEMU implemented those peripherals. The Android-hosted GATT provisioning server continues independently. ([Espressif QEMU feature matrix](https://github.com/espressif/esp-toolchain-docs/blob/main/qemu/README.md))

The normal boot artifact is a merged flash image with second-stage bootloader at `0x1000`, partition table at `0x8000` (ESP-IDF default), and app image at an app partition offset; QEMU provides the first-stage ROM. Espressif documents 2/4/8/16 MiB flash sizes, an optional 124-byte eFuse backing file for security cases, and an OpenCores Ethernet/user-network option. A standalone app `.bin` does not meet this layout. The importer checks layout markers only; it does not prove checksum/signature/security validity or bootability. ([Espressif classic ESP32 QEMU guide](https://github.com/espressif/esp-toolchain-docs/blob/main/qemu/esp32/README.md))

Android hosting is technically possible through a native QEMU port, but Espressif does not document Android as a supported host. The Android NDK/Bionic port, QEMU dependencies, event-loop integration, JNI threading, native packaging, GPL compliance and on-device validation remain unbuilt. Linux QEMU binaries cannot be placed into an APK and run as Android apps. Full bridge boundaries are in [EMULATOR_PORT.md](EMULATOR_PORT.md).

`esp-emulator` is a separate candidate, but its supported chip set currently omits the original ESP32, so it does not solve the classic ESP32/SOLO target. It is not integrated here.

## Build

The project has a Gradle wrapper pinned to Gradle 8.9 and Android Gradle Plugin 8.7.3. With JDK 17 and Android SDK platform/build-tools 35 installed, build with `./gradlew :app:assembleDebug`; output is `app/build/outputs/apk/debug/app-debug.apk`. BLE peripheral support depends on the phone/controller and Android permissions. The current environment has no installed Gradle, no `javac`, no Android SDK, no `adb`, and no connected phone. The Gradle wrapper could not download Gradle because outbound socket access is denied, so compile/install and physical-device tests remain unrun. No APK is included because none could be produced honestly.

## Test results

| Capability/test | Result | Evidence |
|---|---|---|
| All supplied source and APK files inspected | PASS | ZIP/TAR trees and hashes match; both APK containers inspected |
| BLE advertisement/GATT server/device endpoint source | PASS (source checks) | `tests/check_project.py`; live RF discovery not available here |
| Security 0 session + Wi-Fi config/scan protobuf schemas mapped | PASS | Included source `.proto` schemas and Espressif client `Session`, `BLETransport`, `MessengeHelper`, `ESPDevice` paths |
| Protocol message wire vectors | PASS | `python3 tests/proto_vectors.py` checks copied schema field numbers and deterministic Security 0/config message bytes |
| App project static checks | PASS | `python3 tests/check_project.py` |
| Synthetic merged-image layout vector | PASS | `python3 tests/firmware_image_layout.py` validates expected ESP-IDF bootloader/table/app offsets and `AA 50` partition magic; this tests the format assumption, not Java execution |
| Image importer / loopback UART / JNI boundary | PASS (source checks only) | `FirmwareImageManager`, `EmulatorUartBridge`, `ClassicEsp32QemuBackend`; QEMU core is absent |
| Android compile / APK | NOT RUN | No installed Gradle, `javac`, or Android SDK; wrapper download blocked by outbound socket restriction |
| Second Android phone discovery, connect and exchange | NOT RUN | Requires APK install and phone BLE peripheral hardware; neither available |
| Espressif app Security 0 session | NOT RUN | Device-side handlers exist in source, but runtime build/device test unavailable |
| Provisioning config/reconnect persistence | NOT RUN | Code stores config; live client/storage test requires Android build/device |
| Security 1/2, SoftAP, Thread | FAIL / unsupported | Not advertised; no corresponding server implementation |
| Physical GPIO / guest GPIO matrix, Wi-Fi, BLE from firmware | FAIL / unsupported | Android has no general electrical GPIO; classic Espressif QEMU matrix omits GPIO matrix, Wi-Fi and Bluetooth |
| Arbitrary ESP32 firmware execution | FAIL / not implemented | No QEMU native core, CPU execution, or boot has been tested; image import is not execution |

## What works now

- Existing Android-hosted BLE GATT provisioning runtime and Security 0 handlers remain in the project.
- Android app-private virtual state, NVS, GPIO values, UART terminal, timer/event logs, and JSON import/export remain separate from ESP hardware.
- A merged flash image can be imported and screened for a supported flash size, bootloader image header, default partition table, and app image header.
- UART bridge sockets and terminal-to-guest transmit plumbing are implemented, but dormant without the missing QEMU core.
- The project has an explicit `ClassicEsp32QemuBackend` JNI boundary and refuses to report a firmware guest running until a native QEMU machine starts successfully.
- No APK was built and no live Android device/client tests ran in this environment.
