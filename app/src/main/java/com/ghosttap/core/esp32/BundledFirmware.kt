package com.ghosttap.core.esp32

import android.content.Context
import androidx.core.content.FileProvider
import java.io.File

/**
 * UNIFIED BUILD (2026-11-18) — the Virtual ESP32 software that ships
 * inside Ghost Tap.
 *
 * The first-pick software is the MicroPython 4 MB test image
 * (virtualesp32-test-micropython-4mb.bin, MicroPython v1.28.0). It
 * lives in the app's assets, so LOAD SOFTWARE just loads it — no file
 * to pick, no other app to open, nothing to configure. On first use
 * it is copied to app-private cache and handed to the board through
 * the existing client load call, exactly like a picked file used to
 * be (same validation, same FileProvider share, same grant).
 *
 * Call from a background thread — the copy is a 4 MB file write.
 * Returns the check line in plain words (the same text the board
 * screen shows as "Last check").
 */
const val BUNDLED_FIRMWARE_ASSET = "virtualesp32-test-micropython-4mb.bin"
const val BUNDLED_FIRMWARE_NAME = "virtualesp32-test-micropython-4mb.bin"

fun loadBundledFirmware(context: Context, client: VirtualEsp32Client): String {
    val destDir = File(context.cacheDir, "firmware").apply { mkdirs() }
    val destFile = File(destDir, BUNDLED_FIRMWARE_NAME)
    try {
        context.assets.open(BUNDLED_FIRMWARE_ASSET).use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    } catch (e: Exception) {
        return "Couldn't get the built-in software ready: ${e.message}"
    }
    val result = FirmwareValidator.validate(destFile)
    val summary = buildString {
        append(result.summary)
        if (result.issues.isNotEmpty()) {
            append(" | Issues: ${result.issues.joinToString("; ")}")
        }
    }
    val shareUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        destFile
    )
    client.loadFirmware(shareUri)
    return summary
}
