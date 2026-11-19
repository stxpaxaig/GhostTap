# Native Sources — VirtualESP32 (release supplement, added by worker H 2026-11-07)

The corrected tree's own native files live in `../app/src/main/cpp/`
(`esp32_qemu_jni.c`, `qemu_driver_real.c`, `qemu_session.h`,
`esp32_rsa_stub.c`, `CMakeLists.txt` — unwired, STUB default — and
`qemu/android/` docs + patches + `build-qemu-android.sh`).
This directory adds the pieces that were NOT in the corrected tree.

## android-jni/ — the JNI driver sources, with remediation R-01 applied

| File | Lines | SHA-256 | Note |
|---|---|---|---|
| `esp32_qemu_jni.c` | 332 | `2eacfef11097517ce5810c60386e68525aaea490671c0ca4afac07c369ce7d93` | **SYNCED.** Contains `nativeGetSessionState` (declared line 84, defined line 319). Copied from `../app/src/main/cpp/esp32_qemu_jni.c` (byte-identical). |
| `qemu_driver_real.c` | 220 | identical in both locations | Copied from `../app/src/main/cpp/`. |
| `qemu_session.h` | 28 | identical in both locations | Copied from `../app/src/main/cpp/`. |
| `esp32_rsa_stub.c` | 74 | identical in both locations | Copied from `../app/src/main/cpp/`. |
| `meson.build` | — | — | Copied from `~/workspace/virtualesp32/native/qemu-src/android-jni/meson.build`. **WARNING:** this standalone file links `../libqemu_xtensa_softmmu.a`, which does not exist in `qemu-src/build/` — do NOT use it. The real JNI target is inline in top-level `meson.build` ~4300–4320 using `lib.extract_all_objects(recursive: true)`. Included for completeness only. |

**Why the sync exists:** the on-disk QEMU source tree
`~/workspace/virtualesp32/native/qemu-src/android-jni/esp32_qemu_jni.c` is the
restored **288-line pre-getter original** (SHA-256
`c73ba51c8135f7e683d49f0ada035df520cd4f5e59a81fc9a96aee039116c01a`, zero
occurrences of `SessionState`, modified 2026-11-06 01:00:10 — after the
00:59:53 `.so` build). A naive Meson rebuild from `qemu-src/` as it stands
produces a 6-symbol `.so` without `nativeGetSessionState`, and the Java layer
silently falls back to flag-only status. **Before any native rebuild, sync
`qemu-src/android-jni/esp32_qemu_jni.c` from the 332-line copy in this
directory (or from `../app/src/main/cpp/esp32_qemu_jni.c`), diff the other
two files, then re-run the `nm -D` check** (expect all 7
`Java_dev_virtualesp32_ClassicEsp32QemuBackend_*` symbols). The on-disk
`qemu-src/` copy itself was NOT modified by this assembly.

**Rebuild procedure (from `docs/BUILD_AND_REPRODUCTION_GUIDE.md` §6 and
`../app/src/main/cpp/REBUILD.md`):** NDK r27c
(`~/workspace/virtualesp32/native/android-ndk-r27c/`,
`aarch64-linux-android26-clang`, API 26), meson + ninja + python3 +
pkg-config, deps in `~/workspace/virtualesp32/deps-arm64-v8a/`, then
`cd qemu-src/build && ninja libesp32_qemu_android.so`, and copy the result to
`../app/src/main/jniLibs/arm64-v8a/libesp32_qemu_android.so`. Expected `.so`
SHA-256: `6a1fde2ceea5d9c7c9802fea161824b40dbfb392266aae2a72d2708833694894`.

## patches/ — the 7 Android-port patches

Copies of `../app/src/main/cpp/qemu/android/patches/0001–0007` (all inherit
QEMU's GPLv2): gcrypt-include guard, vhost-user skip, SCSI Bionic guard,
CAN-core Bionic guard, hostmem-shm skip, slirp skip, vhost-user-server skip.
See `docs/THIRD_PARTY_LICENSES.md` §2 for what each changes.

## qemu-fork-diff/ — the captured QEMU fork working-tree diff (remediation R-02)

| File | Contents |
|---|---|
| `qemu-fork-working-tree.diff` | `git diff` of the Espressif QEMU fork working tree, captured 2026-11-07: **9 files, 405 lines, 236 insertions / 4 deletions**. Includes `hw/gpio/esp32_gpio.c` (+135) and `include/hw/gpio/esp32_gpio.h` (+29) — the GPIO JNI hooks that previously had NO patch file. |
| `qemu-fork-working-tree.diffstat.txt` | `git diff --stat` for the same. |
| `qemu-fork-status.txt` | `git status --porcelain` at capture time + SHA-256 of the 5 `android-jni/` files as they stood on disk. |
| `qemu-fork-base-commit.txt` | Base commit: `febae18` (esp-develop, "hw/riscv: implement ESP32-C6 machine"). |
| `qemu-fork-remote.txt` | Remote: `https://github.com/espressif/qemu.git`. |
| `slirp-stub.c` | The untracked `net/slirp-stub.c` file, copied verbatim. |

**Scope and limits of this capture (honest):** the diff covers the 9
*tracked, modified* files. The untracked `android-jni/` directory is covered
by the full-file copies in `android-jni/` above (synced version) and the
hashes in `qemu-fork-status.txt`; `net/slirp-stub.c` is copied here;
`subprojects/.wraplock` (untracked, a Meson lock file) is NOT included — it
is regenerable build metadata, not source. **VERIFIED 2026-11-07 (remediation
of H-04):** the diff was applied to a clean `esp-develop` checkout at
`febae18` from the official remote (`git apply --check` PASS, `git apply` OK)
and the complete captured source rebuilt `libesp32_qemu_android.so`
**byte-identical** to the shipped `.so` (SHA-256 `6a1fde2c…`, 81,361,464
bytes, all 7 JNI symbols verified) — see `docs/REMEDIATION-REPORT.md` §2 for
the full record and the reproducible commands. One caveat: byte-identical
reproduction is path-dependent (the `.so` embeds absolute source paths) and
requires the `-ffile-prefix-map` / `-fdebug-prefix-map` remap documented
there; without the remap the rebuild is functionally identical with a
different hash. The full ~453 MB fork checkout itself is not in this release;
clone it from the remote above.

## What is still NOT in this release (native side)

- The full QEMU fork checkout and its 243 MB `build/` tree (incl. the
  `build.ninja` and the built `.so` there — the `.so` in the corrected tree
  and APK is byte-identical to it).
- The NDK (r27c), `deps-arm64-v8a/` (194 MB), `build-tmp/` (678 MB),
  `ndk-wrappers/`, `install-arm64-v8a/` (empty).
- The canonical host-QEMU binary is gone from its path (D report H-08,
  environment FAIL); host-boot testing used the scratch copy at
  `~/workspace/tmp/vesp32-test-scratch/work/qemu/bin/qemu-system-xtensa`.
