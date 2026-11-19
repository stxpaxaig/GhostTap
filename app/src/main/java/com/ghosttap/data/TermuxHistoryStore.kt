package com.ghosttap.data

import android.content.Context
import com.ghosttap.termux.TermuxRun
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local persistence for Termux run history and settings.
 *
 * Follows UsbCommandHistoryStore / RecentFirmwareStore exactly: one
 * JSON file in the app's private files dir, kotlinx-serialization,
 * failures swallowed (a corrupt file means an empty history, never a
 * crash), atomic tmp+rename on save (copied from LearnedStore).
 *
 * NOT stored here, ever: the bridge session token. It lives in RAM
 * only and a new one is made every time the bridge starts.
 * No new Room entities — IntelDb is untouched.
 */
@Serializable
data class TermuxSettings(
    /** Capability id -> user switch. Off means Ghost Tap won't offer it. */
    val capabilityEnabled: Map<String, Boolean> = emptyMap(),
    /** Let Ghost Tap offer Termux steps when it makes a plan. Default on (zero-config). */
    val useTermuxInPlans: Boolean = true,
    /** Keep past runs on this phone. Default on. */
    val keepHistory: Boolean = true,
    /** How long to wait for Termux to answer a run, in ms. */
    val defaultTimeoutMs: Long = 60_000L
)

/**
 * One script the user added in Termux, as found by the library
 * lister. Only the file name and the shown title are kept — the
 * runnable entry is rebuilt from the file name on load, so a stale
 * entry can never point Ghost Tap at a file that is not there.
 * Nothing here is a secret: file names only.
 */
@Serializable
data class LibraryEntry(
    val fileName: String,
    val title: String
)

/**
 * The last probe answer, exactly as Termux sent it: the raw
 * {"tool": true/false} object plus when it arrived. Only the tools
 * object is kept — never the bridge token, which is not in it and
 * must never be stored. Restoring this on open is what keeps the
 * "What Termux can do" list filled in after the app restarts,
 * instead of falling back to "not checked yet" while past runs
 * survive (v3 fix).
 */
@Serializable
data class TermuxProbeSnapshot(
    val toolsJson: String,
    val checkedAtMs: Long = 0L
)

class TermuxHistoryStore(context: Context) {
    private val historyFile: File = File(context.filesDir, "termux_history.json")
    private val settingsFile: File = File(context.filesDir, "termux_settings.json")
    private val libraryFile: File = File(context.filesDir, "termux_library.json")
    private val probeFile: File = File(context.filesDir, "termux_probe.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Most-recent-first run history, capped at 20. Empty when nothing is saved. */
    fun loadHistory(): List<TermuxRun> {
        return runCatching {
            if (!historyFile.exists()) return emptyList()
            json.decodeFromString(ListSerializer(TermuxRun.serializer()), historyFile.readText())
        }.getOrDefault(emptyList())
    }

    fun saveHistory(history: List<TermuxRun>) {
        saveAtomically(historyFile) {
            json.encodeToString(ListSerializer(TermuxRun.serializer()), history.take(20))
        }
    }

    fun loadSettings(): TermuxSettings {
        return runCatching {
            if (!settingsFile.exists()) return TermuxSettings()
            json.decodeFromString(TermuxSettings.serializer(), settingsFile.readText())
        }.getOrDefault(TermuxSettings())
    }

    fun saveSettings(settings: TermuxSettings) {
        saveAtomically(settingsFile) {
            json.encodeToString(TermuxSettings.serializer(), settings)
        }
    }

    fun clearHistory() {
        runCatching { if (historyFile.exists()) historyFile.delete() }
    }

    /** Scripts found in Termux, most recently listed first. Empty when nothing is saved. */
    fun loadLibrary(): List<LibraryEntry> {
        return runCatching {
            if (!libraryFile.exists()) return emptyList()
            json.decodeFromString(ListSerializer(LibraryEntry.serializer()), libraryFile.readText())
        }.getOrDefault(emptyList())
    }

    fun saveLibrary(entries: List<LibraryEntry>) {
        saveAtomically(libraryFile) {
            json.encodeToString(ListSerializer(LibraryEntry.serializer()), entries)
        }
    }

    /** The last probe answer, or null when no probe has ever answered. */
    fun loadProbe(): TermuxProbeSnapshot? {
        return runCatching {
            if (!probeFile.exists()) return null
            json.decodeFromString(TermuxProbeSnapshot.serializer(), probeFile.readText())
        }.getOrNull()
    }

    /** Keeps the last probe answer on disk. The tools object only — never the bridge token. */
    fun saveProbe(toolsJson: String) {
        saveAtomically(probeFile) {
            json.encodeToString(
                TermuxProbeSnapshot.serializer(),
                TermuxProbeSnapshot(toolsJson = toolsJson, checkedAtMs = System.currentTimeMillis())
            )
        }
    }

    private fun saveAtomically(file: File, write: () -> String) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(write())
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IllegalStateException("could not replace ${file.name}")
            }
        }
    }
}
