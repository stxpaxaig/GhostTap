package com.ghosttap.termux

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Scoped-storage-safe file exchange between Ghost Tap and Termux.
 *
 * Ghost Tap can NEVER reach into Termux's private storage, and Termux
 * can never reach into Ghost Tap's. Files cross the gap only through
 * this class:
 * - Ghost Tap stages a file in its own cache dir under "termux/"
 *   (new FileProvider entry in res/xml/filepaths.xml — the existing
 *   "firmware/" entry is untouched).
 * - The file is handed to Termux with an explicit per-URI grant
 *   (the same grant-then-revoke shape VirtualEsp32Client.loadFirmware
 *   uses — Android ignores FLAG_GRANT_READ_URI_PERMISSION on
 *   broadcasts, so the explicit grant is the load-bearing part).
 * - The grant is revoked once Termux has had its chance to copy the
 *   file ([revokeShare]).
 *
 * The bridge session token NEVER passes through here — it is handed
 * to Termux only as an in-memory RUN_COMMAND argument (see
 * [TermuxClient]). Exchange files hold requests, answers, logs and
 * diagnostics only.
 */
class TermuxExchange(private val context: Context) {

    companion object {
        const val EXCHANGE_DIR = "termux"
        const val EXPORTS_DIR = "exports"
    }

    /** Ghost Tap's side of the exchange: cacheDir/termux/. */
    val exchangeDir: File
        get() = File(context.cacheDir, EXCHANGE_DIR).apply { mkdirs() }

    /** Stages a JSON file for Termux to read. Atomic tmp+rename, like LearnedStore. */
    fun stageJson(fileName: String, jsonText: String): File? {
        return stageText(safeName(fileName), jsonText)
    }

    /** Stages any text file (request, answer, log, diagnostic). */
    fun stageText(fileName: String, text: String): File? {
        return runCatching {
            val safe = safeName(fileName)
            val dir = exchangeDir
            val tmp = File(dir, "$safe.tmp")
            val file = File(dir, safe)
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IllegalStateException("could not replace $safe")
            }
            file
        }.getOrNull()
    }

    /** Reads back a staged file. Null when it is missing or unreadable. */
    fun readStaged(fileName: String): String? {
        return runCatching {
            val file = File(exchangeDir, safeName(fileName))
            if (!file.exists()) null else file.readText()
        }.getOrNull()
    }

    /** Names of staged files, newest first. */
    fun listStaged(): List<String> {
        return runCatching {
            exchangeDir.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".tmp") }
                ?.sortedByDescending { it.lastModified() }
                ?.map { it.name }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /** Deletes one staged file. */
    fun deleteStaged(fileName: String) {
        runCatching { File(exchangeDir, safeName(fileName)).delete() }
    }

    /**
     * Hands a staged file to Termux with an explicit read grant.
     * Returns the content Uri Termux can open, or null when sharing
     * failed. Call [revokeShare] once Termux has copied the file.
     */
    fun shareToTermux(file: File): Uri? {
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            context.grantUriPermission(
                TermuxClient.PKG,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            uri
        }.getOrNull()
    }

    /** Drops the read grant handed out by [shareToTermux]. */
    fun revokeShare(uri: Uri) {
        runCatching {
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Exports a diagnostics file where the user can find it — the same
     * getExternalFilesDir(null)/exports spot GhostTapViewModel
     * exportLogsToFile uses. Returns the file, or null on failure.
     */
    fun exportDiagnostics(fileName: String, text: String): File? {
        return runCatching {
            val dir = File(context.getExternalFilesDir(null), EXPORTS_DIR).apply { mkdirs() }
            val file = File(dir, safeName(fileName))
            file.writeText(text)
            file
        }.getOrNull()
    }

    /** File names come from Ghost Tap, never from Termux — still, no path tricks. */
    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "termux.txt" }
}
