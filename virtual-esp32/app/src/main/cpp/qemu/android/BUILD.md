# QEMU classic-ESP32 Android/Bionic port

Target: Espressif's QEMU fork (`esp-develop`, QEMU 9.2 base), `xtensa-softmmu`
with the `esp32` machine, cross-compiled for `arm64-v8a` with the Android NDK,
driven from `libesp32_qemu_android.so` via the JNI lifecycle in
`app/src/main/cpp/`.

## Why this port is shaped the way it is

1. **No JIT on Android.** Since Android 8, SELinux `neverallow appdomain
   self:process execmem` denies apps writable+executable anonymous memory.
   QEMU's TCG JIT needs exactly that (`mmap(PROT_WRITE|PROT_EXEC)` for the
   translation buffer). So the port uses QEMU's Tiny Code Interpreter:
   `-Dtcg_interpreter=true`. This is ~10-50x slower than JIT, but an ESP32 is
   a 240 MHz microcontroller — TCI on a modern phone SoC is fast enough for
   real firmware, and it is honest execution (Xtensa instructions decoded and
   executed one by one), not a fake.
2. **Minimal feature set.** Only `xtensa-softmmu` is built. UI (GTK/SDL/VNC),
   tools, docs, networking backends beyond what the esp32 machine needs, and
   optional accelerators are all disabled. Required dependencies shrink to:
   `zlib` (from the NDK stable API — do NOT build), `libffi`, `pcre2`,
   `glib2`, `pixman`.
3. **Static, no modules.** QEMU is built as static archives linked directly
   into `libesp32_qemu_android.so`. No `dlopen` of QEMU modules at runtime.

## Build procedure (Linux host + Android NDK)

Prerequisites: Android NDK r26+, meson, ninja, python3, pkg-config.

```sh
# 1. Fetch this repo's QEMU reference (or use your own checkout):
#    git clone --branch esp-develop https://github.com/espressif/qemu.git

# 2. Build the dependencies for arm64-v8a, then QEMU itself:
export ANDROID_NDK_ROOT=/path/to/android-ndk
./build-qemu-android.sh /path/to/espressif-qemu arm64-v8a 26

# 3. Output lands in ./install-arm64-v8a/:
#      include/ — QEMU headers (public + generated)
#      lib/libqemu_xtensa_softmmu.a — the softmmu core archive
#      lib/libqemu_deps.a — glib/ffi/pcre2/pixman archives
```

Then point the app's CMake at it:

```sh
# in app/build.gradle defaultConfig.externalNativeBuild.cmake.arguments:
'-DQEMU_CORE_AVAILABLE=ON',
'-DQEMU_BUILD_DIR=/abs/path/to/install-arm64-v8a'
```

## What was verified while writing the JNI driver

Against `espressif/qemu@esp-develop` (checked 2026-10-09):

| Item | Location | Status |
|---|---|---|
| QEMU base version | `VERSION` | 9.2.2 |
| Machine type `esp32` | `hw/xtensa/esp32.c` (`MACHINE_TYPE_NAME("esp32")`) | verified |
| `qemu_init` / `qemu_main_loop` / `qemu_cleanup` | `include/sysemu/sysemu.h:98-100` | verified |
| `qemu_system_reset_request` / `qemu_system_shutdown_request` | `include/sysemu/runstate.h:84,94` | verified |
| `ShutdownCause` enum values | `qapi/run-state.json:96` | verified (`host-qmp-system-reset`, `host-qmp-quit`) |
| UART0 <- `serial_hd(0)` | `hw/xtensa/esp32.c:812` | verified |
| Flash <- first `IF_MTD` drive | `hw/xtensa/esp32.c:797` | verified |
| eFuse `drive` property | `hw/nvram/esp32_efuse.c:281`, type `nvram.esp32.efuse` | verified |
| TCI interpreter option | `meson.build:882` (`-Dtcg_interpreter=true`) | verified |
| TCP client serial (`-serial tcp:host:port`) | QEMU chardev tcp backend | standard QEMU behavior |

## Known port risks (not hidden)

- **Patches.** `patches/` holds the minimal source patches for the Android
  port, currently just the `CONFIG_GCRYPT` include guard for
  `hw/misc/esp32_flash_enc.c` (the implementation already had a qcrypto
  fallback; only the include was unconditional). Applied by
  `build-qemu-android.sh`.

- **Bionic libc gaps.** QEMU 9.x assumes glibc-isms in places. Known areas
  to watch: `eventfd`/`signalfd` availability vs `minSdk 26` (Bionic added
  wrappers over several API levels), `pthread` feature macros, `dladdr`,
  `getauxval`. Each is patchable, but each patch must be found by compiling.
- **`qemu_init()` calls `exit()` on fatal errors.** There is no recovery
  hook in QEMU 9.x. A fatal init error kills the app process. The JNI layer
  validates arguments first; the residual risk is documented, not fixed.
- **Single instance.** QEMU keeps global state; only one guest per process.
  The Java `FirmwareEmulatorRuntime` already serializes sessions.
- **eFuse `-global` wiring.** The esp32 machine does not auto-wire a drive
  to its eFuse device, so the driver passes
  `-global nvram.esp32.efuse.drive=efusedrv`. If a future fork changes the
  device type name, this needs updating. Without the file the device simply
  starts zeroed — boot does not depend on it.
- **Performance.** TCI interprets every guest instruction. ESP32 firmware
  with tight timing loops (e.g. bit-banged protocols) may run slower than
  real time. UART output and flash writes are functionally correct.
- **GPL compliance.** QEMU is GPL-2.0. Shipping this .so means shipping
  QEMU: preserve copyright notices, provide the corresponding source for
  the exact fork commit used, and include the license text. Dependency
  licenses (LGPL glib, MIT pixman, etc.) need their notices too.

## Blocker boundary (updated as the port is attempted)

- [x] JNI lifecycle + CMake scaffolding: DONE, builds in stub mode.
- [x] Driver written against verified QEMU 9.2 APIs: DONE.
- [ ] NDK cross-compile of libffi/pcre2/glib2/pixman: attempted — see below.
- [ ] QEMU `xtensa-softmmu` cross-compile with `-Dtcg_interpreter=true`.
- [ ] On-device boot test with a real merged flash image.

Attempt log lives in `ATTEMPT_LOG.md` next to this file.
