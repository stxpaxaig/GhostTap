from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
manifest = (root / "app/src/main/AndroidManifest.xml").read_text()
service = (root / "app/src/main/java/dev/virtualesp32/VirtualDeviceService.java").read_text()
hal = (root / "app/src/main/java/dev/virtualesp32/VirtualHal.java").read_text()
activity = (root / "app/src/main/java/dev/virtualesp32/MainActivity.java").read_text()
image_manager = (root / "app/src/main/java/dev/virtualesp32/FirmwareImageManager.java").read_text()
emu_runtime = (root / "app/src/main/java/dev/virtualesp32/FirmwareEmulatorRuntime.java").read_text()
qemu_backend = (root / "app/src/main/java/dev/virtualesp32/ClassicEsp32QemuBackend.java").read_text()
uart_bridge = (root / "app/src/main/java/dev/virtualesp32/EmulatorUartBridge.java").read_text()

assert 'android.permission.BLUETOOTH_ADVERTISE' in manifest
assert 'android.permission.BLUETOOTH_CONNECT' in manifest
assert 'BluetoothGattServer' in service and 'startAdvertising' in service
assert '00002901-0000-1000-8000-00805f9b34fb' in service
for endpoint in ("proto-ver", "prov-session", "prov-scan", "prov-config", "prov-ctrl", "virtual-state"):
    assert endpoint in service, endpoint
assert '0xff53,0xff51,0xff54,0xff52,0xff55' in service
assert 'UNSUPPORTED: ESP provisioning protocol handler is not implemented' not in service
assert 'Security 0 session established' in service
assert 'NetworkScan' not in service  # protobuf bytes are handled by the minimal wire codec
assert 'no_pop' in service and 'wifi_scan' in service
assert 'exportState()' in hal and 'importState(JSONObject' in hal
for panel in ("POWER / BOOT", "BLE", "NETWORK", "UART", "GPIO", "FLASH / NVS", "PROVISIONING", "LOGS / TERMINAL"):
    assert panel in activity, panel
assert 'm0!=0xaa||m1!=0x50' in image_manager
assert 'No ESP image header at bootloader offset 0x1000' in image_manager
assert 'nativeStart' in qemu_backend and 'Native classic-ESP32 QEMU library is not packaged' in qemu_backend
assert 'nativeGetLastError' in qemu_backend
# NDK/JNI native layer contracts
jni = (root / "app/src/main/cpp/esp32_qemu_jni.c").read_text()
driver = (root / "app/src/main/cpp/qemu_driver_real.c").read_text()
cmake = (root / "app/src/main/cpp/CMakeLists.txt").read_text()
gradle = (root / "app/build.gradle").read_text()
assert 'Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeStart' in jni
assert 'Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeReset' in jni
assert 'Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeStop' in jni
assert 'Java_dev_virtualesp32_ClassicEsp32QemuBackend_nativeGetLastError' in jni
assert 'QEMU_CORE_AVAILABLE' in jni and 'QEMU core not compiled into this APK' in jni
assert 'qemu_init' in driver and 'qemu_main_loop' in driver and 'qemu_cleanup' in driver
assert 'qemu_system_reset_request' in driver and 'qemu_system_shutdown_request' in driver
assert '"esp32"' in driver and 'tcp:127.0.0.1' in driver and 'if=mtd,format=raw' in driver
assert 'esp32_qemu_android' in cmake and 'QEMU_CORE_AVAILABLE' in cmake
assert 'externalNativeBuild' in gradle and 'src/main/cpp/CMakeLists.txt' in gradle
assert "abiFilters 'arm64-v8a'" in gradle
assert 'if(!ClassicEsp32QemuBackend.available())' in emu_runtime
assert 'new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))' in uart_bridge
assert 'FirmwareEmulatorRuntime.writeUart(tx)' in activity
print("PASS: Android runtime/provisioning panels plus honest QEMU availability gate, merged-image screening, and loopback UART bridge source contracts")
