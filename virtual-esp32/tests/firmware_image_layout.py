"""Build and validate a representative ESP-IDF merged flash layout test vector.

This checks the format assumptions used by FirmwareImageManager; it does not run
Android Java code or claim that any ESP32 firmware boots in this project.
"""
from pathlib import Path

FLASH_SIZE = 2 * 1024 * 1024
BOOT = 0x1000
PARTITION_TABLE = 0x8000
APP = 0x10000

image = bytearray(b"\xff" * FLASH_SIZE)
image[BOOT] = 0xE9  # ESP image magic, second-stage bootloader
entry = bytearray(32)
entry[0:2] = b"\xaa\x50"  # ESP-IDF 0x50AA little-endian partition magic
entry[2] = 0x00  # app partition
entry[3] = 0x00  # factory subtype
entry[4:8] = APP.to_bytes(4, "little")
entry[8:12] = (0x100000).to_bytes(4, "little")
image[PARTITION_TABLE:PARTITION_TABLE + 32] = entry
image[APP] = 0xE9  # app image magic

assert len(image) in (2 << 20, 4 << 20, 8 << 20, 16 << 20)
assert image[BOOT] == 0xE9
assert image[PARTITION_TABLE:PARTITION_TABLE + 2] == bytes.fromhex("aa 50")
partition_offset = int.from_bytes(image[PARTITION_TABLE + 4:PARTITION_TABLE + 8], "little")
assert partition_offset == APP and image[partition_offset] == 0xE9
print("PASS: synthetic 2 MiB merged-image vector has bootloader, AA 50 factory partition, and app headers")
