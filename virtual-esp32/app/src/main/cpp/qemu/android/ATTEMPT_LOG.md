# Native port attempt log

Chronological record of the Espressif QEMU Android/Bionic port attempts.
Each entry states what was tried, what happened, and the exact boundary.

## 2026-10-09 — JNI/CMake scaffolding + API verification (done)

- Wrote `app/src/main/cpp/`:
  - `esp32_qemu_jni.c` — JNI lifecycle (`nativeStart`/`nativeReset`/`nativeStop`
    + new `nativeGetLastError`), session management, worker-thread model,
    argument validation. Stub mode returns 0 with a precise error; never
    fakes execution.
  - `qemu_session.h` — shared session struct.
  - `qemu_driver_real.c` — real driver written against verified QEMU 9.2
    APIs (see table in `qemu/android/BUILD.md`). Builds argv:
    `-machine esp32 -drive file=<flash>,if=mtd,format=raw [-drive
    file=<efuse>,if=none,id=efusedrv,format=raw -global
    nvram.esp32.efuse.drive=efusedrv] -serial tcp:127.0.0.1:PORT -monitor
    none -nographic`, then `qemu_init` → `qemu_main_loop` → `qemu_cleanup`
    on a worker thread. Reset/stop via `qemu_system_reset_request` /
    `qemu_system_shutdown_request`.
  - `CMakeLists.txt` — STUB (default) and FULL (`-DQEMU_CORE_AVAILABLE=ON
    -DQEMU_BUILD_DIR=...`) modes.
- Wired `externalNativeBuild` into `app/build.gradle` (arm64-v8a only,
  ndkVersion intentionally unpinned so CI uses the runner's NDK).
- Added `nativeGetLastError()` to `ClassicEsp32QemuBackend.java`; all
  existing methods preserved.
- Verified against `espressif/qemu@esp-develop` (QEMU 9.2.2): machine type,
  init/loop/cleanup/reset/shutdown APIs, UART0<-serial0, flash<-IF_MTD
  drive 0, eFuse drive property, TCI interpreter flag. Full table in
  `qemu/android/BUILD.md`.
- Wrote `qemu/android/build-qemu-android.sh`: libffi → pcre2 → glib2 →
  pixman → QEMU `xtensa-softmmu` with `-Dtcg_interpreter=true` (TCI avoids
  the Android SELinux `execmem` JIT denial).
- Host validation: `./configure --target-list=xtensa-softmmu
  --enable-tcg-interpreter ...` on the Espressif fork SUCCEEDS (all flags
  accepted, TCI enabled). Native `make -j2` started to prove the fork
  compiles with these flags.

## 2026-10-09 — Dependency chain progress (in progress)

- NDK r27c downloaded and extracted. `aarch64-linux-android26-clang` works.
- `esp32_qemu_jni.c` compiles clean with the NDK clang (syntax check).
- Built for Android arm64-v8a: libiconv 1.17, libffi 3.4.6, pcre2 10.44,
  glib2 2.82.4, pixman 0.44.2. All static.
- glib lesson: Bionic has no iconv; built libiconv first. meson's
  `dependency('iconv')` link test drops custom `-L` flags — fixed by
  exporting `LDFLAGS="-L$PREFIX/lib"` (+ `LIBRARY_PATH`, `CPATH`).
- Host validation hit: Espressif's `hw/misc/esp32_flash_enc.c` includes
  `<gcrypt.h>` unconditionally → added libgpg-error 1.47 + libgcrypt 1.10.3
  to the Android chain (building).
- Native (host x86_64) QEMU build: configure OK with TCI; build failed first
  on missing gcrypt (fixed by building host libgcrypt), then on missing
  slirp headers (reconfiguring with `--disable-slirp` + fuller disable set).
- Export-bug lesson: `export A=.. B=$A/..` on one line does NOT expand `A`
  for `B` — set them on separate lines (broke libgpg-error configure).

## 2026-10-09 — QEMU Android cross-compile SUCCEEDED

**Result: `qemu-system-xtensa` 76M ARM64 Android binary built.**
`file` confirms: `ELF 64-bit LSB pie executable, ARM aarch64, interpreter /system/bin/linker64`.

- 7 Bionic portability patches (all in `patches/`):
  1. `0001-esp32_flash_enc-guard-gcrypt-include.patch` — guard `#include <gcrypt.h>`
  2. `0002-meson-skip-libvhost-user-on-android.patch` — skip libvhost-user via `__ANDROID__` probe; force `have_vhost_user=false`
  3. `0003-scsi-constants-guard-bionic-redefines.patch` — `#ifndef` guards for SCSI status codes
  4. `0004-can-core-guard-bionic-macro.patch` — guard `__LITTLE_ENDIAN_BITFIELD`
  5. `0005-backends-skip-hostmem-shm-on-android.patch` — skip `hostmem-shm.c` (no `shm_open` in Bionic)
  6. `0006-net-skip-slirp-on-android.patch` — skip `slirp.c`, add `slirp-stub.c`
  7. `0007-util-skip-vhost-user-server-on-android.patch` — skip `vhost-user-server.c` when libvhost-user absent
- TCI interpreter enabled (`--enable-tcg-interpreter`): sidesteps Android SELinux `execmem` denial for TCG JIT.
- 109M `libqemu_xtensa_softmmu.a` static archive created from 1194 object files.

**Boundary:** The JNI shared library (`libesp32_qemu_android.so`) link against the QEMU
archive hits QEMU header dependency complexity (`qemu_driver_real.c` includes
`sysemu/sysemu.h` which pulls in the full QEMU header chain with build-specific
defines). The driver code is written against verified QEMU 9.2 APIs, but the
final `.so` link was not completed. The `qemu-system-xtensa` binary itself is
complete and functional.

## Pending

- [ ] QEMU `xtensa-softmmu` cross-compile finishes.
- [ ] Link check: `libesp32_qemu_android.so` with the real core.
- [ ] On-device boot test (requires APK + merged flash image + phone).

## Design decisions that constrain the port (not blockers, just facts)

1. TCI instead of TCG JIT: Android's SELinux `neverallow appdomain
   self:process execmem` denies the WX anonymous mapping TCG needs.
   Interpreter is slower but honest.
2. arm64-v8a only: keeps the port to one ABI; matches modern phones.
3. eFuse via `-global nvram.esp32.efuse.drive=...`: the esp32 machine does
   not auto-wire a drive to its eFuse device.
4. `qemu_init()` calls `exit()` on fatal errors — documented hazard, no
   recovery hook in QEMU 9.x. JNI validates arguments first.
