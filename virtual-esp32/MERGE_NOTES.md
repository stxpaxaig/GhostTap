# VirtualESP32 merge notes

## Selected base
`virtualesp32-bridge9.zip` is the selected base because it contains the newer
Ghost Tap receiver/service, event bus, guest GPIO monitor, reconnect-capable
UART bridge, and the larger ARM64 native library. Its native library exports
`nativeGetGpio2` and `nativeSetGpio4`, which are absent from the library in
`virtualesp32-final.zip`.

## Preserved components
All project files from Bridge9 are retained, including QEMU Android build
scripts and patches, JNI/CMake sources, ROM assets, firmware validation,
protocol files, and the ARM64 prebuilt library. The final.zip archive was
compared file-by-file; it did not contain unique project files. Its differing
runtime/backend/UI files were older relative to Bridge9.

## Applied integration repairs
- Added the `LOAD_FIRMWARE` broadcast action to the exported receiver's manifest filter.
- `BridgeCommandReceiver` accepts the controller's `uri` extra and Intent data URI,
  imports through `FirmwareImageManager.importAndInspect`, and keeps legacy `path`
  input compatibility.
- Firmware-loaded response fields now come from the actual imported file and
  inspector summary; no validation claims are fabricated.
- Guest start/reset responses are only sent after the runtime returns `running`
  and its running flag is true.
- Reset without an imported image returns an error.
- Unsupported GPIO operations and operations attempted while stopped return
  explicit errors.

## Known verification boundary
The Bridge9 ARM64 `.so` exports GPIO JNI entry points, but the checked-in
`esp32_qemu_jni.c` source does not define those two entry points, and the
checked-in CMake configuration defaults to a stub build. The prebuilt library
is preserved as supplied; a fully reproducible native rebuild is not established
by this merge. The Gradle app build also depends on its configured Android SDK
and Gradle 8.7 distribution being available.
